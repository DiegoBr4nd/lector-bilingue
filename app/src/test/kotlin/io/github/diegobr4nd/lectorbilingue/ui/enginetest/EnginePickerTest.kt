package io.github.diegobr4nd.lectorbilingue.ui.enginetest

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnginePickerTest {
    private val gib = 1024L * 1024 * 1024
    private val both = setOf(EngineId.OPUS, EngineId.FIREFOX)

    @Test fun `automatico con 8 GB y los dos motores usa OPUS por RAM`() =
        assertEquals(EnginePlan.Load(EngineId.OPUS, Reason.RAM), EnginePicker.plan(EngineSwitch.AUTO, both, 8 * gib))

    @Test fun `RAM 3,9 GB usa Firefox`() =
        assertEquals(
            EnginePlan.Load(EngineId.FIREFOX, Reason.RAM),
            EnginePicker.plan(EngineSwitch.AUTO, both, (3.9 * gib).toLong()),
        )

    @Test fun `forzado Firefox sin instalar pide descargar Firefox`() =
        assertEquals(
            EnginePlan.Download(EngineId.FIREFOX),
            EnginePicker.plan(EngineSwitch.FIREFOX, setOf(EngineId.OPUS), 8 * gib),
        )

    @Test fun `forzado instalado carga ese motor por eleccion`() =
        assertEquals(EnginePlan.Load(EngineId.OPUS, Reason.FORCED), EnginePicker.plan(EngineSwitch.OPUS, both, 2 * gib))

    @Test fun `error al cargar OPUS en automatico con Firefox instalado ofrece Firefox`() =
        assertEquals(EngineId.FIREFOX, EnginePicker.fallbackOffer(EngineSwitch.AUTO, EngineId.OPUS, both))

    @Test fun `error al cargar en modo forzado no ofrece nada`() {
        assertNull(EnginePicker.fallbackOffer(EngineSwitch.OPUS, EngineId.OPUS, both))
        assertNull(EnginePicker.fallbackOffer(EngineSwitch.FIREFOX, EngineId.FIREFOX, both))
    }

    @Test fun `error en automatico sin otro motor instalado no ofrece nada`() =
        assertNull(EnginePicker.fallbackOffer(EngineSwitch.AUTO, EngineId.OPUS, setOf(EngineId.OPUS)))

    @Test fun `la RAM redondea 7,6 GiB a 8 GB`() {
        assertEquals(8L, EnginePicker.ramGb((7.6 * gib).toLong()))
        assertEquals(4L, EnginePicker.ramGb((3.9 * gib).toLong()))
    }

    @Test fun `los pares tienen su carpeta y su texto`() {
        assertEquals("en-es", PairChoice.EN_ES.wire)
        assertEquals("es-en", PairChoice.ES_EN.wire)
    }
}
