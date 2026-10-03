package io.github.diegobr4nd.lectorbilingue.models

import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelDownloadJobTest {

    private val modelA = CatalogModel(
        "opus-en-es-1", "en-es", "opus", "1.0", "CC-BY-4.0", "Helsinki-NLP",
        listOf(ModelFile("model.bin", 1000, "0".repeat(64), "https://example.invalid/model.bin")),
    )
    private val modelB = modelA.copy(id = "opus-es-en-1", pair = "es-en")
    private val catalogA = Catalog(1, Instant.parse("2026-10-01T00:00:00Z"), listOf(modelA))
    private val catalogB = Catalog(1, Instant.parse("2026-10-02T00:00:00Z"), listOf(modelB))
    private val staging = File("staging-no-se-usa")

    /** Dobles de prueba configurables; registran lo que pasó. */
    private inner class Fakes {
        var currentResults = ArrayDeque<Catalog?>()
        var currentDefault: Catalog? = catalogA
        var refresh: () -> Catalog = { error("refresh no esperado") }
        var download: (CatalogModel, (Long, Long) -> Unit) -> File = { m, p ->
            p(0, m.totalSize)
            p(m.totalSize, m.totalSize)
            staging
        }
        var install: (CatalogModel, File) -> InstalledModel = { m, _ ->
            InstalledModel(m.id, m.pair, m.engine, m.modelVersion, m.files.map { it.name })
        }
        val lock = Mutex()
        val events = mutableListOf<String>()
        val progress = mutableListOf<Triple<String, Long, Long>>()
        var now = 0L

        fun job() = ModelDownloadJob(
            currentCatalog = { events += "current"; if (currentResults.isEmpty()) currentDefault else currentResults.removeFirst() },
            refreshCatalog = { events += "refresh"; refresh() },
            download = { m, p -> events += "download(locked=${lock.isLocked})"; download(m, p) },
            install = { m, s -> events += "install(locked=${lock.isLocked})"; install(m, s) },
            lock = lock,
            beforeWork = { events += "beforeWork(locked=${lock.isLocked})" },
            io = Dispatchers.Unconfined,
            nanoClock = { now },
        )

        suspend fun run(id: String? = "opus-en-es-1", attempt: Int = 0) =
            job().run(id, attempt) { pair, n, t -> progress += Triple(pair, n, t) }
    }

    private fun failureCode(o: DownloadOutcome): String = assertIs<DownloadOutcome.Failure>(o).code

    // ---------------------------------------------------------------- camino feliz y catálogo

    @Test
    fun run_conCatalogoActualDescargaEInstalaSinRefrescar() = runTest {
        val f = Fakes()
        val outcome = f.run()
        val ok = assertIs<DownloadOutcome.Success>(outcome)
        assertEquals("en-es", ok.installed.pair)
        assertFalse("refresh" in f.events)
        assertEquals(
            listOf("current", "beforeWork(locked=true)", "download(locked=true)", "install(locked=true)"),
            f.events,
        )
        assertFalse(f.lock.isLocked, "el candado se suelta al terminar")
        assertEquals(Triple("en-es", 1000L, 1000L), f.progress.last())
    }

    @Test
    fun run_sinCatalogoActualRefresca() = runTest {
        val f = Fakes()
        f.currentDefault = null
        f.refresh = { catalogA }
        assertIs<DownloadOutcome.Success>(f.run())
        assertTrue("refresh" in f.events)
    }

    @Test
    fun run_idQueNoEstaEnElActualRefresca() = runTest {
        val f = Fakes()
        f.currentDefault = catalogB
        f.refresh = { catalogA }
        assertIs<DownloadOutcome.Success>(f.run())
    }

    @Test
    fun run_idQueNoEstaEnNingunCatalogoFallaConCatalogo() = runTest {
        val f = Fakes()
        f.currentDefault = catalogB
        f.refresh = { catalogB }
        assertEquals("catalogo", failureCode(f.run()))
        assertFalse(f.events.any { it.startsWith("download") })
    }

    @Test
    fun run_catalogoMasAntiguoAlRefrescarUsaElActualSiTieneElModelo() = runTest {
        val f = Fakes()
        // El primer current() no lo tiene; mientras, otro refresh guardó uno que sí lo tiene.
        f.currentResults = ArrayDeque(listOf(null, catalogA))
        f.refresh = { throw CatalogException("catálogo más antiguo") }
        assertIs<DownloadOutcome.Success>(f.run())
    }

    @Test
    fun run_catalogoMasAntiguoSinActualUtilFallaConCatalogo() = runTest {
        val f = Fakes()
        f.currentDefault = catalogB
        f.refresh = { throw CatalogException("catálogo más antiguo") }
        assertEquals("catalogo", failureCode(f.run()))
    }

    @Test
    fun run_idAusenteOInvalidoFallaSinTocarNada() = runTest {
        for (bad in listOf(null, "", "../x", "Opus", "a/b", "x".repeat(65), "a..b")) {
            val f = Fakes()
            assertEquals("catalogo", failureCode(f.run(bad)), "id: $bad")
            assertEquals(emptyList(), f.events, "id: $bad")
        }
    }

    // ---------------------------------------------------------------- errores → códigos

    @Test
    fun run_integridadFirmaPoliticaArchivos() = runTest {
        val cases = listOf<Pair<Exception, String>>(
            IntegrityException("el archivo model.bin no coincide con el catálogo") to "integridad",
            NetworkPolicyException("host no permitido: evil.example") to "politica",
            ModelFileException("error de archivos al descargar el modelo") to "archivos",
            CatalogException("catálogo inválido: models[0]") to "catalogo",
            IllegalArgumentException("nombre no válido") to "catalogo",
            IllegalStateException("raro") to "desconocido",
        )
        for ((e, code) in cases) {
            val f = Fakes()
            f.download = { _, _ -> throw e }
            assertEquals(code, failureCode(f.run()), e.javaClass.simpleName)
            assertFalse(f.lock.isLocked)
        }
    }

    @Test
    fun run_errorDeArchivosAlInstalarEsArchivos() = runTest {
        val f = Fakes()
        f.install = { _, _ -> throw ModelFileException("error de archivos al instalar el modelo") }
        assertEquals("archivos", failureCode(f.run()))
    }

    @Test
    fun run_firmaYPoliticaAlRefrescar() = runTest {
        for ((e, code) in listOf(SignatureException("firma no válida") to "firma", NetworkPolicyException("x") to "politica")) {
            val f = Fakes()
            f.currentDefault = null
            f.refresh = { throw e }
            assertEquals(code, failureCode(f.run()))
        }
    }

    @Test
    fun run_errorDeRedReintentaHastaElQuintoIntento() = runTest {
        for (attempt in 0..3) {
            val f = Fakes()
            f.download = { _, _ -> throw IOException("HTTP 503 desde github.com") }
            assertEquals(DownloadOutcome.Retry, f.run(attempt = attempt), "intento $attempt")
        }
        val last = Fakes()
        last.download = { _, _ -> throw IOException("respuesta incompleta desde github.com") }
        assertEquals("red", failureCode(last.run(attempt = 4)))
    }

    @Test
    fun run_errorDeRedAlRefrescarTambienReintenta() = runTest {
        val f = Fakes()
        f.currentDefault = null
        f.refresh = { throw IOException("HTTP 500 desde github.com") }
        assertEquals(DownloadOutcome.Retry, f.run())
    }

    @Test
    fun run_losCodigosNuncaLlevanElMensaje() = runTest {
        val f = Fakes()
        f.download = { _, _ -> throw IntegrityException("SECRETO /data/user/0/x") }
        val code = failureCode(f.run())
        assertTrue(code in DownloadOutcome.CODES)
        assertFalse(code.contains("SECRETO"))
    }

    // ---------------------------------------------------------------- candado, progreso, cancelación

    @Test
    fun run_esperaAlCandadoAntesDeDescargar() = runTest {
        val f = Fakes()
        f.lock.lock()
        val result = async { f.run() }
        testScheduler.advanceUntilIdle()
        assertFalse(f.events.any { it.startsWith("download") }, "no descarga mientras otro tiene el candado")
        f.lock.unlock()
        assertIs<DownloadOutcome.Success>(result.await())
    }

    @Test
    fun run_progresoLimitadoACadaIntervaloYSiempreElFinal() = runTest {
        val f = Fakes()
        f.download = { m, p ->
            for (i in 0..100) {
                f.now = i * 50_000_000L // 50 ms por paso
                p(i * 10L, m.totalSize)
            }
            staging
        }
        f.run()
        // 0 ms, 250, 500, … 5000 ms → 21 avisos; el último es el total.
        assertEquals(21, f.progress.size)
        assertEquals(Triple("en-es", 1000L, 1000L), f.progress.last())
        assertEquals(f.progress.map { it.second }.sorted(), f.progress.map { it.second })
    }

    @Test
    fun run_cancelarCortaLaDescargaYSueltaElCandado() = runTest {
        val f = Fakes()
        val started = CountDownLatch(1)
        var sawCancel = false
        f.download = { m, p ->
            started.countDown()
            try {
                while (true) {
                    p(1, m.totalSize)
                    Thread.sleep(1)
                }
                @Suppress("UNREACHABLE_CODE")
                staging
            } catch (e: CancellationException) {
                sawCancel = true
                throw e
            }
        }
        val job = ModelDownloadJob(
            currentCatalog = { catalogA },
            refreshCatalog = { error("no") },
            download = f.download,
            install = f.install,
            lock = f.lock,
            beforeWork = {},
            io = Dispatchers.IO,
        )
        val running = launch(Dispatchers.Default) { job.run("opus-en-es-1", 0) { _, _, _ -> } }
        withContext(Dispatchers.IO) { assertTrue(started.await(10, TimeUnit.SECONDS)) }
        running.cancelAndJoin()
        assertTrue(sawCancel)
        assertFalse(f.lock.isLocked)
    }

    // ---------------------------------------------------------------- utilidades de WorkManager

    @Test
    fun downloadWork_estadosActivos() {
        val active = WorkInfo.State.entries.filter { DownloadWork.isActive(it) }.toSet()
        assertEquals(setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED), active)
    }

    @Test
    fun downloadWork_nombreUnicoPorModelo() {
        assertEquals("model-download-opus-en-es-1", DownloadWork.uniqueName("opus-en-es-1"))
    }

    @Test
    fun downloadWork_validaElId() {
        assertTrue(DownloadWork.isValidModelId("opus-en-es-1"))
        for (bad in listOf("", "-a", "a..b", "A", "a/b", "x".repeat(65))) assertFalse(DownloadWork.isValidModelId(bad), bad)
    }
}
