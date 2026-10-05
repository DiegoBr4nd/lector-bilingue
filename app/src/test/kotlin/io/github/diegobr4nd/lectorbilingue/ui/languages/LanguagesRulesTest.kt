package io.github.diegobr4nd.lectorbilingue.ui.languages

import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.core.ui.components.ModelRowState
import io.github.diegobr4nd.lectorbilingue.data.EnginePreference
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.data.RowStatus
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.models.DownloadState
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LanguagesRulesTest {
    private val gib = 1024L * 1024 * 1024
    private val es = Locale.forLanguageTag("es")

    private fun pair(
        opusInstalled: Boolean = false,
        firefoxInstalled: Boolean = false,
        opusDownload: DownloadState? = null,
    ) = PairStatus(
        "en-es",
        listOf(
            RowStatus("opus-en-es", EngineId.OPUS, 238_524_992, opusInstalled, opusDownload),
            RowStatus("firefox-en-es", EngineId.FIREFOX, 36_594_513, firefoxInstalled, null),
        ),
    )

    private fun rows(p: PairStatus, ram: Long = 8 * gib, pref: EnginePreference = EnginePreference.AUTO) =
        LanguagesRules.rows(listOf(p), ram, pref).single().rows

    @Test
    fun la_fila_en_uso_es_InUse_y_la_otra_instalada_es_Installed() {
        val r = rows(pair(opusInstalled = true, firefoxInstalled = true))
        assertEquals(ModelRowState.InUse, r[0].state)
        assertEquals(EngineKind.QUALITY, r[0].kind)
        assertEquals(ModelRowState.Installed, r[1].state)
        assertEquals(EngineKind.FAST, r[1].kind)
    }

    @Test
    fun el_motor_forzado_cambia_cual_esta_en_uso() {
        val r = rows(pair(opusInstalled = true, firefoxInstalled = true), pref = EnginePreference.FAST)
        assertEquals(ModelRowState.Installed, r[0].state)
        assertEquals(ModelRowState.InUse, r[1].state)
    }

    @Test
    fun sin_instalar_es_NotInstalled_y_el_tamano_va_en_mb() {
        val r = rows(pair())
        assertEquals(ModelRowState.NotInstalled, r[0].state)
        assertEquals(227L, r[0].sizeMb)
        assertEquals("opus-en-es", r[0].modelId)
        assertEquals(EngineId.OPUS, r[0].engine)
    }

    @Test
    fun con_descarga_activa_es_Downloading_con_avance() {
        val running = DownloadState(DownloadState.Status.RUNNING, 50, 200, null)
        assertEquals(ModelRowState.Downloading(0.25f), rows(pair(opusDownload = running))[0].state)
        val queued = DownloadState(DownloadState.Status.QUEUED, 0, 0, null)
        assertEquals(ModelRowState.Downloading(null), rows(pair(opusDownload = queued))[0].state)
    }

    @Test
    fun un_fallo_de_descarga_se_avisa_con_mensaje_fijo_y_la_fila_vuelve_a_ofrecer_descargar() {
        val failed = DownloadState(DownloadState.Status.FAILED, 0, 0, "red")
        val card = LanguagesRules.rows(listOf(pair(opusDownload = failed)), 8 * gib, EnginePreference.AUTO).single()
        assertEquals(ModelRowState.NotInstalled, card.rows[0].state)
        assertEquals(ModelMessage.NETWORK, card.message)
        val ok = DownloadState(DownloadState.Status.SUCCEEDED, 1, 1, null)
        val done = LanguagesRules.rows(listOf(pair(opusInstalled = true, opusDownload = ok)), 8 * gib, EnginePreference.AUTO)
        assertNull(done.single().message)
    }

    @Test
    fun borrar_siempre_pide_confirmacion() {
        for (state in listOf(ModelRowState.NotInstalled, ModelRowState.Installed, ModelRowState.InUse)) {
            val row = UiRow("m", EngineKind.QUALITY, 227, state, EngineId.OPUS)
            assertNotNull(LanguagesRules.confirmFor(RowAction.DELETE, row))
        }
    }

    @Test
    fun descargar_solo_pide_confirmacion_si_ya_esta_instalado() {
        fun row(s: ModelRowState) = UiRow("m", EngineKind.QUALITY, 227, s, EngineId.OPUS)
        assertNull(LanguagesRules.confirmFor(RowAction.DOWNLOAD, row(ModelRowState.NotInstalled)))
        assertNotNull(LanguagesRules.confirmFor(RowAction.DOWNLOAD, row(ModelRowState.Installed)))
        assertNotNull(LanguagesRules.confirmFor(RowAction.DOWNLOAD, row(ModelRowState.InUse)))
        val c = LanguagesRules.confirmFor(RowAction.DELETE, row(ModelRowState.Installed))!!
        assertEquals(RowAction.DELETE, c.action)
        assertEquals(EngineKind.QUALITY, c.kind)
        assertEquals(227L, c.sizeMb)
    }

    @Test
    fun automatico_con_8_gib_usa_calidad() {
        val line = LanguagesRules.autoLine(8 * gib, es)
        assertEquals(EngineKind.QUALITY, line.kind)
        assertEquals("8", line.ramGb)
    }

    @Test
    fun automatico_con_3_6_gib_usa_rapido_y_dice_3_6() {
        val line = LanguagesRules.autoLine((3.6 * gib).toLong(), es)
        assertEquals(EngineKind.FAST, line.kind)
        assertEquals("3,6", line.ramGb)
    }
}
