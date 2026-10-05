package io.github.diegobr4nd.lectorbilingue.data

import android.app.ActivityManager
import android.app.Application
import android.net.Uri
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.models.Catalog
import io.github.diegobr4nd.lectorbilingue.models.DownloadState
import io.github.diegobr4nd.lectorbilingue.models.Models
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lo que las pantallas necesitan del gestor de modelos. Las pantallas dependen de esta interfaz
 * (y no de [ModelHub]) para que las pruebas instrumentadas usen una versión falsa.
 */
interface ModelHubApi {
    /** Pares del catálogo con sus filas; cambia con [refresh], con las descargas, al borrar y al importar. */
    val pairs: StateFlow<List<PairStatus>>

    /** false hasta terminar la primera lectura del catálogo y lo instalado: antes, [pairs] vacío no significa "sin catálogo". */
    val loaded: StateFlow<Boolean>

    /** Refresca el catálogo de la red con un tope de espera; devuelve un mensaje fijo o null si no hay nada que avisar. */
    suspend fun refresh(): ModelMessage?

    /** Encola la descarga de [modelId]; su avance llega por [pairs]. */
    fun download(modelId: String)

    /** Cancela la descarga de [modelId]; el estado CANCELLED llega por [pairs]. */
    fun cancel(modelId: String)

    /** Borra el modelo instalado de [engine] y [pair]: [ModelMessage.DELETE_OK] o el mensaje del error. */
    suspend fun delete(engine: EngineId, pair: String): ModelMessage?

    /** Importa un modelo desde el .zip de [uri]: [ModelMessage.IMPORT_OK] o el mensaje del error. */
    suspend fun import(uri: Uri): ModelMessage

    /** RAM total del teléfono en bytes. */
    fun totalRamBytes(): Long
}

/**
 * Gestor de modelos compartido por las pantallas (Bienvenida, Inicio, Idiomas): catálogo, descargas,
 * borrar e importar. Reúne lo que hace la pantalla de prueba del motor, sin cargar motores.
 *
 * - Toda lectura de disco va en [io], nunca en el hilo principal.
 * - Nunca registra ni muestra texto de excepciones: solo mensajes fijos ([ModelMessage]).
 * - Un colector por modelo descargándose (con `Models.downloadInfo`); se detiene en el estado final.
 * - Vive lo que vive la app: su ámbito de corrutinas (un "scope", el dueño de las tareas en segundo plano)
 *   no se cancela.
 */
class ModelHub(
    private val app: Application,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val refreshTimeoutMs: Long = 5_000,
) : ModelHubApi {
    /** Lo que se combina para formar [pairs]. Solo se lee y se cambia con [stateMutex]. */
    private data class Inputs(
        val catalog: Catalog? = null,
        val installed: Set<Pair<String, String>> = emptySet(),
        val downloads: Map<String, DownloadState?> = emptyMap(),
    )

    // El manejador no registra nada: solo evita que un error futuro no atrapado cierre la app.
    private val scope = CoroutineScope(SupervisorJob() + io + CoroutineExceptionHandler { _, _ -> })

    /**
     * Candado (un "Mutex": deja pasar a una corrutina a la vez) de [inputs] y [_pairs]: una lectura vieja
     * del disco nunca pisa una nueva, y [pairs] cambia en el mismo paso que los datos.
     */
    private val stateMutex = Mutex()
    private var inputs = Inputs()
    private val _pairs = MutableStateFlow<List<PairStatus>>(emptyList())
    override val pairs: StateFlow<List<PairStatus>> = _pairs.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    override val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val watchers = HashMap<String, Job>()
    private val refreshLock = Any()
    private var inFlightRefresh: Deferred<Throwable?>? = null

    private val ram: Long by lazy {
        val info = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        info.totalMem
    }

    init {
        scope.launch {
            // Recuperación del arranque (barata tras la primera vez): limpia restos de instalaciones cortadas.
            runCatching { Models.recover(app) }
            try {
                reload()
            } finally {
                _loaded.value = true
            }
            resumeActiveDownloads()
        }
    }

    override fun totalRamBytes(): Long = ram

    override suspend fun refresh(): ModelMessage? {
        // El refresco corre en el ámbito del gestor: si vence el tope, sigue en segundo plano y guarda el
        // catálogo nuevo cuando llegue (withTimeoutOrNull no puede cortar una lectura de red bloqueante).
        val refreshError = withTimeoutOrNull(refreshTimeoutMs) { startRefresh().await() }
        val current = reload()
        return ModelActions.resolveCatalog(current, refreshError).second
    }

    /** Un solo refresco a la vez: si ya hay uno en marcha, se espera ese. Devuelve su error, o null. */
    private fun startRefresh(): Deferred<Throwable?> = synchronized(refreshLock) {
        inFlightRefresh?.takeIf { it.isActive }?.let { return it }
        scope.async {
            try {
                Models.catalogRepository(app).refresh()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }.also { reload() }
        }.also { inFlightRefresh = it }
    }

    override fun download(modelId: String) {
        scope.launch {
            // Si ya está en cola o en curso, se conserva su avance: solo se vuelve a seguir si nadie la sigue.
            // La comprobación y el "en cola" van en el mismo paso: dos toques seguidos no encolan dos veces.
            val alreadyActive = withState { s ->
                if (HubRules.keepsCurrentDownload(s.downloads[modelId])) {
                    s to true
                } else {
                    val queued = DownloadState(DownloadState.Status.QUEUED, 0, 0, null)
                    s.copy(downloads = s.downloads + (modelId to queued)) to false
                }
            }
            if (alreadyActive) {
                if (!isWatching(modelId)) watch(modelId, null)
                return@launch
            }
            val requestId = try {
                Models.enqueueDownload(app, modelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Id no válido o WorkManager sin iniciar: la fila muestra un fallo con mensaje fijo.
                setDownload(modelId, DownloadState(DownloadState.Status.FAILED, 0, 0, null))
                return@launch
            }
            // En cola al instante (la fila deja de ofrecer "Descargar"); el colector trae lo demás.
            setDownload(modelId, DownloadState(DownloadState.Status.QUEUED, 0, 0, null, requestId))
            watch(modelId, requestId)
        }
    }

    override fun cancel(modelId: String) {
        runCatching { Models.cancelDownload(app, modelId) }
    }

    override suspend fun delete(engine: EngineId, pair: String): ModelMessage? {
        val problem = try {
            withContext(io) { Models.deleteModel(app, engine.wire, pair) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ModelActions.classify(e)
        }
        reload()
        return problem ?: ModelMessage.DELETE_OK
    }

    override suspend fun import(uri: Uri): ModelMessage {
        val problem = try {
            withContext(io) {
                if (runCatching { Models.catalogRepository(app).current() }.getOrNull() == null) {
                    ModelMessage.NO_CATALOG_IMPORT
                } else {
                    val input = app.contentResolver.openInputStream(uri) ?: throw IOException()
                    input.use { Models.importModel(app, it) }
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ModelActions.classifyImport(e)
        }
        reload()
        return problem ?: ModelMessage.IMPORT_OK
    }

    /**
     * Cambia [inputs] con [transform] y recalcula [pairs] en el mismo paso, con el candado y fuera del hilo
     * principal. [transform] devuelve los datos nuevos y un resultado para quien llama.
     */
    private suspend fun <T> withState(transform: (Inputs) -> Pair<Inputs, T>): T = withContext(io) {
        stateMutex.withLock {
            val (next, result) = transform(inputs)
            if (next != inputs) {
                inputs = next
                _pairs.value = HubRules.pairStatuses(next.catalog, next.installed, next.downloads)
            }
            result
        }
    }

    /**
     * Relee el catálogo guardado y lo instalado con el candado (las relecturas no se cruzan). Devuelve el
     * catálogo leído (null si no hay); si esa lectura falla, [pairs] conserva el catálogo anterior.
     */
    private suspend fun reload(): Catalog? = withState { s ->
        val c = runCatching { Models.catalogRepository(app).current() }.getOrNull()
        val installed = runCatching { Models.store(app).installed() }.getOrDefault(emptyList())
            .mapTo(mutableSetOf()) { it.engine to it.pair }
        s.copy(catalog = c ?: s.catalog, installed = installed) to c
    }

    /** Al arrancar: vuelve a seguir las descargas que siguen en cola o en curso (p. ej. tras cerrar la app). */
    private suspend fun resumeActiveDownloads() {
        val models = withState { s -> s to s.catalog?.models.orEmpty() }
        for (m in models) {
            if (runCatching { Models.isDownloading(app, m.id) }.getOrDefault(false)) watch(m.id, null)
        }
    }

    private suspend fun setDownload(modelId: String, state: DownloadState?) =
        withState { s -> s.copy(downloads = s.downloads + (modelId to state)) to Unit }

    private fun isWatching(modelId: String): Boolean = synchronized(watchers) { watchers[modelId]?.isActive == true }

    /**
     * Sigue la descarga de [modelId] hasta un estado final (por modelo, no por id de petición: con KEEP el
     * id devuelto puede no ser el que corre). Los estados viejos se ignoran como en la pantalla de prueba.
     */
    private fun watch(modelId: String, requestId: UUID?): Unit = synchronized(watchers) {
        watchers[modelId]?.cancel()
        // LAZY: se registra antes de arrancar, así al terminar se quita del mapa solo si sigue siendo el suyo.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Sin requestId (retomada al abrir la app) no hay estados viejos que ignorar.
            var seenActive = requestId == null
            runCatching {
                Models.downloadInfo(app, modelId).first { info ->
                    if (!ModelActions.acceptDownloadState(info, requestId, seenActive)) return@first false
                    if (ModelActions.isActive(info)) seenActive = true
                    setDownload(modelId, info)
                    val done = info != null && !ModelActions.isActive(info)
                    if (info?.status == DownloadState.Status.SUCCEEDED) reload()
                    done
                }
            }.onFailure {
                if (it is CancellationException) throw it
                // No se pudo seguir la descarga (id no válido, WorkManager): la fila no se queda "en cola".
                setDownload(modelId, DownloadState(DownloadState.Status.FAILED, 0, 0, null))
            }
        }
        watchers[modelId] = job
        job.invokeOnCompletion {
            synchronized(watchers) { if (watchers[modelId] === job) watchers.remove(modelId) }
        }
        job.start()
    }
}
