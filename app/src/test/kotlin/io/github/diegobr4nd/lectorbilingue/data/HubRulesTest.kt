package io.github.diegobr4nd.lectorbilingue.data

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.models.Catalog
import io.github.diegobr4nd.lectorbilingue.models.CatalogModel
import io.github.diegobr4nd.lectorbilingue.models.DownloadState
import io.github.diegobr4nd.lectorbilingue.models.ModelFile
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubRulesTest {
    private val gib = 1024L * 1024 * 1024

    private fun model(id: String, pair: String, engine: String, size: Long) = CatalogModel(
        id, pair, engine, "1", "x", "x", listOf(ModelFile("model.bin", size, "0".repeat(64), "u")),
    )

    /** Igual que models/src/test/resources/catalog-prod/catalog.json: ids, pares, motores, orden y tamaño total. */
    private val prod = Catalog(
        1, Instant.parse("2026-10-04T22:00:14Z"),
        listOf(
            model("opus-en-es-tcbig-2026.10", "en-es", "opus", 238_524_992),
            model("firefox-en-es-3.0", "en-es", "firefox", 36_594_513),
            model("firefox-es-en-3.0", "es-en", "firefox", 37_032_325),
            model("opus-es-en-tcbig-2026.10", "es-en", "opus", 238_203_778),
        ),
    )

    private fun statuses(installed: Set<Pair<String, String>> = emptySet(), downloads: Map<String, DownloadState?> = emptyMap()) =
        HubRules.pairStatuses(prod, installed, downloads)

    @Test fun `el catalogo real da dos pares en orden con OPUS y luego Firefox`() {
        val s = statuses()
        assertEquals(listOf("en-es", "es-en"), s.map { it.pair })
        for (p in s) assertEquals(listOf(EngineId.OPUS, EngineId.FIREFOX), p.rows.map { it.engine })
        assertEquals(
            RowStatus("opus-en-es-tcbig-2026.10", EngineId.OPUS, 238_524_992, installed = false, download = null),
            s[0].rows[0],
        )
        assertEquals("firefox-es-en-3.0", s[1].rows[1].modelId)
        assertEquals(37_032_325, s[1].rows[1].sizeBytes)
    }

    @Test fun `lo instalado y las descargas se marcan en su fila`() {
        val running = DownloadState(DownloadState.Status.RUNNING, 10, 100, null)
        val s = statuses(setOf("firefox" to "en-es"), mapOf("opus-es-en-tcbig-2026.10" to running))
        assertTrue(s[0].rows[1].installed)
        assertFalse(s[0].rows[0].installed)
        assertFalse(s[1].rows[1].installed)
        assertEquals(running, s[1].rows[0].download)
        assertNull(s[0].rows[0].download)
    }

    @Test fun `sin catalogo la lista esta vacia`() =
        assertEquals(emptyList(), HubRules.pairStatuses(null, setOf("opus" to "en-es"), emptyMap()))

    @Test fun `un motor desconocido del catalogo no aparece`() {
        val c = prod.copy(models = prod.models + model("nllb-en-es-1", "en-es", "nllb", 1))
        assertEquals(2, HubRules.pairStatuses(c, emptySet(), emptyMap())[0].rows.size)
    }

    @Test fun `se recomienda OPUS con 8 GiB y Firefox con 3 GiB`() {
        val enEs = statuses()[0]
        assertEquals(EngineId.OPUS, HubRules.recommended(enEs, 8 * gib)?.engine)
        assertEquals(EngineId.FIREFOX, HubRules.recommended(enEs, 3 * gib)?.engine)
    }

    @Test fun `la recomendacion no depende de lo instalado`() {
        val enEs = statuses(setOf("firefox" to "en-es"))[0]
        assertEquals(EngineId.OPUS, HubRules.recommended(enEs, 8 * gib)?.engine)
    }

    @Test fun `un par con un solo motor en el catalogo recomienda ese`() {
        val c = prod.copy(models = prod.models.filter { it.engine == "firefox" })
        val enEs = HubRules.pairStatuses(c, emptySet(), emptyMap())[0]
        assertEquals(EngineId.FIREFOX, HubRules.recommended(enEs, 8 * gib)?.engine)
    }

    @Test fun `en uso con solo Firefox instalado y automatico es Firefox`() {
        val enEs = statuses(setOf("firefox" to "en-es"))[0]
        assertEquals(EngineId.FIREFOX, HubRules.inUse(enEs, 8 * gib, EnginePreference.AUTO)?.engine)
    }

    @Test fun `en uso con calidad y OPUS sin instalar es null`() {
        val enEs = statuses(setOf("firefox" to "en-es"))[0]
        assertNull(HubRules.inUse(enEs, 8 * gib, EnginePreference.QUALITY))
    }

    @Test fun `en uso sin nada instalado es null`() =
        assertNull(HubRules.inUse(statuses()[0], 8 * gib, EnginePreference.AUTO))

    @Test fun `en uso con los dos instalados sigue la RAM o la preferencia`() {
        val enEs = statuses(setOf("firefox" to "en-es", "opus" to "en-es"))[0]
        assertEquals(EngineId.OPUS, HubRules.inUse(enEs, 8 * gib, EnginePreference.AUTO)?.engine)
        assertEquals(EngineId.FIREFOX, HubRules.inUse(enEs, 3 * gib, EnginePreference.AUTO)?.engine)
        assertEquals(EngineId.FIREFOX, HubRules.inUse(enEs, 8 * gib, EnginePreference.FAST)?.engine)
    }

    @Test fun `volver a descargar una fila instalada pide confirmacion`() {
        val s = statuses(setOf("opus" to "en-es"))[0]
        assertTrue(HubRules.needsConfirmToDownload(s.rows[0]))
        assertFalse(HubRules.needsConfirmToDownload(s.rows[1]))
    }

    @Test fun `pedir otra vez una descarga activa conserva su avance`() {
        fun state(s: DownloadState.Status) = DownloadState(s, 10, 100, null)
        assertTrue(HubRules.keepsCurrentDownload(state(DownloadState.Status.QUEUED)))
        assertTrue(HubRules.keepsCurrentDownload(state(DownloadState.Status.RUNNING)))
        assertFalse(HubRules.keepsCurrentDownload(null))
        assertFalse(HubRules.keepsCurrentDownload(state(DownloadState.Status.SUCCEEDED)))
        assertFalse(HubRules.keepsCurrentDownload(state(DownloadState.Status.FAILED)))
        assertFalse(HubRules.keepsCurrentDownload(state(DownloadState.Status.CANCELLED)))
    }

    // Movidas desde EnginePickerTest: el texto de la RAM ahora vive en HubRules.
    @Test fun `la RAM redondea 7,6 GiB a 8 GB`() {
        assertEquals(8L, HubRules.ramGb((7.6 * gib).toLong()))
        assertEquals(4L, HubRules.ramGb((3.9 * gib).toLong()))
    }

    @Test fun `el texto de RAM lleva decimal cerca del umbral de 4 GB`() {
        val es = Locale.forLanguageTag("es")
        assertEquals("3,6", HubRules.ramText((3.6 * gib).toLong(), es))
        assertEquals("7", HubRules.ramText((7.4 * gib).toLong(), es))
        assertEquals("8", HubRules.ramText((7.6 * gib).toLong(), es))
    }

    @Test fun `tras importar o borrar se olvidan los finales del par y motor, no las descargas activas ni otros`() {
        val failed = DownloadState(DownloadState.Status.FAILED, 0, 0, null)
        val done = DownloadState(DownloadState.Status.SUCCEEDED, 1, 1, null)
        val running = DownloadState(DownloadState.Status.RUNNING, 10, 100, null)
        val downloads = mapOf<String, DownloadState?>(
            "firefox-en-es-3.0" to failed,
            "opus-en-es-tcbig-2026.10" to done,
            "firefox-es-en-3.0" to failed,
        )
        val after = HubRules.withoutFinishedDownloads(prod, "firefox", "en-es", downloads)
        assertEquals(setOf("opus-en-es-tcbig-2026.10", "firefox-es-en-3.0"), after.keys)
        val active = HubRules.withoutFinishedDownloads(prod, "firefox", "en-es", mapOf("firefox-en-es-3.0" to running))
        assertEquals(mapOf<String, DownloadState?>("firefox-en-es-3.0" to running), active)
        assertEquals(downloads, HubRules.withoutFinishedDownloads(null, "firefox", "en-es", downloads))
    }
}
