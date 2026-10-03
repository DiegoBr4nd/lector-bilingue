package io.github.diegobr4nd.lectorbilingue.models

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.UUID

/** Se pidió importar un modelo mientras hay una descarga de modelo en cola o en curso: hay que esperar o cancelarla. */
class DownloadInProgressException : Exception("hay una descarga de modelo en curso")

/**
 * Fábrica que arma las piezas del gestor de modelos con un [Context] (singleton: una "única instancia"
 * por proceso, como un solo mostrador para todos los clientes).
 *
 * Rutas: catálogo en `filesDir/catalog/`, modelos en `filesDir/models/`.
 *
 * **Serialización.** Descargar e importar nunca corren a la vez en el proceso: el importador borra
 * `.tmp/<id>`, que una descarga en curso está usando. Por eso:
 * - el trabajo de [DownloadWorker] (recuperación + descarga + instalación) y [importModel] / [deleteModel]
 *   / [recover] corren dentro de [withModelsLock] (un `Mutex` de corrutinas: un candado que hace
 *   esperar sin bloquear el hilo);
 * - además [importModel] se niega con [DownloadInProgressException] si hay una descarga en cola o en curso
 *   ([isAnyDownloadActive]), para que la interfaz diga "espera o cancela la descarga" en vez de quedarse
 *   esperando el candado durante toda una descarga.
 * El candado NO es reentrante: no llamar a [withModelsLock] desde dentro de otro [withModelsLock].
 *
 * **Recuperación.** [ModelStore.recover] corre una vez por proceso, con el candado, antes del primer uso
 * (lo hacen el worker y [importModel]); la interfaz puede llamar antes a [recover]. Las reglas viven en
 * [ModelsCoordinator] (probadas sin Android).
 */
object Models {
    private const val CATALOG_DIR = "catalog"
    private const val MODELS_DIR = "models"
    private const val ASSET_CATALOG = "catalog/catalog.json"
    private const val ASSET_SIGNATURE = "catalog/catalog.json.minisig"
    private val BACKOFF: Duration = Duration.ofSeconds(30)

    private val coordinator = ModelsCoordinator()

    @Volatile
    private var repository: CatalogRepository? = null

    /** Ejecuta [block] con el candado de modelos (ver la nota de serialización). No reentrante. */
    suspend fun <T> withModelsLock(block: suspend () -> T): T = coordinator.withLock(block)

    /** Único por proceso: su candado interno protege el catálogo guardado. */
    fun catalogRepository(context: Context): CatalogRepository {
        repository?.let { return it }
        synchronized(this) {
            repository?.let { return it }
            val app = context.applicationContext
            return CatalogRepository(
                dir = File(app.filesDir, CATALOG_DIR),
                verifier = MinisignVerifier(TrustedKeys.production),
                fetcher = HttpFetcher(),
                bundled = { bundledCatalog(app) },
            ).also { repository = it }
        }
    }

    fun store(context: Context): ModelStore = ModelStore(modelsDir(context))

    /**
     * Encola la descarga de [modelId] con WorkManager: trabajo único `model-download-<id>` (política KEEP:
     * si ya hay una en cola o en curso, se conserva esa), con red y almacenamiento no bajo, y reintentos
     * con espera exponencial. Devuelve el id de la petición creada; con KEEP, si ya existía otra, ese id
     * no es el que corre: para seguir el progreso usar [downloadInfo] (por modelo, no por id).
     */
    fun enqueueDownload(context: Context, modelId: String): UUID {
        require(DownloadWork.isValidModelId(modelId)) { "id de modelo no válido" }
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWork.KEY_MODEL_ID to modelId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF)
            .addTag(DownloadWork.TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DownloadWork.uniqueName(modelId), ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    /** Cancela la descarga de [modelId] (el `.part` se conserva para reanudar después). */
    fun cancelDownload(context: Context, modelId: String) {
        require(DownloadWork.isValidModelId(modelId)) { "id de modelo no válido" }
        WorkManager.getInstance(context).cancelUniqueWork(DownloadWork.uniqueName(modelId))
    }

    /** Estado de la descarga de [modelId] (null si nunca se pidió). Ver [DownloadState]. */
    fun downloadInfo(context: Context, modelId: String): Flow<DownloadState?> {
        require(DownloadWork.isValidModelId(modelId)) { "id de modelo no válido" }
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(DownloadWork.uniqueName(modelId))
            .map { infos -> infos.lastOrNull()?.let(DownloadState::from) }
    }

    /** ¿Hay una descarga de [modelId] en cola o en curso? */
    suspend fun isDownloading(context: Context, modelId: String): Boolean {
        require(DownloadWork.isValidModelId(modelId)) { "id de modelo no válido" }
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(DownloadWork.uniqueName(modelId))
            .first()
            .any { DownloadWork.isActive(it.state) }
    }

    /** ¿Hay alguna descarga de modelo en cola o en curso? */
    suspend fun isAnyDownloadActive(context: Context): Boolean =
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(DownloadWork.TAG)
            .first()
            .any { DownloadWork.isActive(it.state) }

    /**
     * Importa un modelo desde un zip ([ModelImporter]) con el catálogo vigente.
     * Lanza [DownloadInProgressException] si hay una descarga de modelo en cola o en curso (el modelo
     * del zip solo se conoce tras leerlo, así que se mira cualquier descarga), y [CatalogException] si
     * no hay catálogo. [zip] lo cierra quien llama.
     */
    suspend fun importModel(context: Context, zip: InputStream): InstalledModel {
        val app = context.applicationContext
        return coordinator.import(anyDownloadActive = { isAnyDownloadActive(app) }, recover = { recoverStore(app) }) {
            val catalog = catalogRepository(app).current() ?: throw CatalogException("no hay catálogo de modelos")
            val dir = modelsDir(app)
            ModelImporter(dir, ModelInstaller(dir)).import(zip, catalog)
        }
    }

    /** Borra el modelo instalado de [pair] con el candado tomado (nunca a mitad de una instalación). */
    suspend fun deleteModel(context: Context, pair: String) {
        val app = context.applicationContext
        coordinator.delete { store(app).delete(pair) }
    }

    /** Recuperación del arranque (una vez por proceso). Ver [ModelStore.recover]. */
    suspend fun recover(context: Context) {
        val app = context.applicationContext
        coordinator.recover { recoverStore(app) }
    }

    /** El trabajo del worker con las piezas reales. */
    internal fun downloadJob(context: Context): ModelDownloadJob {
        val app = context.applicationContext
        val repo = catalogRepository(app)
        val dir = modelsDir(app)
        val downloader = ModelDownloader(dir, HttpFetcher())
        val installer = ModelInstaller(dir)
        return coordinator.downloadJob(
            currentCatalog = repo::current,
            refreshCatalog = repo::refresh,
            download = downloader::download,
            install = installer::install,
            recover = { recoverStore(app) },
        )
    }

    /** Solo con el candado tomado (lo garantiza [coordinator]). */
    private fun recoverStore(app: Context) {
        val ids = catalogRepository(app).current()?.models?.map { it.id }?.toSet()
        store(app).recover(ids)
    }

    private fun modelsDir(context: Context) = File(context.applicationContext.filesDir, MODELS_DIR)

    /** Catálogo incrustado en el APK (`assets/catalog/`), o null si no está (la Tarea 12 lo añade). */
    private fun bundledCatalog(context: Context): Pair<ByteArray, String>? = try {
        val bytes = readAsset(context, ASSET_CATALOG, CatalogParser.MAX_BYTES)
        val signature = readAsset(context, ASSET_SIGNATURE, CatalogRepository.MAX_SIGNATURE_BYTES)
        if (bytes == null || signature == null) null else bytes to String(signature, Charsets.UTF_8)
    } catch (e: IOException) {
        null
    }

    /** Lee un asset entero si mide como mucho [max] bytes; si no existe o es mayor, null. */
    private fun readAsset(context: Context, name: String, max: Int): ByteArray? = try {
        context.assets.open(name).use { input ->
            // Sin readNBytes (API 33): se lee a mano hasta max + 1 bytes.
            val buf = ByteArray(max + 1)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n > max) null else buf.copyOf(n)
        }
    } catch (e: java.io.FileNotFoundException) {
        null
    }
}
