package io.github.diegobr4nd.lectorbilingue.models

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Comprueba las llaves de producción y el catálogo firmado de verdad que va dentro del APK. */
class ProductionCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun prod(name: String) = javaClass.getResource("/catalog-prod/$name")!!.readBytes()
    private val catalog = prod("catalog.json")
    private val signature = String(prod("catalog.json.minisig"), Charsets.UTF_8)
    private val verifier = MinisignVerifier(TrustedKeys.production)

    /** Id como lo imprime minisign: 8 bytes little-endian en hexadecimal mayúscula. */
    private fun idHex(key: MinisignPublicKey) =
        key.keyId.reversed().joinToString("") { "%02X".format(it) }

    /** El asset real del APK. El directorio de trabajo de las pruebas de Gradle es la carpeta del módulo (:models). */
    private fun assetFile(name: String): File {
        val f = File("../app/src/main/assets/catalog/$name").canonicalFile
        assertTrue(f.isFile, "no se encontró el asset real ${f.path}; las pruebas deben correr con cwd = carpeta del módulo :models")
        return f
    }

    @Test
    fun copiasDePruebaSonIgualesAlAssetRealDelApk() {
        for (name in listOf("catalog.json", "catalog.json.minisig")) {
            assertTrue(
                prod(name).contentEquals(assetFile(name).readBytes()),
                "catalog-prod/$name difiere de app/src/main/assets/catalog/$name: copia el asset real a models/src/test/resources/catalog-prod/",
            )
        }
    }

    @Test
    fun production_tieneExactamenteLasDosLlavesEsperadas() {
        val ids = TrustedKeys.production.map(::idHex)
        assertEquals(listOf("2FD11AA2479CDAA4", "162048C19C9F87EC"), ids)
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun production_noIncluyeNingunaLlaveDePrueba() {
        val dir = File(javaClass.getResource("/minisign/test.pub")!!.toURI()).parentFile
        val pubs = dir.listFiles { f -> f.extension == "pub" }!!
        assertTrue(pubs.size >= 3)
        val prodIds = TrustedKeys.production.map(::idHex).toSet()
        for (pub in pubs) {
            val id = idHex(MinisignPublicKey.parse(pub.readText()))
            assertFalse(id in prodIds, "la llave de prueba ${pub.name} no debe ser de producción")
        }
    }

    @Test
    fun verificador_aceptaElCatalogoRealYElParserLoLee() {
        verifier.verify(catalog, signature)
        val parsed = CatalogParser.parse(catalog)
        assertEquals(1, parsed.models.size)
        val m = parsed.models.single()
        assertEquals("opus-en-es-tcbig-2026.10", m.id)
        assertEquals("en-es", m.pair)
        assertEquals("opus", m.engine)
        assertEquals(8, m.files.size)
    }

    @Test
    fun repositorio_sinRedDevuelveElCatalogoIncrustado() {
        val repo = CatalogRepository(
            dir = File(tmp.root, "catalog"),
            verifier = verifier,
            fetcher = HttpFetcher(policy = HostPolicy { false }, connectTimeoutMs = 1_000, readTimeoutMs = 1_000),
            bundled = { catalog to signature },
        )
        val current = assertNotNull(repo.current())
        assertEquals("opus-en-es-tcbig-2026.10", current.models.single().id)
    }

    @Test
    fun catalogoAlteradoSeRechaza() {
        val tampered = catalog.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { verifier.verify(tampered, signature) }
        val repo = CatalogRepository(
            dir = File(tmp.root, "catalog2"),
            verifier = verifier,
            fetcher = HttpFetcher(policy = HostPolicy { false }, connectTimeoutMs = 1_000, readTimeoutMs = 1_000),
            bundled = { tampered to signature },
        )
        assertEquals(null, repo.current())
    }
}
