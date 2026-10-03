package io.github.diegobr4nd.lectorbilingue.models

import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Cómo terminó un intento de descarga. [Failure.code] es uno de [CODES]: nunca texto de una excepción. */
internal sealed interface DownloadOutcome {
    data class Success(val installed: InstalledModel) : DownloadOutcome
    data object Retry : DownloadOutcome
    data class Failure(val code: String) : DownloadOutcome

    companion object {
        const val INTEGRIDAD = "integridad"
        const val FIRMA = "firma"
        const val CATALOGO = "catalogo"
        const val POLITICA = "politica"
        const val ARCHIVOS = "archivos"
        const val RED = "red"
        const val DESCONOCIDO = "desconocido"
        val CODES = setOf(INTEGRIDAD, FIRMA, CATALOGO, POLITICA, ARCHIVOS, RED, DESCONOCIDO)
    }
}

/** Nombres y reglas compartidas entre [Models] y [DownloadWorker] (sin `Context`, para poder probarlas). */
internal object DownloadWork {
    const val KEY_MODEL_ID = "modelId"
    const val KEY_BYTES = "bytes"
    const val KEY_TOTAL = "total"
    const val KEY_ERROR = "error"
    const val KEY_PAIR = "pair"
    const val TAG = "model-download"

    /** Intentos totales ante errores de red (el primero + 4 reintentos). */
    const val MAX_ATTEMPTS = 5

    private val MODEL_ID = Regex("^[a-z0-9][a-z0-9.-]{0,63}$")

    fun uniqueName(modelId: String) = "model-download-$modelId"

    /** Mismas reglas que el `id` del catálogo. */
    fun isValidModelId(id: String) = MODEL_ID.matches(id) && !id.contains("..")

    /** Una descarga "en curso" para la interfaz: esperando red, encadenada o corriendo. */
    fun isActive(state: WorkInfo.State) =
        state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING || state == WorkInfo.State.BLOCKED
}

/**
 * El trabajo real de [DownloadWorker], sin Android, para poder probarlo:
 *
 * 1. Intenta SIEMPRE `refreshCatalog()` primero, sin que su fallo detenga nada (el APK trae un catálogo
 *    firmado incrustado, así que `currentCatalog()` casi nunca es null; sin este refresco no llegarían
 *    nunca catálogos nuevos ni la rotación de llaves). Después busca [modelId] en `currentCatalog()`
 *    (tras un refresco válido es el más nuevo; si el refresco falló —sin red, firma mala, "catálogo más
 *    antiguo"— es el que ya había). Si ahí no está el modelo y el refresco había fallado, se clasifica
 *    el error del refresco (p. ej. red → reintento); si el refresco funcionó, `Failure("catalogo")`.
 *    Cancelar durante el refresco corta todo.
 * 2. Con el [lock] tomado (el mismo de [Models.withModelsLock], así nunca coincide con una importación):
 *    `beforeWork()` (recuperación del arranque), descarga e instala.
 * 3. Traduce cada excepción a un [DownloadOutcome] (ver [classify]). Errores de red → [DownloadOutcome.Retry]
 *    hasta el intento [DownloadWork.MAX_ATTEMPTS]; después, `Failure("red")`.
 *
 * Progreso: `onProgress(pair, bajado, total)` como mucho cada [minProgressIntervalNanos] (más el primero
 * y el final), para no saturar WorkManager ni la notificación.
 * Cancelar: si la corrutina se cancela, el siguiente aviso de progreso lanza [CancellationException],
 * que corta la descarga dejando el `.part` para reanudar.
 */
internal class ModelDownloadJob(
    private val currentCatalog: () -> Catalog?,
    private val refreshCatalog: () -> Catalog,
    private val download: (CatalogModel, (Long, Long) -> Unit) -> File,
    private val install: (CatalogModel, File) -> InstalledModel,
    private val lock: Mutex,
    private val beforeWork: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nanoClock: () -> Long = System::nanoTime,
    private val minProgressIntervalNanos: Long = 250_000_000L,
) {

    /** [runAttempt] empieza en 0 (como `runAttemptCount` de WorkManager). */
    suspend fun run(
        modelId: String?,
        runAttempt: Int,
        onProgress: (pair: String, downloaded: Long, total: Long) -> Unit,
    ): DownloadOutcome {
        if (modelId == null || !DownloadWork.isValidModelId(modelId)) return DownloadOutcome.Failure(DownloadOutcome.CATALOGO)
        return try {
            withContext(io) {
                val model = findModel(modelId) ?: return@withContext DownloadOutcome.Failure(DownloadOutcome.CATALOGO)
                val ctx = currentCoroutineContext()
                val throttle = Throttle { n, t -> onProgress(model.pair, n, t) }
                lock.withLock {
                    beforeWork()
                    val staging = download(model) { n, t ->
                        ctx.ensureActive()
                        throttle.offer(n, t)
                    }
                    ctx.ensureActive()
                    DownloadOutcome.Success(install(model, staging))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            classify(e, runAttempt)
        }
    }

    private fun findModel(id: String): CatalogModel? {
        var refreshError: Exception? = null
        try {
            refreshCatalog()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            refreshError = e
        }
        currentCatalog()?.models?.firstOrNull { it.id == id }?.let { return it }
        if (refreshError != null) throw refreshError
        return null
    }

    /** Excepción → resultado. Nunca se copia el mensaje: solo un código fijo. */
    private fun classify(e: Exception, runAttempt: Int): DownloadOutcome = when (e) {
        is IntegrityException -> DownloadOutcome.Failure(DownloadOutcome.INTEGRIDAD)
        is SignatureException -> DownloadOutcome.Failure(DownloadOutcome.FIRMA)
        is CatalogException -> DownloadOutcome.Failure(DownloadOutcome.CATALOGO)
        is IllegalArgumentException -> DownloadOutcome.Failure(DownloadOutcome.CATALOGO) // nombres rechazados
        is NetworkPolicyException -> DownloadOutcome.Failure(DownloadOutcome.POLITICA)
        is ModelFileException -> DownloadOutcome.Failure(DownloadOutcome.ARCHIVOS)
        // El resto de IOException viene de HttpFetcher (red): se reintenta con espera exponencial.
        is IOException ->
            if (runAttempt + 1 < DownloadWork.MAX_ATTEMPTS) DownloadOutcome.Retry else DownloadOutcome.Failure(DownloadOutcome.RED)
        else -> DownloadOutcome.Failure(DownloadOutcome.DESCONOCIDO)
    }

    /** Deja pasar el primer aviso, el final y uno cada [minProgressIntervalNanos]. */
    private inner class Throttle(private val sink: (Long, Long) -> Unit) {
        private var lastAt = 0L
        private var lastBytes = -1L

        fun offer(bytes: Long, total: Long) {
            val now = nanoClock()
            val first = lastBytes < 0
            val final = bytes == total && bytes != lastBytes
            if (first || final || (now - lastAt >= minProgressIntervalNanos && bytes != lastBytes)) {
                lastAt = now
                lastBytes = bytes
                sink(bytes, total)
            }
        }
    }
}
