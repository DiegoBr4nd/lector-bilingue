package io.github.diegobr4nd.lectorbilingue.ui.home

import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.data.EnginePreference
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.data.RowStatus
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.models.DownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeRulesTest {
    private val gib = 1024L * 1024 * 1024

    private fun pair(
        name: String = "en-es",
        opusInstalled: Boolean = false,
        opusDownload: DownloadState? = null,
        firefoxInstalled: Boolean = false,
    ) = PairStatus(
        name,
        listOf(
            RowStatus("opus-$name", EngineId.OPUS, 238_000_000, opusInstalled, opusDownload),
            RowStatus("firefox-$name", EngineId.FIREFOX, 36_000_000, firefoxInstalled, null),
        ),
    )

    @Test
    fun sin_modelos_muestra_aun_no_tienes_idiomas() {
        val state = HomeRules.cards(listOf(pair(), pair("es-en")), 8 * gib, EnginePreference.AUTO)
        assertTrue(state.showNoLanguages)
        assertTrue(state.pairCards.isEmpty())
        assertTrue(HomeRules.cards(emptyList(), 8 * gib, EnginePreference.AUTO).showNoLanguages)
    }

    @Test
    fun opus_instalado_con_automatico_y_8_gib_es_una_tarjeta_calidad() {
        val state = HomeRules.cards(listOf(pair(opusInstalled = true), pair("es-en")), 8 * gib, EnginePreference.AUTO)
        assertFalse(state.showNoLanguages)
        assertEquals(1, state.pairCards.size)
        assertEquals("en-es", state.pairCards[0].pair)
        assertEquals(EngineKind.QUALITY, state.pairCards[0].engine)
        assertTrue(state.pairCards[0].downloads.isEmpty())
        assertNull(state.pairCards[0].missing)
    }

    @Test
    fun una_descarga_activa_lleva_su_estado_y_no_dice_listo() {
        val running = DownloadState(DownloadState.Status.RUNNING, 10, 100, null)
        val state = HomeRules.cards(listOf(pair(opusDownload = running)), 8 * gib, EnginePreference.AUTO)
        assertEquals(1, state.pairCards.size)
        assertEquals(running, state.pairCards[0].downloads.single().state)
        assertEquals("opus-en-es", state.pairCards[0].downloads.single().modelId)
        assertNull(state.pairCards[0].engine)
    }

    @Test
    fun una_descarga_fallida_sin_instalar_no_crea_tarjeta() {
        val failed = DownloadState(DownloadState.Status.FAILED, 0, 0, "red")
        assertTrue(HomeRules.cards(listOf(pair(opusDownload = failed)), 8 * gib, EnginePreference.AUTO).showNoLanguages)
    }

    @Test
    fun con_motor_forzado_ausente_avisa_que_falta_y_no_usa_otro() {
        val state = HomeRules.cards(listOf(pair(firefoxInstalled = true)), 8 * gib, EnginePreference.QUALITY)
        val card = state.pairCards.single()
        assertNull(card.engine)
        assertEquals(EngineKind.QUALITY, card.missing)
    }

    @Test
    fun si_el_motor_forzado_ausente_se_esta_descargando_no_dice_que_falta() {
        val running = DownloadState(DownloadState.Status.RUNNING, 10, 100, null)
        val state = HomeRules.cards(
            listOf(pair(opusDownload = running, firefoxInstalled = true)), 8 * gib, EnginePreference.QUALITY,
        )
        assertNull(state.pairCards.single().missing)
    }

    @Test
    fun muestra_todas_las_descargas_del_par() {
        val running = DownloadState(DownloadState.Status.RUNNING, 10, 100, null)
        val p = PairStatus(
            "en-es",
            listOf(
                RowStatus("opus-en-es", EngineId.OPUS, 1, false, running),
                RowStatus("firefox-en-es", EngineId.FIREFOX, 1, false, running),
            ),
        )
        assertEquals(2, HomeRules.cards(listOf(p), 8 * gib, EnginePreference.AUTO).pairCards.single().downloads.size)
    }

    @Test
    fun poca_ram_en_automatico_elige_rapido() {
        val state = HomeRules.cards(
            listOf(pair(opusInstalled = true, firefoxInstalled = true)), 3 * gib, EnginePreference.AUTO,
        )
        assertEquals(EngineKind.FAST, state.pairCards.single().engine)
    }
}
