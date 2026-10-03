package io.github.diegobr4nd.lectorbilingue.models

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var repo: CatalogRepository

    private fun res(name: String) = javaClass.getResource("/minisign/$name")!!.readBytes()

    private val pub1 = MinisignPublicKey.parse(String(res("test.pub")))
    private val viejo = res("catalog-viejo.json")
    private val viejoSig = res("catalog-viejo.json.minisig")
    private val nuevo = res("catalog-nuevo.json")
    private val nuevoSig = res("catalog-nuevo.json.minisig")
    private val viejoBis = res("catalog-viejo-bis.json")
    private val viejoBisSig = res("catalog-viejo-bis.json.minisig")

    private val fechaVieja = Instant.parse("2026-01-01T00:00:00Z")
    private val fechaNueva = Instant.parse("2026-06-01T00:00:00Z")

    /** Lo que sirve el servidor de prueba: (catalog.json, catalog.json.minisig). */
    @Volatile private var servido: Pair<ByteArray, ByteArray> = Pair(ByteArray(0), ByteArray(0))

    /** Retraso (ms) antes de enviar las cabeceras de la firma: simula una red atascada. */
    @Volatile private var retrasoMs = 0L

    /** Lo que devuelve el catálogo "incrustado en el APK". */
    private var incrustado: (() -> Pair<ByteArray, String>?) = { null }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.url.encodedPath) {
                    "/catalogo/catalog.json" -> servido.first
                    "/catalogo/catalog.json.minisig" -> servido.second
                    else -> return MockResponse.Builder().code(404).build()
                }
                val builder = MockResponse.Builder().code(200).body(Buffer().write(body))
                if (request.url.encodedPath.endsWith(".minisig") && retrasoMs > 0) {
                    builder.headersDelay(retrasoMs, TimeUnit.MILLISECONDS)
                }
                return builder.build()
            }
        }
        server.start()
        dir = File(tmp.root, "catalog")
        repo = newRepo()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun newRepo(): CatalogRepository {
        val policy = HostPolicy { url ->
            url.protocol == "http" && url.host in setOf("localhost", "127.0.0.1") && url.port == server.port
        }
        return CatalogRepository(
            dir = dir,
            verifier = MinisignVerifier(listOf(pub1)),
            fetcher = HttpFetcher(policy = policy, connectTimeoutMs = 5_000, readTimeoutMs = 5_000),
            bundled = { incrustado() },
            catalogUrl = server.url("/catalogo/catalog.json").toString(),
        )
    }

    private fun servir(catalogo: ByteArray, firma: ByteArray) {
        servido = Pair(catalogo, firma)
    }

    private fun guardadoCatalogo() = File(dir, "catalog.json")
    private fun guardadaFirma() = File(dir, "catalog.json.minisig")

    private fun aceptarViejo() {
        servir(viejo, viejoSig)
        repo.refresh()
    }

    // ---------------------------------------------------------------- aceptar y guardar

    @Test
    fun refresh_catalogoValido_seAceptaYSeGuardaConSuFirma() {
        servir(nuevo, nuevoSig)

        val catalogo = repo.refresh()

        assertEquals(fechaNueva, catalogo.generated)
        assertEquals(2, catalogo.models.size)
        assertContentEquals(nuevo, guardadoCatalogo().readBytes())
        assertContentEquals(nuevoSig, guardadaFirma().readBytes())
    }

    @Test
    fun current_despuesDeRefresh_devuelveElGuardado() {
        servir(nuevo, nuevoSig)
        repo.refresh()

        assertEquals(fechaNueva, repo.current()?.generated)
        // Una instancia nueva (como tras reiniciar la app) lo lee del disco.
        assertEquals(fechaNueva, newRepo().current()?.generated)
    }

    @Test
    fun refresh_pideElCatalogoYSuFirmaEnLaUrlConfigurada() {
        servir(nuevo, nuevoSig)
        repo.refresh()

        val rutas = listOf(server.takeRequest().url.encodedPath, server.takeRequest().url.encodedPath)
        assertEquals(setOf("/catalogo/catalog.json", "/catalogo/catalog.json.minisig"), rutas.toSet())
    }

    @Test
    fun current_sinNadaGuardadoNiIncrustado_esNull() {
        assertNull(repo.current())
    }

    // ---------------------------------------------------------------- firma

    @Test
    fun refresh_firmaInvalida_lanzaYNoGuardaNada() {
        servir(nuevo, viejoSig)

        assertFailsWith<SignatureException> { repo.refresh() }

        assertFalse(guardadoCatalogo().exists())
        assertFalse(guardadaFirma().exists())
        assertNull(repo.current())
    }

    @Test
    fun refresh_firmaInvalida_conservaElGuardadoAnterior() {
        aceptarViejo()
        servir(nuevo, viejoSig)

        assertFailsWith<SignatureException> { repo.refresh() }

        assertContentEquals(viejo, guardadoCatalogo().readBytes())
        assertContentEquals(viejoSig, guardadaFirma().readBytes())
        assertEquals(fechaVieja, repo.current()?.generated)
    }

    @Test
    fun refresh_firmaValidaPeroCatalogoInvalido_lanzaCatalogExceptionYNoGuarda() {
        // catalog-ok.json está bien firmado pero no cumple la spec (le falta `generated`).
        servir(res("catalog-ok.json"), res("catalog-ok.json.minisig"))

        assertFailsWith<CatalogException> { repo.refresh() }

        assertFalse(guardadoCatalogo().exists())
    }

    // ---------------------------------------------------------------- antirretroceso

    @Test
    fun refresh_catalogoMasAntiguoQueElGuardado_seRechazaYSeConservaElGuardado() {
        servir(nuevo, nuevoSig)
        repo.refresh()
        servir(viejo, viejoSig)

        val e = assertFailsWith<CatalogException> { repo.refresh() }

        assertEquals("catálogo más antiguo", e.message)
        assertContentEquals(nuevo, guardadoCatalogo().readBytes())
        assertContentEquals(nuevoSig, guardadaFirma().readBytes())
        assertEquals(fechaNueva, repo.current()?.generated)
    }

    @Test
    fun refresh_catalogoMasAntiguoQueElIncrustado_seRechazaYNoGuarda() {
        incrustado = { Pair(nuevo, String(nuevoSig)) }
        servir(viejo, viejoSig)

        val e = assertFailsWith<CatalogException> { repo.refresh() }

        assertEquals("catálogo más antiguo", e.message)
        assertFalse(guardadoCatalogo().exists())
        assertEquals(fechaNueva, repo.current()?.generated)
    }

    @Test
    fun refresh_mismaFechaYMismosBytes_noHaceNadaYDevuelveElVigente() {
        aceptarViejo()
        val antes = guardadoCatalogo().lastModified()

        val catalogo = repo.refresh()

        assertEquals(fechaVieja, catalogo.generated)
        assertContentEquals(viejo, guardadoCatalogo().readBytes())
        assertEquals(antes, guardadoCatalogo().lastModified())
    }

    @Test
    fun refresh_mismaFechaQueElIncrustadoYMismosBytes_noFallaNiGuarda() {
        incrustado = { Pair(viejo, String(viejoSig)) }
        servir(viejo, viejoSig)

        assertEquals(fechaVieja, repo.refresh().generated)
        assertFalse(guardadoCatalogo().exists())
    }

    @Test
    fun refresh_mismaFechaPeroOtroContenido_seRechaza() {
        aceptarViejo()
        servir(viejoBis, viejoBisSig)

        val e = assertFailsWith<CatalogException> { repo.refresh() }

        assertEquals("catálogo con la misma fecha y otro contenido", e.message)
        assertContentEquals(viejo, guardadoCatalogo().readBytes())
        assertContentEquals(viejoSig, guardadaFirma().readBytes())
    }

    @Test
    fun refresh_catalogoMasNuevoQueElGuardado_loReemplaza() {
        aceptarViejo()
        servir(nuevo, nuevoSig)

        assertEquals(fechaNueva, repo.refresh().generated)
        assertContentEquals(nuevo, guardadoCatalogo().readBytes())
        assertContentEquals(nuevoSig, guardadaFirma().readBytes())
    }

    // ---------------------------------------------------------------- sin red y topes

    @Test
    fun refresh_sinRed_lanzaIOExceptionYCurrentDevuelveElIncrustado() {
        incrustado = { Pair(viejo, String(viejoSig)) }
        server.close()

        assertFailsWith<IOException> { repo.refresh() }

        assertEquals(fechaVieja, repo.current()?.generated)
        assertFalse(guardadoCatalogo().exists())
    }

    @Test
    fun refresh_catalogoMayorQueUnMiB_lanzaIOExceptionYNoGuarda() {
        servir(ByteArray(CatalogParser.MAX_BYTES + 1) { ' '.code.toByte() }, nuevoSig)

        assertFailsWith<IOException> { repo.refresh() }

        assertFalse(guardadoCatalogo().exists())
    }

    @Test
    fun refresh_firmaMayorQue4KiB_lanzaIOExceptionYNoGuarda() {
        servir(nuevo, nuevoSig + ByteArray(4096) { '\n'.code.toByte() })

        assertFailsWith<IOException> { repo.refresh() }

        assertFalse(guardadoCatalogo().exists())
    }

    // ---------------------------------------------------------------- incrustado frente a guardado

    @Test
    fun current_incrustadoMasNuevoQueElGuardado_devuelveElIncrustado() {
        aceptarViejo()
        incrustado = { Pair(nuevo, String(nuevoSig)) }

        assertEquals(fechaNueva, repo.current()?.generated)
    }

    @Test
    fun current_guardadoMasNuevoQueElIncrustado_devuelveElGuardado() {
        incrustado = { Pair(viejo, String(viejoSig)) }
        servir(nuevo, nuevoSig)
        repo.refresh()

        assertEquals(fechaNueva, repo.current()?.generated)
    }

    @Test
    fun current_soloIncrustado_devuelveElIncrustadoVerificado() {
        incrustado = { Pair(viejo, String(viejoSig)) }

        assertEquals(fechaVieja, repo.current()?.generated)
    }

    @Test
    fun current_incrustadoConFirmaInvalida_seIgnora() {
        incrustado = { Pair(nuevo, String(viejoSig)) }

        assertNull(repo.current())
    }

    @Test
    fun current_incrustadoQueLanza_seIgnoraSinPropagar() {
        incrustado = { throw IOException("asset roto") }

        assertNull(repo.current())
    }

    // ---------------------------------------------------------------- archivos manipulados

    @Test
    fun current_catalogoGuardadoManipulado_seIgnoraYCaeAlIncrustado() {
        servir(nuevo, nuevoSig)
        repo.refresh()
        incrustado = { Pair(viejo, String(viejoSig)) }
        val alterado = nuevo.copyOf().also { it[it.size - 3] = 'X'.code.toByte() }
        guardadoCatalogo().writeBytes(alterado)

        assertEquals(fechaVieja, repo.current()?.generated)
    }

    @Test
    fun current_firmaGuardadaManipulada_seIgnora() {
        servir(nuevo, nuevoSig)
        repo.refresh()
        guardadaFirma().writeText("basura")

        assertNull(repo.current())
    }

    @Test
    fun current_catalogoGuardadoMayorQueElTope_seIgnora() {
        servir(nuevo, nuevoSig)
        repo.refresh()
        guardadoCatalogo().writeBytes(ByteArray(CatalogParser.MAX_BYTES + 1))

        assertNull(repo.current())
    }

    @Test
    fun refresh_guardadoManipulado_noSirveDePisoAntirretroceso() {
        // Si el guardado no verifica, no cuenta; el piso es el incrustado (aquí no hay).
        servir(nuevo, nuevoSig)
        repo.refresh()
        guardadaFirma().writeText("basura")
        servir(viejo, viejoSig)

        assertEquals(fechaVieja, repo.refresh().generated)
        assertContentEquals(viejoSig, guardadaFirma().readBytes())
    }

    // ---------------------------------------------------------------- escritura atómica

    @Test
    fun refresh_noDejaTemporales() {
        servir(nuevo, nuevoSig)
        repo.refresh()

        assertEquals(setOf("catalog.json", "catalog.json.minisig"), dir.list()!!.toSet())
    }

    @Test
    fun refresh_temporalesViejosDeUnCorteAnterior_noEstorban() {
        dir.mkdirs()
        File(dir, "catalog.json.tmp").writeText("resto de un corte")
        File(dir, "catalog.json.minisig.tmp").writeText("resto de un corte")
        servir(nuevo, nuevoSig)

        assertEquals(fechaNueva, repo.refresh().generated)
        assertEquals(setOf("catalog.json", "catalog.json.minisig"), dir.list()!!.toSet())
    }

    @Test
    fun corteEntreLosDosRenombres_noDejaUnParMezcladoAceptado() {
        aceptarViejo()
        servir(nuevo, nuevoSig)
        repo.afterSignatureRename = { throw IOException("corte simulado") }

        assertFailsWith<IOException> { repo.refresh() }

        // En disco quedó la firma nueva con el catálogo viejo (par mezclado, no verifica) y el
        // catálogo nuevo aún en catalog.json.tmp: se acepta solo el par que sí verifica (tmp + firma).
        assertContentEquals(nuevoSig, guardadaFirma().readBytes())
        assertContentEquals(viejo, guardadoCatalogo().readBytes())
        assertContentEquals(nuevo, File(dir, "catalog.json.tmp").readBytes())
        assertEquals(fechaNueva, repo.current()?.generated)
        assertEquals(fechaNueva, newRepo().current()?.generated)
    }

    @Test
    fun corteEntreLosDosRenombres_elPisoAntirretrocesoNoBaja() {
        aceptarViejo()
        servir(nuevo, nuevoSig)
        repo.afterSignatureRename = { throw IOException("corte simulado") }
        assertFailsWith<IOException> { repo.refresh() }
        repo.afterSignatureRename = {}
        servir(viejo, viejoSig)

        val e = assertFailsWith<CatalogException> { repo.refresh() }

        assertEquals("catálogo más antiguo", e.message)
    }

    @Test
    fun corteEntreLosDosRenombres_sinElTemporal_elParMezcladoNoSeAcepta() {
        aceptarViejo()
        servir(nuevo, nuevoSig)
        repo.afterSignatureRename = { throw IOException("corte simulado") }
        assertFailsWith<IOException> { repo.refresh() }
        File(dir, "catalog.json.tmp").delete()

        assertNull(repo.current())
        incrustado = { Pair(viejo, String(viejoSig)) }
        assertEquals(fechaVieja, repo.current()?.generated)
    }

    @Test
    fun temporalQueNoCasaConLaFirma_seIgnora() {
        aceptarViejo()
        File(dir, "catalog.json.tmp").writeBytes(nuevo)

        assertEquals(fechaVieja, repo.current()?.generated)
    }

    @Test
    fun corteEntreLosDosRenombres_elSiguienteRefreshSeRecupera() {
        aceptarViejo()
        servir(nuevo, nuevoSig)
        repo.afterSignatureRename = { throw IOException("corte simulado") }
        assertFailsWith<IOException> { repo.refresh() }
        repo.afterSignatureRename = {}

        assertEquals(fechaNueva, repo.refresh().generated)
        assertEquals(fechaNueva, repo.current()?.generated)
        // El refresh repara el par principal y borra el temporal.
        assertContentEquals(nuevo, guardadoCatalogo().readBytes())
        assertEquals(setOf("catalog.json", "catalog.json.minisig"), dir.list()!!.toSet())
    }

    // ---------------------------------------------------------------- concurrencia

    @Test
    fun current_noSeBloqueaMientrasRefreshEsperaALaRed() {
        aceptarViejo()
        servir(nuevo, nuevoSig)
        retrasoMs = 3_000
        val hilo = Thread { runCatching { repo.refresh() } }
        hilo.start()
        try {
            // Espera a que refresh() esté ya pidiendo el catálogo (atascado en la red).
            server.takeRequest()
            server.takeRequest()
            Thread.sleep(100)

            val inicio = System.nanoTime()
            val actual = repo.current()
            val ms = (System.nanoTime() - inicio) / 1_000_000

            assertEquals(fechaVieja, actual?.generated)
            assertTrue(ms < 1_000, "current() tardó $ms ms")
        } finally {
            hilo.join(10_000)
        }
        assertEquals(fechaNueva, repo.current()?.generated)
    }
}
