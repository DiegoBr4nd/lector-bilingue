package io.github.diegobr4nd.lectorbilingue.ui.enginetest

import io.github.diegobr4nd.lectorbilingue.models.Catalog
import io.github.diegobr4nd.lectorbilingue.models.CatalogException
import io.github.diegobr4nd.lectorbilingue.models.CatalogModel
import io.github.diegobr4nd.lectorbilingue.models.DownloadInProgressException
import io.github.diegobr4nd.lectorbilingue.models.IntegrityException
import io.github.diegobr4nd.lectorbilingue.models.ModelFile
import io.github.diegobr4nd.lectorbilingue.models.ModelFileException
import io.github.diegobr4nd.lectorbilingue.models.NetworkPolicyException
import io.github.diegobr4nd.lectorbilingue.models.SignatureException
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelActionsTest {
    private fun model(id: String, pair: String, engine: String, vararg sizes: Long) = CatalogModel(
        id, pair, engine, "1", "MIT", "x",
        sizes.mapIndexed { i, s -> ModelFile("f$i", s, "0".repeat(64), "u") },
    )

    private fun catalog(vararg m: CatalogModel) = Catalog(1, Instant.EPOCH, m.toList())

    @Test fun `elige el modelo en-es de opus`() {
        val c = catalog(
            model("firefox-en-es", "en-es", "firefox", 5),
            model("opus-es-en", "es-en", "opus", 5),
            model("opus-en-es", "en-es", "opus", 5),
        )
        assertEquals("opus-en-es", ModelActions.pickModel(c)?.id)
    }

    @Test fun `sin modelo en-es opus devuelve null`() {
        assertNull(ModelActions.pickModel(catalog(model("firefox-en-es", "en-es", "firefox", 5))))
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
}
