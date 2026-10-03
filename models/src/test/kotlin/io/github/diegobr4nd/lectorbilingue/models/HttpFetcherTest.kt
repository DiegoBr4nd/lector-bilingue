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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpFetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var fetcher: HttpFetcher

    /** Solo en pruebas: permite http hacia el servidor local (localhost/127.0.0.1 en su puerto). */
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
        fetcher = HttpFetcher(policy = localPolicy(), connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun bytes(n: Int, seed: Int = 7): ByteArray = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun buffer(b: ByteArray) = Buffer().write(b)

    private fun ok(b: ByteArray) = MockResponse.Builder().code(200).body(buffer(b)).build()

    private fun redirect(code: Int, location: String) =
        MockResponse.Builder().code(code).addHeader("Location", location).build()

    // ---------------------------------------------------------------- GitHubHostPolicy

    private fun gh(u: String) = GitHubHostPolicy.allows(URL(u))

    @Test
    fun githubPolicy_aceptaLosTresHostsPorHttps() {
        assertTrue(gh("https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/catalogo/catalog.json"))
        assertTrue(gh("https://objects.githubusercontent.com/github-production-release-asset/1"))
        assertTrue(gh("https://release-assets.githubusercontent.com/github-production-release-asset/1"))
    }

    @Test
    fun githubPolicy_aceptaMayusculasYPuerto443Explicito() {
        assertTrue(gh("HTTPS://GitHub.COM/x"))
        assertTrue(gh("https://github.com:443/x"))
    }

    @Test
    fun githubPolicy_rechazaHttpYOtrosEsquemas() {
        assertFalse(gh("http://github.com/x"))
        assertFalse(gh("ftp://github.com/x"))
        assertFalse(gh("http://objects.githubusercontent.com:443/x"))
    }

    @Test
    fun githubPolicy_rechazaSufijosYPrefijosTramposos() {
        assertFalse(gh("https://github.com.evil.com/x"))
        assertFalse(gh("https://evilgithub.com/x"))
        assertFalse(gh("https://objects.githubusercontent.com.evil.com/x"))
        assertFalse(gh("https://githubusercontent.com/x"))
    }

    @Test
    fun githubPolicy_rechazaSubdominios() {
        assertFalse(gh("https://api.github.com/x"))
        assertFalse(gh("https://evil.github.com/x"))
        assertFalse(gh("https://raw.githubusercontent.com/x"))
    }

    @Test
    fun githubPolicy_rechazaUserinfo() {
        assertFalse(gh("https://user@github.com.evil/x"))
        assertFalse(gh("https://user@github.com/x"))
        assertFalse(gh("https://github.com@evil.com/x"))
        assertFalse(gh("https://github.com:443@evil.com/x"))
    }

    @Test
    fun githubPolicy_rechazaPuntoFinalYHostsInternacionales() {
        assertFalse(gh("https://github.com./x"))
        assertFalse(gh("https://xn--gthub-zsa.com/x"))
        assertFalse(gh("https://gíthub.com/x"))
    }

    @Test
    fun githubPolicy_rechazaPuertosRaros() {
        assertFalse(gh("https://github.com:8443/x"))
        assertFalse(gh("https://github.com:80/x"))
        assertFalse(gh("https://evil.com:443/x"))
    }

    // ---------------------------------------------------------------- fetchBytes

    @Test
    fun fetchBytes_200DevuelveElCuerpo() {
        val body = bytes(1000)
        server.enqueue(ok(body))
        assertContentEquals(body, fetcher.fetchBytes(url("/catalog.json"), 4096))
    }

    @Test
    fun fetchBytes_enviaCabecerasMinimasYSinCookie() {
        server.enqueue(ok(bytes(10)))
        fetcher.fetchBytes(url("/a"), 100)
        val req = take()
        assertEquals("GET", req.method)
        assertEquals("lector-bilingue", req.headers["User-Agent"])
        assertEquals("identity", req.headers["Accept-Encoding"])
        assertNull(req.headers["Cookie"])
        assertNull(req.headers["Range"])
    }

    @Test
    fun fetchBytes_noDevuelveCookiesRecibidasEnLaRedireccion() {
        server.enqueue(
            MockResponse.Builder().code(302)
                .addHeader("Location", "/b")
                .addHeader("Set-Cookie", "sesion=secreta; Path=/")
                .build(),
        )
        server.enqueue(ok(bytes(5)))
        fetcher.fetchBytes(url("/a"), 100)
        assertNull(take().headers["Cookie"])
        assertNull(take().headers["Cookie"])
    }

    @Test
    fun fetchBytes_404LanzaIOExceptionConCodigoYHostSinCuerpoNiRuta() {
        server.enqueue(MockResponse.Builder().code(404).body("cuerpo-secreto").build())
        val e = assertFailsWith<IOException> { fetcher.fetchBytes(url("/ruta-privada/x"), 100) }
        val msg = e.message.orEmpty()
        assertTrue(msg.contains("404"), msg)
        assertTrue(msg.contains(server.url("/").host), msg)
        assertFalse(msg.contains("cuerpo-secreto"), msg)
        assertFalse(msg.contains("ruta-privada"), msg)
    }

    @Test
    fun fetchBytes_cuerpoMayorQueElTopeLanza() {
        server.enqueue(ok(bytes(101)))
        assertFailsWith<IOException> { fetcher.fetchBytes(url("/a"), 100) }
    }

    @Test
    fun fetchBytes_cuerpoChunkedMayorQueElTopeLanza() {
        server.enqueue(MockResponse.Builder().code(200).chunkedBody(buffer(bytes(5000)), 512).build())
        assertFailsWith<IOException> { fetcher.fetchBytes(url("/a"), 1000) }
    }

    @Test
    fun fetchBytes_cuerpoIgualAlTopeSeAcepta() {
        val body = bytes(100)
        server.enqueue(MockResponse.Builder().code(200).chunkedBody(buffer(body), 7).build())
        assertContentEquals(body, fetcher.fetchBytes(url("/a"), 100))
    }

    @Test
    fun fetchBytes_sigueRedireccion302AHostPermitido() {
        val body = bytes(20)
        server.enqueue(redirect(302, url("/destino")))
        server.enqueue(ok(body))
        assertContentEquals(body, fetcher.fetchBytes(url("/origen"), 100))
        assertEquals("/origen", take().url.encodedPath)
        assertEquals("/destino", take().url.encodedPath)
    }

    @Test
    fun fetchBytes_resuelveLocationRelativo() {
        val body = bytes(20)
        server.enqueue(redirect(301, "../c/d"))
        server.enqueue(ok(body))
        assertContentEquals(body, fetcher.fetchBytes(url("/a/b/x"), 100))
        take()
        assertEquals("/a/c/d", take().url.encodedPath)
    }

    @Test
    fun fetchBytes_sigueTodosLosCodigosDeRedireccion() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            server.enqueue(redirect(code, "/fin$code"))
            server.enqueue(ok(bytes(3)))
            assertContentEquals(bytes(3), fetcher.fetchBytes(url("/ini$code"), 10))
            take()
            assertEquals("/fin$code", take().url.encodedPath)
        }
    }

    @Test
    fun fetchBytes_302AHostNoPermitidoLanzaPolicySinConectar() {
        server.enqueue(redirect(302, "http://evil.example/robar"))
        assertFailsWith<NetworkPolicyException> { fetcher.fetchBytes(url("/a"), 100) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun fetchBytes_302AOtroPuertoOEsquemaLanzaPolicy() {
        server.enqueue(redirect(302, "https://localhost:${server.port}/x"))
        assertFailsWith<NetworkPolicyException> { fetcher.fetchBytes(url("/a"), 100) }
        server.enqueue(redirect(302, "file:///etc/passwd"))
        assertFailsWith<NetworkPolicyException> { fetcher.fetchBytes(url("/a"), 100) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun fetchBytes_urlInicialNoPermitidaLanzaPolicySinConectar() {
        val strict = HttpFetcher(policy = GitHubHostPolicy)
        assertFailsWith<NetworkPolicyException> { strict.fetchBytes(url("/a"), 100) }
        assertFailsWith<NetworkPolicyException> { strict.fetchBytes("no es una url", 100) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun fetchBytes_cincoRedireccionesSeAceptan() {
        for (i in 1..5) server.enqueue(redirect(302, "/r$i"))
        server.enqueue(ok(bytes(4)))
        assertContentEquals(bytes(4), fetcher.fetchBytes(url("/r0"), 10))
        assertEquals(6, server.requestCount)
    }

    @Test
    fun fetchBytes_seisRedireccionesLanzan() {
        for (i in 1..6) server.enqueue(redirect(302, "/r$i"))
        server.enqueue(ok(bytes(4)))
        assertFailsWith<IOException> { fetcher.fetchBytes(url("/r0"), 10) }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun fetchBytes_redireccionSinLocationLanza() {
        server.enqueue(MockResponse.Builder().code(302).build())
        assertFailsWith<IOException> { fetcher.fetchBytes(url("/a"), 10) }
    }

    @Test
    fun fetchBytes_otros3xxLanzan() {
        server.enqueue(MockResponse.Builder().code(300).addHeader("Location", "/b").build())
        assertFailsWith<IOException> { fetcher.fetchBytes(url("/a"), 10) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun fetchBytes_respetaTimeoutDeLectura() {
        val lento = HttpFetcher(policy = localPolicy(), connectTimeoutMs = 2_000, readTimeoutMs = 200)
        server.enqueue(ok(bytes(10)).newBuilder().headersDelay(3, TimeUnit.SECONDS).build())
        assertFailsWith<IOException> { lento.fetchBytes(url("/a"), 100) }
    }

    @Test
    fun fetchBytes_conCookieHandlerGlobalSeNiegaSinConectar() {
        val previo = CookieHandler.getDefault()
        try {
            CookieHandler.setDefault(CookieManager())
            assertFailsWith<NetworkPolicyException> { fetcher.fetchBytes(url("/a"), 10) }
            assertEquals(0, server.requestCount)
        } finally {
            CookieHandler.setDefault(previo)
        }
    }

    // ---------------------------------------------------------------- downloadTo

    private class Recorder {
        val out = ByteArrayOutputStream()
        var resets = 0
        val onBytes: (ByteArray, Int) -> Unit = { b, n -> out.write(b, 0, n) }
        val onReset: () -> Unit = { resets++; out.reset() }
    }

    private fun newTarget(): File = File(tmp.root, "modelo.bin")

    @Test
    fun downloadTo_errorAlEscribirEsErrorDeArchivosSinRuta() {
        server.enqueue(ok(bytes(200_000)))
        val full = object : java.io.OutputStream() {
            override fun write(b: Int) = throw IOException("No space left on device /data/user/0/secreto")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("No space left on device /data/user/0/secreto")
        }
        val disco = HttpFetcher(localPolicy(), 5_000, 5_000) { _, _ -> full }
        val e = assertFailsWith<ModelFileException> { disco.downloadTo(url("/m.bin"), newTarget(), 1_000_000, { _, _ -> }) }
        assertFalse(e.message.orEmpty().contains('/'))
        assertNull(e.cause)
    }

    @Test
    fun downloadTo_noSePuedeAbrirElDestinoEsErrorDeArchivos() {
        server.enqueue(ok(bytes(100)))
        val target = File(tmp.root, "no-existe/modelo.bin")
        val e = assertFailsWith<ModelFileException> { fetcher.downloadTo(url("/m.bin"), target, 1_000, { _, _ -> }) }
        assertFalse(e.message.orEmpty().contains(tmp.root.name))
    }

    @Test
    fun downloadTo_descargaCompleta() {
        val body = bytes(200_000)
        server.enqueue(ok(body))
        val target = newTarget()
        val rec = Recorder()
        fetcher.downloadTo(url("/m.bin"), target, 1_000_000, rec.onBytes, rec.onReset)
        assertContentEquals(body, target.readBytes())
        assertContentEquals(body, rec.out.toByteArray())
        assertEquals(0, rec.resets)
        val req = take()
        assertNull(req.headers["Range"])
        assertEquals("lector-bilingue", req.headers["User-Agent"])
        assertEquals("identity", req.headers["Accept-Encoding"])
        assertNull(req.headers["Cookie"])
    }

    @Test
    fun downloadTo_reanudaConRangeY206() {
        val body = bytes(150_000)
        val n = 60_000
        val target = newTarget().apply { writeBytes(body.copyOfRange(0, n)) }
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes $n-${body.size - 1}/${body.size}")
                .body(buffer(body.copyOfRange(n, body.size)))
                .build(),
        )
        val rec = Recorder()
        fetcher.downloadTo(url("/m.bin"), target, 1_000_000, rec.onBytes, rec.onReset)
        assertEquals("bytes=$n-", take().headers["Range"])
        assertContentEquals(body, target.readBytes())
        assertContentEquals(body.copyOfRange(n, body.size), rec.out.toByteArray())
        assertEquals(0, rec.resets)
    }

    @Test
    fun downloadTo_mantieneRangeEnCadaSaltoDeRedireccion() {
        val body = bytes(1000)
        val n = 400
        val target = newTarget().apply { writeBytes(body.copyOfRange(0, n)) }
        server.enqueue(redirect(302, "/cdn/m.bin"))
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes $n-${body.size - 1}/${body.size}")
                .body(buffer(body.copyOfRange(n, body.size)))
                .build(),
        )
        fetcher.downloadTo(url("/m.bin"), target, 10_000, { _, _ -> })
        assertEquals("bytes=$n-", take().headers["Range"])
        assertEquals("bytes=$n-", take().headers["Range"])
        assertContentEquals(body, target.readBytes())
    }

    @Test
    fun downloadTo_200ARangeReiniciaDesdeCero() {
        val body = bytes(5000)
        val target = newTarget().apply { writeBytes(bytes(1234, seed = 99)) }
        server.enqueue(ok(body))
        val rec = Recorder()
        fetcher.downloadTo(url("/m.bin"), target, 10_000, rec.onBytes, rec.onReset)
        assertEquals("bytes=1234-", take().headers["Range"])
        assertContentEquals(body, target.readBytes())
        assertContentEquals(body, rec.out.toByteArray())
        assertEquals(1, rec.resets)
    }

    @Test
    fun downloadTo_206ConInicioDistintoReiniciaSinRange() {
        val body = bytes(5000)
        val target = newTarget().apply { writeBytes(body.copyOfRange(0, 1000)) }
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 0-4999/5000")
                .body(buffer(body))
                .build(),
        )
        server.enqueue(ok(body))
        val rec = Recorder()
        fetcher.downloadTo(url("/m.bin"), target, 10_000, rec.onBytes, rec.onReset)
        assertEquals("bytes=1000-", take().headers["Range"])
        assertNull(take().headers["Range"])
        assertContentEquals(body, target.readBytes())
        assertContentEquals(body, rec.out.toByteArray())
        assertEquals(1, rec.resets)
    }

    @Test
    fun downloadTo_206SinContentRangeReiniciaSinRange() {
        val body = bytes(3000)
        val target = newTarget().apply { writeBytes(body.copyOfRange(0, 1000)) }
        server.enqueue(MockResponse.Builder().code(206).body(buffer(body.copyOfRange(1000, 3000))).build())
        server.enqueue(ok(body))
        val rec = Recorder()
        fetcher.downloadTo(url("/m.bin"), target, 10_000, rec.onBytes, rec.onReset)
        take()
        assertNull(take().headers["Range"])
        assertContentEquals(body, target.readBytes())
        assertEquals(1, rec.resets)
    }

    @Test
    fun downloadTo_206SinPedirRangeLanza() {
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 0-9/10")
                .body(buffer(bytes(10)))
                .build(),
        )
        assertFailsWith<IOException> { fetcher.downloadTo(url("/m.bin"), newTarget(), 100, { _, _ -> }) }
    }

    @Test
    fun downloadTo_416Lanza() {
        val target = newTarget().apply { writeBytes(bytes(100)) }
        server.enqueue(MockResponse.Builder().code(416).build())
        assertFailsWith<IOException> { fetcher.downloadTo(url("/m.bin"), target, 1000, { _, _ -> }) }
    }

    @Test
    fun downloadTo_404LanzaYNoTocaElArchivo() {
        val parcial = bytes(100)
        val target = newTarget().apply { writeBytes(parcial) }
        server.enqueue(MockResponse.Builder().code(404).body("secreto").build())
        val e = assertFailsWith<IOException> { fetcher.downloadTo(url("/m.bin"), target, 1000, { _, _ -> }) }
        assertTrue(e.message.orEmpty().contains("404"))
        assertFalse(e.message.orEmpty().contains("secreto"))
        assertContentEquals(parcial, target.readBytes())
    }

    @Test
    fun downloadTo_superarElTopeEnStreamAbortaYNoExcede() {
        val max = 100_000L
        server.enqueue(MockResponse.Builder().code(200).chunkedBody(buffer(bytes(300_000)), 4096).build())
        val target = newTarget()
        val rec = Recorder()
        assertFailsWith<IOException> { fetcher.downloadTo(url("/m.bin"), target, max, rec.onBytes, rec.onReset) }
        assertTrue(target.length() <= max, "len=${target.length()}")
        assertEquals(target.length(), rec.out.size().toLong())
        assertContentEquals(target.readBytes(), rec.out.toByteArray())
    }

    @Test
    fun downloadTo_contentLengthMayorQueElTopeAbortaSinEscribir() {
        server.enqueue(ok(bytes(5000)))
        val target = newTarget()
        val rec = Recorder()
        assertFailsWith<IOException> { fetcher.downloadTo(url("/m.bin"), target, 1000, rec.onBytes, rec.onReset) }
        assertTrue(target.length() <= 1000)
        assertEquals(target.length(), rec.out.size().toLong())
    }

    @Test
    fun downloadTo_reanudacionQueSuperaElTopeAbortaYNoExcede() {
        val n = 800
        val target = newTarget().apply { writeBytes(bytes(n)) }
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes $n-4999/5000")
                .chunkedBody(buffer(bytes(4200)), 100)
                .build(),
        )
        val rec = Recorder()
        assertFailsWith<IOException> { fetcher.downloadTo(url("/m.bin"), target, 1000, rec.onBytes, rec.onReset) }
        assertTrue(target.length() <= 1000, "len=${target.length()}")
        assertEquals(target.length() - n, rec.out.size().toLong())
    }

    @Test
    fun downloadTo_archivoParcialMayorQueElTopeReiniciaSinRange() {
        val body = bytes(500)
        val target = newTarget().apply { writeBytes(bytes(2000)) }
        server.enqueue(ok(body))
        val rec = Recorder()
        fetcher.downloadTo(url("/m.bin"), target, 1000, rec.onBytes, rec.onReset)
        assertNull(take().headers["Range"])
        assertContentEquals(body, target.readBytes())
        assertEquals(1, rec.resets)
    }

    @Test
    fun downloadTo_redireccionAHostNoPermitidoLanzaPolicy() {
        server.enqueue(redirect(302, "http://evil.example/m.bin"))
        val target = newTarget()
        assertFailsWith<NetworkPolicyException> { fetcher.downloadTo(url("/m.bin"), target, 1000, { _, _ -> }) }
        assertEquals(1, server.requestCount)
        assertFalse(target.exists() && target.length() > 0)
    }

    // ---------------------------------------------------------------- correcciones tras revisión

    @Test
    fun fetchBytes_redireccionProtocoloRelativoLanzaPolicySinConectar() {
        server.enqueue(redirect(302, "//evil.example/x"))
        assertFailsWith<NetworkPolicyException> { fetcher.fetchBytes(url("/a"), 100) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun fetchBytes_redireccionConBarraInvertidaLanzaPolicySinConectar() {
        server.enqueue(redirect(302, "/\\evil.example/x"))
        assertFailsWith<NetworkPolicyException> { fetcher.fetchBytes(url("/a"), 100) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun downloadTo_reinicioPor200TruncaAntesDeAvisar() {
        val body = bytes(5000)
        val target = newTarget().apply { writeBytes(bytes(1234, seed = 99)) }
        server.enqueue(ok(body))
        val largosAlAvisar = mutableListOf<Long>()
        fetcher.downloadTo(url("/m.bin"), target, 10_000, { _, _ -> }, { largosAlAvisar += target.length() })
        assertEquals(listOf(0L), largosAlAvisar)
        assertContentEquals(body, target.readBytes())
    }

    @Test
    fun downloadTo_reinicioPor200YCorteDejaArchivoYHashCoherentes() {
        val body = bytes(200_000)
        val target = newTarget().apply { writeBytes(bytes(1234, seed = 99)) }
        server.enqueue(ok(body).newBuilder().onResponseBody(SocketEffect.CloseSocket()).build())
        val rec = Recorder()
        assertFailsWith<IOException> { fetcher.downloadTo(url("/m.bin"), target, 1_000_000, rec.onBytes, rec.onReset) }
        assertEquals(1, rec.resets)
        assertContentEquals(target.readBytes(), rec.out.toByteArray())
    }

    @Test
    fun downloadTo_200IncompletoLanzaYConservaElParcial() {
        val body = bytes(200_000)
        val target = newTarget()
        server.enqueue(ok(body).newBuilder().onResponseBody(SocketEffect.CloseSocket()).build())
        val rec = Recorder()
        val e = assertFailsWith<IOException> { fetcher.downloadTo(url("/m.bin"), target, 1_000_000, rec.onBytes, rec.onReset) }
        assertFalse(e.message.orEmpty().contains("/m.bin"))
        assertTrue(target.exists())
        assertTrue(target.length() < body.size)
        assertContentEquals(body.copyOfRange(0, target.length().toInt()), target.readBytes())
        assertContentEquals(target.readBytes(), rec.out.toByteArray())
    }

    @Test
    fun downloadTo_206IncompletoLanzaYConservaElParcial() {
        val body = bytes(200_000)
        val n = 1000
        val target = newTarget().apply { writeBytes(body.copyOfRange(0, n)) }
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes $n-${body.size - 1}/${body.size}")
                .body(buffer(body.copyOfRange(n, body.size)))
                .onResponseBody(SocketEffect.CloseSocket())
                .build(),
        )
        assertFailsWith<IOException> { fetcher.downloadTo(url("/m.bin"), target, 1_000_000, { _, _ -> }) }
        assertTrue(target.length() in n.toLong() until body.size.toLong())
        assertContentEquals(body.copyOfRange(0, target.length().toInt()), target.readBytes())
    }

    @Test
    fun downloadTo_206ConCuerpoMasCortoQueElRangoLanza() {
        val n = 100
        val target = newTarget().apply { writeBytes(bytes(n)) }
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes $n-999/1000")
                .chunkedBody(buffer(bytes(500)), 128)
                .build(),
        )
        val e = assertFailsWith<IOException> { fetcher.downloadTo(url("/m.bin"), target, 10_000, { _, _ -> }) }
        assertTrue(e.message.orEmpty().contains("incompleta"), e.message)
        assertEquals(600L, target.length())
    }

    @Test
    fun downloadTo_206ConContentLengthQueContradiceElRangoLanzaSinEscribir() {
        val n = 100
        val target = newTarget().apply { writeBytes(bytes(n)) }
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes $n-999/1000")
                .body(buffer(bytes(500)))
                .build(),
        )
        assertFailsWith<IOException> { fetcher.downloadTo(url("/m.bin"), target, 10_000, { _, _ -> }) }
        assertEquals(100L, target.length())
    }

    @Test
    fun downloadTo_206ConTotalDesconocidoYCuerpoEnormeRespetaElTope() {
        val n = 100
        val target = newTarget().apply { writeBytes(bytes(n)) }
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes $n-199/*")
                .chunkedBody(buffer(bytes(50_000)), 256)
                .build(),
        )
        val rec = Recorder()
        assertFailsWith<IOException> { fetcher.downloadTo(url("/m.bin"), target, 1000, rec.onBytes, rec.onReset) }
        assertTrue(target.length() <= 200, "len=${target.length()}")
        assertEquals(target.length() - n, rec.out.size().toLong())
    }

    @Test
    fun downloadTo_206QueNoLlegaAlFinalDelTotalLanzaSinEscribir() {
        val body = bytes(1000)
        val n = 100
        val target = newTarget().apply { writeBytes(body.copyOfRange(0, n)) }
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes $n-499/1000")
                .body(buffer(body.copyOfRange(n, 500)))
                .build(),
        )
        val rec = Recorder()
        assertFailsWith<IOException> { fetcher.downloadTo(url("/m.bin"), target, 10_000, rec.onBytes, rec.onReset) }
        assertContentEquals(body.copyOfRange(0, n), target.readBytes())
        assertEquals(0, rec.out.size())
        assertEquals(0, rec.resets)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun downloadTo_206ConInicioMayorQueElParcialReinicia() {
        val body = bytes(3000)
        val target = newTarget().apply { writeBytes(body.copyOfRange(0, 1000)) }
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 2000-2999/3000")
                .body(buffer(body.copyOfRange(2000, 3000)))
                .build(),
        )
        server.enqueue(ok(body))
        val rec = Recorder()
        fetcher.downloadTo(url("/m.bin"), target, 10_000, rec.onBytes, rec.onReset)
        assertEquals("bytes=1000-", take().headers["Range"])
        assertNull(take().headers["Range"])
        assertContentEquals(body, target.readBytes())
        assertEquals(1, rec.resets)
    }

    @Test
    fun downloadTo_parcialIgualAlTopeReiniciaSinRange() {
        val body = bytes(1000)
        val target = newTarget().apply { writeBytes(bytes(1000, seed = 3)) }
        server.enqueue(ok(body))
        val largosAlAvisar = mutableListOf<Long>()
        fetcher.downloadTo(url("/m.bin"), target, 1000, { _, _ -> }, { largosAlAvisar += target.length() })
        assertNull(take().headers["Range"])
        assertEquals(listOf(0L), largosAlAvisar)
        assertContentEquals(body, target.readBytes())
    }

    @Test
    fun parseContentRange_valido() {
        assertEquals(HttpFetcher.ContentRange(0, 9, 10), HttpFetcher.parseContentRange("bytes 0-9/10"))
        assertEquals(HttpFetcher.ContentRange(5, 9, null), HttpFetcher.parseContentRange("bytes 5-9/*"))
        assertEquals(HttpFetcher.ContentRange(5, 9, 10), HttpFetcher.parseContentRange("  bytes 5-9/10 "))
    }

    @Test
    fun parseContentRange_invalido() {
        assertNull(HttpFetcher.parseContentRange(null))
        assertNull(HttpFetcher.parseContentRange(""))
        assertNull(HttpFetcher.parseContentRange("bytes 9-5/10"))
        assertNull(HttpFetcher.parseContentRange("bytes 0-10/10"))
        assertNull(HttpFetcher.parseContentRange("bytes  0-9/10"))
        assertNull(HttpFetcher.parseContentRange("bytes 0 - 9/10"))
        assertNull(HttpFetcher.parseContentRange("bytes 1234567890123456789-1234567890123456790/*"))
        assertNull(HttpFetcher.parseContentRange("bytes */10"))
        assertNull(HttpFetcher.parseContentRange("items 0-9/10"))
        assertNull(HttpFetcher.parseContentRange("bytes -1-9/10"))
    }
}
