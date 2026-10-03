package io.github.diegobr4nd.lectorbilingue.models

import androidx.work.Data
import androidx.work.WorkInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Las reglas de serialización de [Models], sin `Context`, para poder probarlas:
 *
 * - un solo [lock] para descarga (worker), importación, borrado y recuperación;
 * - la recuperación del arranque corre una sola vez por instancia (= por proceso, en [Models]);
 * - importar se niega con [DownloadInProgressException] si hay una descarga activa, sin tomar el candado.
 *
 * El candado NO es reentrante: no llamar a [withLock] desde dentro de otro [withLock].
 */
internal class ModelsCoordinator(private val io: CoroutineDispatcher = Dispatchers.IO) {

    val lock = Mutex()

    @Volatile
    private var recovered = false

    suspend fun <T> withLock(block: suspend () -> T): T = lock.withLock { block() }

    /** Recuperación del arranque. Si ya se hizo, vuelve en el acto (sin esperar al candado). */
    suspend fun recover(recover: () -> Unit) {
        if (recovered) return
        withLock { withContext(io) { recoverOnceLocked(recover) } }
    }

    /** Importa con [block] tras recuperar, con el candado; antes se niega si [anyDownloadActive]. */
    suspend fun import(
        anyDownloadActive: suspend () -> Boolean,
        recover: () -> Unit,
        block: () -> InstalledModel,
    ): InstalledModel {
        if (anyDownloadActive()) throw DownloadInProgressException()
        return withLock {
            withContext(io) {
                recoverOnceLocked(recover)
                block()
            }
        }
    }

    /** Borra con [block] con el candado (nunca a mitad de una instalación). */
    suspend fun delete(block: () -> Unit) {
        withLock { withContext(io) { block() } }
    }

    /** El trabajo del worker con este candado; la recuperación también corre una sola vez. */
    fun downloadJob(
        currentCatalog: () -> Catalog?,
        refreshCatalog: () -> Catalog,
        download: (CatalogModel, (Long, Long) -> Unit) -> File,
        install: (CatalogModel, File) -> InstalledModel,
        recover: () -> Unit,
    ) = ModelDownloadJob(
        currentCatalog = currentCatalog,
        refreshCatalog = refreshCatalog,
        download = download,
        install = install,
        lock = lock,
        beforeWork = { recoverOnceLocked(recover) },
        io = io,
    )

    /** Solo con el candado tomado. Si [recover] lanza, se reintenta la próxima vez. */
    private fun recoverOnceLocked(recover: () -> Unit) {
        if (recovered) return
        recover()
        recovered = true
    }
}

/**
 * Estado de una descarga para la interfaz, leído de un `WorkInfo`. [error] solo en [Status.FAILED] y
 * siempre uno de [DownloadOutcome.CODES] (un valor desconocido se vuelve `desconocido`): nunca texto libre.
 */
data class DownloadState(
    val status: Status,
    val bytes: Long,
    val total: Long,
    val error: String?,
    /** Id de la petición de WorkManager a la que pertenece este estado (null si no se conoce). */
    val workId: java.util.UUID? = null,
) {

    enum class Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    companion object {
        fun from(info: WorkInfo): DownloadState = from(info.state, info.progress, info.outputData, info.id)

        internal fun from(state: WorkInfo.State, progress: Data, output: Data, id: java.util.UUID? = null): DownloadState {
            val status = when (state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> Status.QUEUED
                WorkInfo.State.RUNNING -> Status.RUNNING
                WorkInfo.State.SUCCEEDED -> Status.SUCCEEDED
                WorkInfo.State.FAILED -> Status.FAILED
                WorkInfo.State.CANCELLED -> Status.CANCELLED
            }
            val error = if (status == Status.FAILED) {
                output.getString(DownloadWork.KEY_ERROR)?.takeIf { it in DownloadOutcome.CODES } ?: DownloadOutcome.DESCONOCIDO
            } else {
                null
            }
            return DownloadState(
                status,
                progress.getLong(DownloadWork.KEY_BYTES, 0L),
                progress.getLong(DownloadWork.KEY_TOTAL, 0L),
                error,
                id,
            )
        }
    }
}
