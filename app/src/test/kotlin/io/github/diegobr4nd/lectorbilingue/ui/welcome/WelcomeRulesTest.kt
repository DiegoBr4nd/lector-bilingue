package io.github.diegobr4nd.lectorbilingue.ui.welcome

import io.github.diegobr4nd.lectorbilingue.data.HubRules
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.models.Catalog
import io.github.diegobr4nd.lectorbilingue.models.CatalogModel
import io.github.diegobr4nd.lectorbilingue.models.ModelFile
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WelcomeRulesTest {
    private val gib = 1024L * 1024 * 1024

    private fun model(id: String, pair: String, engine: String, size: Long) = CatalogModel(
        id, pair, engine, "1", "x", "x", listOf(ModelFile("model.bin", size, "0".repeat(64), "u")),
    )

    private val prod = Catalog(
        1, Instant.parse("2026-10-04T22:00:14Z"),
        listOf(
            model("opus-en-es-tcbig-2026.10", "en-es", "opus", 238_524_992),
            model("firefox-en-es-3.0", "en-es", "firefox", 36_594_513),
            model("firefox-es-en-3.0", "es-en", "firefox", 37_032_325),
            model("opus-es-en-tcbig-2026.10", "es-en", "opus", 238_203_778),
        ),
    )

    private fun pairs(installed: Set<Pair<String, String>> = emptySet()) =
        HubRules.pairStatuses(prod, installed, emptyMap())

    @Test fun `con 8 GiB se ofrece Calidad y solo en-es viene elegido`() {
        val s = WelcomeRules.step3(pairs(), null, 8 * gib)
        assertEquals(listOf("en-es", "es-en"), s.options.map { it.pair })
        assertEquals(listOf(EngineId.OPUS, EngineId.OPUS), s.options.map { it.recommended.engine })
        assertEquals(listOf(true, false), s.options.map { it.selectedByDefault })
        assertTrue(s.canDownload)
        assertNull(s.message)
    }

    @Test fun `con 3 GiB se ofrece Rapido`() {
        val s = WelcomeRules.step3(pairs(), null, 3 * gib)
        assertEquals(listOf(EngineId.FIREFOX, EngineId.FIREFOX), s.options.map { it.recommended.engine })
    }

    @Test fun `downloadBytes suma solo las elegidas`() {
        val s = WelcomeRules.step3(pairs(), null, 8 * gib)
        assertEquals(238_524_992L, WelcomeRules.downloadBytes(listOf(s.options[0])))
        assertEquals(238_524_992L + 238_203_778L, WelcomeRules.downloadBytes(s.options))
        assertEquals(0L, WelcomeRules.downloadBytes(emptyList()))
    }

    @Test fun `sin catalogo no hay opciones ni descarga`() {
        val s = WelcomeRules.step3(emptyList(), ModelMessage.NO_CATALOG, 8 * gib)
        assertTrue(s.options.isEmpty())
        assertFalse(s.canDownload)
        assertEquals(ModelMessage.NO_CATALOG, s.message)
        assertEquals(ModelMessage.NO_CATALOG, WelcomeRules.step3(emptyList(), null, 8 * gib).message)
        // Aunque queden pares viejos en memoria, NO_CATALOG manda.
        assertTrue(WelcomeRules.step3(pairs(), ModelMessage.NO_CATALOG, 8 * gib).options.isEmpty())
    }

    @Test fun `lo ya instalado no se suma a la descarga`() {
        val s = WelcomeRules.step3(pairs(setOf("opus" to "en-es")), null, 8 * gib)
        assertTrue(s.options[0].recommended.installed)
        assertEquals(0L, WelcomeRules.downloadBytes(listOf(s.options[0])))
        assertEquals(238_203_778L, WelcomeRules.downloadBytes(s.options))
        assertTrue(s.canDownload)
    }

    @Test fun `si todo esta instalado no hay nada que descargar`() {
        val s = WelcomeRules.step3(pairs(setOf("opus" to "en-es", "opus" to "es-en")), null, 8 * gib)
        assertFalse(s.canDownload)
        assertEquals(2, s.options.size)
    }
}
