package io.github.diegobr4nd.lectorbilingue.models

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var modelsDir: File
    private lateinit var downloader: ModelDownloader

    private val bodyA = bytes(150_000, seed = 1)
    private val bodyB = bytes(70_000, seed = 2)

    /** Solo en pruebas: permite http hacia el servidor local. */
    private fun localPolicy() = HostPolicy { url ->
        url.protocol == "http" &&
            url.host in setOf("localhost", "127.0.0.1") &&
            url.port == server.port &&
            url.userInfo == null
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        modelsDir = tmp.newFolder("models")
        val fetcher = HttpFetcher(policy = localPolicy(), connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
        downloader = ModelDownloader(modelsDir, fetcher)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun bytes(n: Int, seed: Int): ByteArray = ByteArray(n) { ((it * 31 + seed * 7) and 0xFF).toByte() }

    private fun sha(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun file(name: String, body: ByteArray, size: Long = body.size.toLong(), sha256: String = sha(body)) =
        ModelFile(name, size, sha256, server.url("/$name").toString())

    private fun model(vararg files: ModelFile) =
        CatalogModel("opus-en-es-1", "en-es", "opus", "1.0", "CC-BY-4.0", "Helsinki-NLP", files.toList())

    private fun twoFiles() = model(file("model.bin", bodyA), file("vocab.spm", bodyB))

    private fun ok(b: ByteArray) = MockResponse.Builder().code(200).body(Buffer().write(b)).build()

    private fun partial(body: ByteArray, from: Int) =
        MockResponse.Builder().code(206)
            .addHeader("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
            .body(Buffer().write(body.copyOfRange(from, body.size)))
            .build()

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun staging() = File(modelsDir, ".tmp/opus-en-es-1")

    private fun names(dir: File) = dir.list()!!.sorted()

    private class Progress {
        val values = mutableListOf<Pair<Long, Long>>()
        val cb: (Long, Long) -> Unit = { d, t -> values += d to t }
        fun downloaded() = values.map { it.first }
    }

    private fun assertMonotonic(values: List<Long>) {
        for (i in 1 until values.size) {
            assertTrue(values[i] >= values[i - 1], "el progreso retrocedió en la posición $i")
        }
    }

    // ---------------------------------------------------------------- descarga normal

    @Test
    fun download_dosArchivosQuedanVerificadosEnLaCarpetaTemporal() {
        server.enqueue(ok(bodyA))
        server.enqueue(ok(bodyB))
        val dir = downloader.download(twoFiles()) { _, _ -> }
        assertEquals(staging().canonicalFile, dir.canonicalFile)
        assertEquals(listOf("model.bin", "vocab.spm"), names(dir))
        assertContentEquals(bodyA, File(dir, "model.bin").readBytes())
        assertContentEquals(bodyB, File(dir, "vocab.spm").readBytes())
        assertEquals("/model.bin", take().url.encodedPath)
        assertEquals("/vocab.spm", take().url.encodedPath)
    }

    @Test
    fun download_noInstalaNadaEnLaCarpetaDelPar() {
        server.enqueue(ok(bodyA))
        server.enqueue(ok(bodyB))
        downloader.download(twoFiles()) { _, _ -> }
        assertFalse(File(modelsDir, "en-es").exists())
    }

    @Test
    fun download_progresoMonotonoQueTerminaEnElTotal() {
        server.enqueue(ok(bodyA))
        server.enqueue(ok(bodyB))
        val m = twoFiles()
        val p = Progress()
        downloader.download(m, p.cb)
        assertTrue(p.values.isNotEmpty())
        assertTrue(p.values.all { it.second == m.totalSize })
        assertMonotonic(p.downloaded())
        assertEquals(m.totalSize, p.downloaded().last())
    }

    // ---------------------------------------------------------------- reanudación

    @Test
    fun download_reanudaUnPartConRangeYLoCompleta() {
        val half = bodyA.size / 2
        staging().mkdirs()
        File(staging(), "model.bin.part").writeBytes(bodyA.copyOfRange(0, half))
        server.enqueue(partial(bodyA, half))
        server.enqueue(ok(bodyB))
        val m = twoFiles()
        val p = Progress()
        val dir = downloader.download(m, p.cb)
        assertEquals("bytes=$half-", take().headers["Range"])
        assertNull(take().headers["Range"])
        assertContentEquals(bodyA, File(dir, "model.bin").readBytes())
        assertEquals(listOf("model.bin", "vocab.spm"), names(dir))
        assertMonotonic(p.downloaded())
        assertTrue(p.downloaded().first() >= half.toLong(), "lo ya bajado cuenta desde el principio")
        assertEquals(m.totalSize, p.downloaded().last())
    }

    @Test
    fun download_reanudaTrasUnCorteReal() {
        // Primer intento: el servidor corta a mitad del primer archivo.
        server.enqueue(ok(bodyA).newBuilder().onResponseBody(SocketEffect.CloseSocket()).build())
        val m = twoFiles()
        assertFailsWith<IOException> { downloader.download(m) { _, _ -> } }
        take()
        val part = File(staging(), "model.bin.part")
        val have = if (part.isFile) part.length().toInt() else 0
        assertTrue(have < bodyA.size)

        // Segundo intento: se completa desde donde quedó.
        server.enqueue(if (have > 0) partial(bodyA, have) else ok(bodyA))
        server.enqueue(ok(bodyB))
        val dir = downloader.download(m) { _, _ -> }
        val range = take().headers["Range"]
        if (have > 0) assertEquals("bytes=$have-", range) else assertNull(range)
        assertContentEquals(bodyA, File(dir, "model.bin").readBytes())
        assertContentEquals(bodyB, File(dir, "vocab.spm").readBytes())
        assertEquals(listOf("model.bin", "vocab.spm"), names(dir))
    }

    @Test
    fun download_partMayorQueElTamanoSeBorraYEmpiezaDeCero() {
        staging().mkdirs()
        File(staging(), "model.bin.part").writeBytes(bytes(bodyA.size + 10, seed = 9))
        server.enqueue(ok(bodyA))
        server.enqueue(ok(bodyB))
        val dir = downloader.download(twoFiles()) { _, _ -> }
        assertNull(take().headers["Range"])
        assertContentEquals(bodyA, File(dir, "model.bin").readBytes())
    }

    @Test
    fun download_partCompletoYCorrectoNoVuelveABajarse() {
        staging().mkdirs()
        File(staging(), "model.bin.part").writeBytes(bodyA)
        server.enqueue(ok(bodyB))
        val m = twoFiles()
        val p = Progress()
        val dir = downloader.download(m, p.cb)
        assertEquals(1, server.requestCount)
        assertEquals("/vocab.spm", take().url.encodedPath)
        assertContentEquals(bodyA, File(dir, "model.bin").readBytes())
        assertEquals(listOf("model.bin", "vocab.spm"), names(dir))
        assertMonotonic(p.downloaded())
        assertEquals(m.totalSize, p.downloaded().last())
    }

    @Test
    fun download_partCompletoPeroCorruptoSeVuelveABajarEntero() {
        staging().mkdirs()
        File(staging(), "model.bin.part").writeBytes(bytes(bodyA.size, seed = 5))
        server.enqueue(ok(bodyA))
        server.enqueue(ok(bodyB))
        val dir = downloader.download(twoFiles()) { _, _ -> }
        assertNull(take().headers["Range"])
        assertContentEquals(bodyA, File(dir, "model.bin").readBytes())
    }

    @Test
    fun download_archivoYaCompletoYVerificadoNoSeVuelveABajar() {
        staging().mkdirs()
        File(staging(), "model.bin").writeBytes(bodyA)
        File(staging(), "vocab.spm").writeBytes(bodyB)
        val m = twoFiles()
        val p = Progress()
        val dir = downloader.download(m, p.cb)
        assertEquals(0, server.requestCount)
        assertEquals(listOf("model.bin", "vocab.spm"), names(dir))
        assertEquals(m.totalSize, p.downloaded().last())
    }

    @Test
    fun download_archivoFinalAlteradoSeReverificaYSeVuelveABajar() {
        staging().mkdirs()
        File(staging(), "model.bin").writeBytes(bytes(bodyA.size, seed = 3))
        File(staging(), "vocab.spm").writeBytes(bodyB)
        server.enqueue(ok(bodyA))
        val dir = downloader.download(twoFiles()) { _, _ -> }
        assertEquals(1, server.requestCount)
        assertEquals("/model.bin", take().url.encodedPath)
        assertContentEquals(bodyA, File(dir, "model.bin").readBytes())
    }

    // ---------------------------------------------------------------- integridad

    @Test
    fun download_sha256DistintoLanzaIntegrityYBorraElPart() {
        val m = model(file("model.bin", bodyA, sha256 = sha(bodyB)), file("vocab.spm", bodyB))
        server.enqueue(ok(bodyA))
        val e = assertFailsWith<IntegrityException> { downloader.download(m) { _, _ -> } }
        assertFalse(File(staging(), "model.bin.part").exists())
        assertFalse(File(staging(), "model.bin").exists())
        assertFalse(File(modelsDir, "en-es").exists())
        val msg = e.message.orEmpty()
        assertTrue(msg.contains("model.bin"))
        assertFalse(msg.contains(sha(bodyA)))
        assertFalse(msg.contains(sha(bodyB)))
        assertFalse(msg.contains(modelsDir.name))
        assertFalse(msg.contains(File.separator) || msg.contains("/"))
    }

    @Test
    fun download_tamanoDistintoLanzaIntegrityYBorraElPart() {
        // El catálogo dice más bytes de los que llegan; el servidor responde completo según su Content-Length.
        val m = model(file("model.bin", bodyA, size = bodyA.size + 100L), file("vocab.spm", bodyB))
        server.enqueue(ok(bodyA))
        assertFailsWith<IntegrityException> { downloader.download(m) { _, _ -> } }
        assertFalse(File(staging(), "model.bin.part").exists())
        assertFalse(File(staging(), "model.bin").exists())
        assertFalse(File(modelsDir, "en-es").exists())
    }

    @Test
    fun download_tamanoCorrectoPeroContenidoAlteradoLanzaIntegrity() {
        val m = model(file("model.bin", bodyA), file("vocab.spm", bodyB))
        server.enqueue(ok(bytes(bodyA.size, seed = 4)))
        assertFailsWith<IntegrityException> { downloader.download(m) { _, _ -> } }
        assertFalse(File(staging(), "model.bin.part").exists())
    }

    // ---------------------------------------------------------------- nombres y rutas

    @Test
    fun download_alFinalSoloQuedanLosArchivosDelCatalogo() {
        staging().mkdirs()
        File(staging(), "basura.txt").writeText("x")
        File(staging(), "vocab.spm.part").writeBytes(bodyB.copyOfRange(0, 10))
        File(staging(), "subcarpeta").mkdirs()
        File(staging(), "subcarpeta/otro").writeText("y")
        File(staging(), "vocab.spm").writeBytes(bodyB)
        server.enqueue(ok(bodyA))
        val dir = downloader.download(twoFiles()) { _, _ -> }
        assertEquals(listOf("model.bin", "vocab.spm"), names(dir))
    }

    @Test
    fun download_rechazaNombresQueChocanConUnPart() {
        val m = model(file("model.bin", bodyA), file("model.bin.part", bodyB))
        assertFailsWith<IllegalArgumentException> { downloader.download(m) { _, _ -> } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun download_rechazaRutasQueSalenDeLaCarpeta() {
        // El parser ya lo impide; esto es la segunda defensa.
        val m = model(file("../escape.bin", bodyA))
        assertFailsWith<IllegalArgumentException> { downloader.download(m) { _, _ -> } }
        val badId = CatalogModel("..", "en-es", "opus", "1", "x", "x", listOf(file("model.bin", bodyA)))
        assertFailsWith<IllegalArgumentException> { downloader.download(badId) { _, _ -> } }
        assertEquals(0, server.requestCount)
        assertFalse(File(modelsDir, "escape.bin").exists())
    }
}
