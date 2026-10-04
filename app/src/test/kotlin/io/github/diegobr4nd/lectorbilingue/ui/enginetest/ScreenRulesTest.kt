package io.github.diegobr4nd.lectorbilingue.ui.enginetest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenRulesTest {
    private val ready = EngineTestUiState(modelStatus = ModelStatus.READY)

    @Test fun `listo y libre no bloquea`() = assertNull(ScreenRules.lockReason(ready))

    @Test fun `descarga en curso`() =
        assertEquals(
            LockReason.DOWNLOADING,
            ScreenRules.lockReason(ready.copy(modelBusy = true, downloading = true)),
        )

    @Test fun `operacion de modelos sin descarga`() =
        assertEquals(LockReason.MODEL_BUSY, ScreenRules.lockReason(ready.copy(modelBusy = true)))

    @Test fun `cargando el modelo`() =
        assertEquals(LockReason.LOADING, ScreenRules.lockReason(ready.copy(modelStatus = ModelStatus.LOADING)))

    @Test fun `traduciendo y midiendo`() {
        assertEquals(LockReason.TRANSLATING, ScreenRules.lockReason(ready.copy(busy = true)))
        assertEquals(LockReason.MEASURING, ScreenRules.lockReason(ready.copy(busy = true, measuring = true)))
    }

    @Test fun `la descarga manda sobre la carga`() =
        assertEquals(
            LockReason.DOWNLOADING,
            ScreenRules.lockReason(ready.copy(modelStatus = ModelStatus.LOADING, modelBusy = true, downloading = true)),
        )

    @Test fun `no se trabaja mientras carga o hay operacion de modelos`() {
        assertTrue(ScreenRules.modelBlocksWork(ready.copy(modelStatus = ModelStatus.LOADING)))
        assertTrue(ScreenRules.modelBlocksWork(ready.copy(modelBusy = true)))
        assertFalse(ScreenRules.modelBlocksWork(ready))
    }

    @Test fun `aviso de Wi-Fi solo si falta algun modelo o se descarga`() {
        val installed = ModelRow("a", io.github.diegobr4nd.lectorbilingue.engine.api.EngineId.OPUS, PairChoice.EN_ES, 10, true)
        val missing = installed.copy(id = "b", installed = false)
        assertFalse(ScreenRules.showWifiHint(ready.copy(models = listOf(installed))))
        assertFalse(ScreenRules.showWifiHint(ready))
        assertTrue(ScreenRules.showWifiHint(ready.copy(models = listOf(installed, missing))))
        assertTrue(ScreenRules.showWifiHint(ready.copy(models = listOf(installed), downloading = true)))
    }

    @Test fun `la medicion en es a en depende de los textos en español`() {
        assertTrue(ScreenRules.benchAvailable(ready.copy(pair = PairChoice.EN_ES)))
        assertFalse(ScreenRules.benchAvailable(ready.copy(pair = PairChoice.ES_EN)))
        assertTrue(ScreenRules.benchAvailable(ready.copy(pair = PairChoice.ES_EN, spanishBenchTexts = true)))
    }

    @Test fun `motivos por los que Traducir esta desactivado`() {
        val withText = ready.copy(input = "Hello")
        assertNull(ScreenRules.translateBlock(withText))
        assertEquals(ActionBlock.EmptyInput, ScreenRules.translateBlock(ready))
        assertEquals(ActionBlock.NotReady, ScreenRules.translateBlock(withText.copy(modelStatus = ModelStatus.MISSING)))
        assertEquals(
            ActionBlock.Lock(LockReason.LOADING),
            ScreenRules.translateBlock(withText.copy(modelStatus = ModelStatus.LOADING)),
        )
        assertEquals(ActionBlock.Lock(LockReason.TRANSLATING), ScreenRules.translateBlock(withText.copy(busy = true)))
    }

    @Test fun `motivos por los que Medir esta desactivado`() {
        assertNull(ScreenRules.measureBlock(ready))
        assertEquals(ActionBlock.NotReady, ScreenRules.measureBlock(ready.copy(modelStatus = ModelStatus.ERROR)))
        assertEquals(
            ActionBlock.Lock(LockReason.MEASURING),
            ScreenRules.measureBlock(ready.copy(busy = true, measuring = true)),
        )
    }
}
