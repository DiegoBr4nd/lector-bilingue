package io.github.diegobr4nd.lectorbilingue.data

import io.github.diegobr4nd.lectorbilingue.models.Catalog
import io.github.diegobr4nd.lectorbilingue.models.CatalogException
import io.github.diegobr4nd.lectorbilingue.models.CatalogModel
import io.github.diegobr4nd.lectorbilingue.models.DownloadInProgressException
import io.github.diegobr4nd.lectorbilingue.models.DownloadState
import io.github.diegobr4nd.lectorbilingue.models.IntegrityException
import io.github.diegobr4nd.lectorbilingue.models.ModelFile
import io.github.diegobr4nd.lectorbilingue.models.ModelFileException
import io.github.diegobr4nd.lectorbilingue.models.NetworkPolicyException
import io.github.diegobr4nd.lectorbilingue.models.SignatureException
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelActionsTest {
    private fun model(id: String, pair: String, engine: String, vararg sizes: Long) = CatalogModel(
        id, pair, engine, "1", "MIT", "x",
        sizes.mapIndexed { i, s -> ModelFile("f$i", s, "0".repeat(64), "u") },
    )

    private fun catalog(vararg m: CatalogModel) = Catalog(1, Instant.EPOCH, m.toList())

    @Test fun `la seleccion por par devuelve OPUS y Firefox ordenados`() {
        val c = catalog(
            model("firefox-en-es", "en-es", "firefox", 5),
            model("opus-es-en", "es-en", "opus", 5),
            model("opus-en-es", "en-es", "opus", 5),
        )
        assertEquals(listOf("opus-en-es", "firefox-en-es"), ModelActions.pickModels(c, "en-es").map { it.id })
        assertEquals(listOf("opus-es-en"), ModelActions.pickModels(c, "es-en").map { it.id })
    }

    @Test fun `sin modelos del par devuelve lista vacia`() {
        assertEquals(emptyList(), ModelActions.pickModels(catalog(model("firefox-en-es", "en-es", "firefox", 5)), "es-en"))
    }

    @Test fun `formatea megabytes redondeando al mas cercano`() {
        assertEquals(0L, ModelActions.megabytes(0))
        assertEquals(1L, ModelActions.megabytes(1_048_576))
        assertEquals(2L, ModelActions.megabytes(1_572_864))
        assertEquals(100L, ModelActions.megabytes(100L * 1_048_576 + 100))
    }

    @Test fun `tamano total suma los archivos`() {
        assertEquals(3L, ModelActions.megabytes(model("a", "en-es", "opus", 1_048_576, 2_097_152).totalSize))
    }

    @Test fun `con catalogo guardado se usa y el fallo del refresco es silencioso`() {
        val c = catalog(model("a", "en-es", "opus", 1))
        for (err in listOf(null, IOException("x"), SignatureException("x"), NetworkPolicyException("x"), CatalogException("x"))) {
            assertEquals(c to null, ModelActions.resolveCatalog(c, err))
        }
    }

    @Test fun `sin catalogo firma politica o catalogo dan su mensaje`() {
        assertEquals(null to ModelMessage.SIGNATURE, ModelActions.resolveCatalog(null, SignatureException("x")))
        assertEquals(null to ModelMessage.POLICY, ModelActions.resolveCatalog(null, NetworkPolicyException("x")))
        assertEquals(null to ModelMessage.CATALOG, ModelActions.resolveCatalog(null, CatalogException("x")))
    }

    @Test fun `sin catalogo y sin conexion o 404 es NO_CATALOG`() {
        assertEquals(null to ModelMessage.NO_CATALOG, ModelActions.resolveCatalog(null, IOException("HTTP 404")))
        assertEquals(null to ModelMessage.NO_CATALOG, ModelActions.resolveCatalog(null, null))
        assertEquals(null to ModelMessage.NO_CATALOG, ModelActions.resolveCatalog(null, RuntimeException("x")))
    }

    @Test fun `errores a mensajes`() {
        assertEquals(ModelMessage.FILES, ModelActions.classify(ModelFileException("x")))
        assertEquals(ModelMessage.NETWORK, ModelActions.classify(IOException("x")))
        assertEquals(ModelMessage.POLICY, ModelActions.classify(NetworkPolicyException("x")))
        assertEquals(ModelMessage.SIGNATURE, ModelActions.classify(SignatureException("x")))
        assertEquals(ModelMessage.INTEGRITY, ModelActions.classify(IntegrityException("x")))
        assertEquals(ModelMessage.CATALOG, ModelActions.classify(CatalogException("x")))
        assertEquals(ModelMessage.DOWNLOAD_BUSY, ModelActions.classify(DownloadInProgressException()))
        assertEquals(ModelMessage.INVALID_ZIP, ModelActions.classify(IllegalArgumentException("x")))
        assertEquals(ModelMessage.UNKNOWN, ModelActions.classify(RuntimeException("x")))
    }

    @Test fun `codigos de descarga a mensajes`() {
        assertEquals(ModelMessage.INTEGRITY, ModelActions.fromCode("integridad"))
        assertEquals(ModelMessage.SIGNATURE, ModelActions.fromCode("firma"))
        assertEquals(ModelMessage.CATALOG, ModelActions.fromCode("catalogo"))
        assertEquals(ModelMessage.POLICY, ModelActions.fromCode("politica"))
        assertEquals(ModelMessage.FILES, ModelActions.fromCode("archivos"))
        assertEquals(ModelMessage.NETWORK, ModelActions.fromCode("red"))
        assertEquals(ModelMessage.UNKNOWN, ModelActions.fromCode("desconocido"))
        assertEquals(ModelMessage.UNKNOWN, ModelActions.fromCode(null))
        assertEquals(ModelMessage.UNKNOWN, ModelActions.fromCode("otra cosa"))
    }

    @Test fun `progreso en porcentaje acotado`() {
        assertEquals(null, ModelActions.fraction(0, 0))
        assertEquals(0.5f, ModelActions.fraction(50, 100))
        assertEquals(1f, ModelActions.fraction(200, 100))
    }

    @Test fun `importar zip roto es zip invalido y no red`() {
        assertEquals(ModelMessage.INVALID_ZIP, ModelActions.classifyImport(IOException("zip inválido")))
        assertEquals(ModelMessage.FILES, ModelActions.classifyImport(ModelFileException("x")))
        assertEquals(ModelMessage.IMPORT_NO_MATCH, ModelActions.classifyImport(CatalogException("x")))
        assertEquals(ModelMessage.INTEGRITY, ModelActions.classifyImport(IntegrityException("x")))
        assertEquals(ModelMessage.DOWNLOAD_BUSY, ModelActions.classifyImport(DownloadInProgressException()))
        assertEquals(ModelMessage.UNKNOWN, ModelActions.classifyImport(RuntimeException("x")))
    }

    @Test fun `solo cancelar, importar bien y descargar bien no son errores`() {
        val ok = ModelMessage.entries.filter { !it.isError }.toSet()
        assertEquals(setOf(ModelMessage.CANCELLED, ModelMessage.IMPORT_OK, ModelMessage.DOWNLOAD_OK, ModelMessage.DELETE_OK), ok)
    }

    @Test fun `mensaje final de la descarga segun el estado`() {
        fun info(status: DownloadState.Status, error: String? = null) = DownloadState(status, 0, 0, error, null)
        assertEquals(ModelMessage.DOWNLOAD_OK, ModelActions.finalMessage(info(DownloadState.Status.SUCCEEDED)))
        assertEquals(ModelMessage.CANCELLED, ModelActions.finalMessage(info(DownloadState.Status.CANCELLED)))
        assertEquals(ModelMessage.INTEGRITY, ModelActions.finalMessage(info(DownloadState.Status.FAILED, "integridad")))
        assertEquals(ModelMessage.UNKNOWN, ModelActions.finalMessage(info(DownloadState.Status.FAILED, null)))
        assertNull(ModelActions.finalMessage(info(DownloadState.Status.QUEUED)))
        assertNull(ModelActions.finalMessage(info(DownloadState.Status.RUNNING)))
        assertNull(ModelActions.finalMessage(null))
    }

    @Test fun `importar sin catalogo tiene su propio mensaje`() {
        assertTrue(ModelMessage.NO_CATALOG_IMPORT.isError)
        assertTrue(ModelMessage.NO_CATALOG_IMPORT != ModelMessage.NO_CATALOG)
    }

    private val mine = UUID.randomUUID()
    private fun st(status: DownloadState.Status, id: UUID?) = DownloadState(status, 0, 0, null, id)

    @Test fun `estado final viejo se ignora`() =
        assertFalse(ModelActions.acceptDownloadState(st(DownloadState.Status.SUCCEEDED, UUID.randomUUID()), mine, false))

    @Test fun `estado final de mi peticion se acepta de inmediato`() =
        assertTrue(ModelActions.acceptDownloadState(st(DownloadState.Status.FAILED, mine), mine, false))

    @Test fun `activo y luego final se acepta`() {
        assertTrue(ModelActions.acceptDownloadState(st(DownloadState.Status.RUNNING, mine), mine, false))
        assertTrue(ModelActions.acceptDownloadState(st(DownloadState.Status.SUCCEEDED, UUID.randomUUID()), mine, true))
    }

    @Test fun `trabajo activo conservado con otro id se acepta`() =
        assertTrue(ModelActions.acceptDownloadState(st(DownloadState.Status.RUNNING, UUID.randomUUID()), mine, false))

    @Test fun `sin estado se acepta`() = assertTrue(ModelActions.acceptDownloadState(null, mine, false))
}
