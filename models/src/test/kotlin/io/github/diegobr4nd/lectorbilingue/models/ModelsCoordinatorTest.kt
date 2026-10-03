package io.github.diegobr4nd.lectorbilingue.models

import androidx.work.WorkInfo
import androidx.work.workDataOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelsCoordinatorTest {

    private val model = CatalogModel(
        "opus-en-es-1", "en-es", "opus", "1.0", "CC-BY-4.0", "Helsinki-NLP",
        listOf(ModelFile("model.bin", 10, "0".repeat(64), "https://example.invalid/model.bin")),
    )
    private val catalog = Catalog(1, Instant.parse("2026-10-01T00:00:00Z"), listOf(model))
    private val installed = InstalledModel(model.id, model.pair, model.engine, model.modelVersion, listOf("model.bin"))

    private fun coordinator() = ModelsCoordinator(io = Dispatchers.Unconfined)

    private fun job(c: ModelsCoordinator, recover: () -> Unit, onDownload: () -> Unit = {}) = c.downloadJob(
        currentCatalog = { catalog },
        refreshCatalog = { error("no") },
        download = { _, _ -> onDownload(); File("x") },
        install = { _, _ -> installed },
        recover = recover,
    )

    // ---------------------------------------------------------------- un solo candado

    @Test
    fun elMismoCandadoBloqueaDescargaImportacionYBorrado() = runTest {
        val c = coordinator()
        val events = mutableListOf<String>()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch { c.withLock { held.complete(Unit); release.await() } }
        held.await()

        val download = async { job(c, recover = {}, onDownload = { events += "download" }).run(model.id, 0) { _, _, _ -> } }
        val import = async { c.import(anyDownloadActive = { false }, recover = {}) { events += "import"; installed } }
        val delete = async { c.delete { events += "delete" } }
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), events, "nadie entra mientras otro tiene el candado")

        release.complete(Unit)
        holder.join()
        assertIs<DownloadOutcome.Success>(download.await())
        import.await()
        delete.await()
        assertEquals(setOf("download", "import", "delete"), events.toSet())
        assertFalse(c.lock.isLocked)
    }

    // ---------------------------------------------------------------- recuperación una vez

    @Test
    fun laRecuperacionCorreUnaSolaVezEntreWorkerImportacionYPantalla() = runTest {
        val c = coordinator()
        var runs = 0
        val recover = { runs++; Unit }
        job(c, recover).run(model.id, 0) { _, _, _ -> }
        c.import(anyDownloadActive = { false }, recover = recover) { installed }
        c.recover(recover)
        job(c, recover).run(model.id, 0) { _, _, _ -> }
        assertEquals(1, runs)
    }

    @Test
    fun recoverYaHechaNoEsperaAlCandado() = runTest {
        val c = coordinator()
        c.recover {}
        val release = CompletableDeferred<Unit>()
        val holder = launch { c.withLock { release.await() } }
        testScheduler.advanceUntilIdle()
        assertTrue(c.lock.isLocked)
        c.recover { error("no debe correr otra vez") } // vuelve en el acto aunque haya una descarga con el candado
        release.complete(Unit)
        holder.join()
    }

    // ---------------------------------------------------------------- importación con descargas activas

    @Test
    fun importarSeNiegaSiHayUnaDescargaActiva() = runTest {
        val c = coordinator()
        var ran = false
        assertFailsWith<DownloadInProgressException> {
            c.import(anyDownloadActive = { true }, recover = { ran = true }) { ran = true; installed }
        }
        assertFalse(ran)
        assertFalse(c.lock.isLocked)
    }

    @Test
    fun importarSinDescargasRecuperaYLuegoImporta() = runTest {
        val c = coordinator()
        val events = mutableListOf<String>()
        val result = c.import(anyDownloadActive = { false }, recover = { events += "recover" }) {
            events += "import(locked=${c.lock.isLocked})"
            installed
        }
        assertEquals(installed, result)
        assertEquals(listOf("recover", "import(locked=true)"), events)
    }

    // ---------------------------------------------------------------- DownloadState

    @Test
    fun downloadState_estados() {
        val empty = workDataOf()
        val cases = mapOf(
            WorkInfo.State.ENQUEUED to DownloadState.Status.QUEUED,
            WorkInfo.State.BLOCKED to DownloadState.Status.QUEUED,
            WorkInfo.State.RUNNING to DownloadState.Status.RUNNING,
            WorkInfo.State.SUCCEEDED to DownloadState.Status.SUCCEEDED,
            WorkInfo.State.FAILED to DownloadState.Status.FAILED,
            WorkInfo.State.CANCELLED to DownloadState.Status.CANCELLED,
        )
        for ((state, status) in cases) assertEquals(status, DownloadState.from(state, empty, empty).status, state.name)
    }

    @Test
    fun downloadState_progresoEnCurso() {
        val s = DownloadState.from(WorkInfo.State.RUNNING, workDataOf("bytes" to 40L, "total" to 100L), workDataOf())
        assertEquals(DownloadState(DownloadState.Status.RUNNING, 40L, 100L, null), s)
    }

    @Test
    fun downloadState_errorSoloConCodigoConocido() {
        val ok = DownloadState.from(WorkInfo.State.FAILED, workDataOf(), workDataOf("error" to "integridad"))
        assertEquals("integridad", ok.error)
        val raro = DownloadState.from(WorkInfo.State.FAILED, workDataOf(), workDataOf("error" to "texto /con/ruta"))
        assertEquals("desconocido", raro.error)
        val sinCodigo = DownloadState.from(WorkInfo.State.FAILED, workDataOf(), workDataOf())
        assertEquals("desconocido", sinCodigo.error)
        val exito = DownloadState.from(WorkInfo.State.SUCCEEDED, workDataOf(), workDataOf("error" to "integridad"))
        assertEquals(null, exito.error)
    }
}
