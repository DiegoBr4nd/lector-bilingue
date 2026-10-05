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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lo que las pantallas necesitan del gestor de modelos. Las pantallas dependen de esta interfaz
 * (y no de [ModelHub]) para que las pruebas instrumentadas usen una versión falsa.
 */
interface ModelHubApi {
    /** Pares del catálogo con sus filas; cambia con [refresh], con las descargas, al borrar y al importar. */
    val pairs: StateFlow<List<PairStatus>>

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
    /** Lo que se combina para formar [pairs]. */
    private data class Inputs(
        val catalog: Catalog? = null,
        val installed: Set<Pair<String, String>> = emptySet(),
        val downloads: Map<String, DownloadState?> = emptyMap(),
    )

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val inputs = MutableStateFlow(Inputs())

    override val pairs: StateFlow<List<PairStatus>> = inputs
        .map { HubRules.pairStatuses(it.catalog, it.installed, it.downloads) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

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
            reload()
            resumeActiveDownloads()
        }
    }

    override fun totalRamBytes(): Long = ram

    override suspend fun refresh(): ModelMessage? {
        // El refresco corre en el ámbito del gestor: si vence el tope, sigue en segundo plano y guarda el
        // catálogo nuevo cuando llegue (withTimeoutOrNull no puede cortar una lectura de red bloqueante).
        val refreshError = withTimeoutOrNull(refreshTimeoutMs) { startRefresh().await() }
        return withContext(io) {
            val current = runCatching { Models.catalogRepository(app).current() }.getOrNull()
            reload(current)
            ModelActions.resolveCatalog(current, refreshError).second
        }
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

    /** Relee el catálogo guardado (si no se pasa) y lo instalado, fuera del hilo principal. */
    private suspend fun reload(catalog: Catalog? = null) = withContext(io) {
        val c = catalog ?: runCatching { Models.catalogRepository(app).current() }.getOrNull()
        val installed = runCatching { Models.store(app).installed() }.getOrDefault(emptyList())
            .mapTo(mutableSetOf()) { it.engine to it.pair }
        inputs.update { it.copy(catalog = c ?: it.catalog, installed = installed) }
    }

    /** Al arrancar: vuelve a seguir las descargas que siguen en cola o en curso (p. ej. tras cerrar la app). */
    private suspend fun resumeActiveDownloads() {
        val models = inputs.value.catalog?.models.orEmpty()
        for (m in models) {
            if (runCatching { Models.isDownloading(app, m.id) }.getOrDefault(false)) watch(m.id, null)
        }
    }

    private fun setDownload(modelId: String, state: DownloadState?) =
        inputs.update { it.copy(downloads = it.downloads + (modelId to state)) }

    /**
     * Sigue la descarga de [modelId] hasta un estado final (por modelo, no por id de petición: con KEEP el
     * id devuelto puede no ser el que corre). Los estados viejos se ignoran como en la pantalla de prueba.
     */
    private fun watch(modelId: String, requestId: UUID?) = synchronized(watchers) {
        watchers[modelId]?.cancel()
        watchers[modelId] = scope.launch {
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
    }
}
