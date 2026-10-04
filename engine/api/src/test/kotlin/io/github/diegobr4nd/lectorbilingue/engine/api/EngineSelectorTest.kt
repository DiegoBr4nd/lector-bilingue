package io.github.diegobr4nd.lectorbilingue.engine.api

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineChoice.Missing
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineChoice.Use
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId.FIREFOX
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId.OPUS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineSelectorTest {
    private val gib = 1024L * 1024 * 1024
    private val both = setOf(OPUS, FIREFOX)

    @Test fun ochoGbConAmbosEligeOpus() = assertEquals(Use(OPUS, Reason.RAM), EngineSelector.choose(both, 8 * gib, null))
    @Test fun cuatroGbExactosEligeOpus() = assertEquals(Use(OPUS, Reason.RAM), EngineSelector.choose(both, 4 * gib, null))
    @Test fun unByteMenosDeCuatroEligeFirefox() = assertEquals(Use(FIREFOX, Reason.RAM), EngineSelector.choose(both, 4 * gib - 1, null))
    @Test fun tresGbEligeFirefox() = assertEquals(Use(FIREFOX, Reason.RAM), EngineSelector.choose(both, 3 * gib, null))
    @Test fun dosGbEligeFirefox() = assertEquals(Use(FIREFOX, Reason.RAM), EngineSelector.choose(both, 2 * gib, null))
    @Test fun soloOpusConPocaRamUsaOpus() = assertEquals(Use(OPUS, Reason.ONLY_INSTALLED), EngineSelector.choose(setOf(OPUS), 2 * gib, null))
    @Test fun soloFirefoxConMuchaRamUsaFirefox() = assertEquals(Use(FIREFOX, Reason.ONLY_INSTALLED), EngineSelector.choose(setOf(FIREFOX), 8 * gib, null))
    @Test fun ningunoConMuchaRamPideOpus() = assertEquals(Missing(OPUS), EngineSelector.choose(emptySet(), 8 * gib, null))
    @Test fun ningunoConPocaRamPideFirefox() = assertEquals(Missing(FIREFOX), EngineSelector.choose(emptySet(), 2 * gib, null))
    @Test fun forzadoInstaladoGana() = assertEquals(Use(FIREFOX, Reason.FORCED), EngineSelector.choose(both, 8 * gib, FIREFOX))
    @Test fun forzadoOpusConPocaRam() = assertEquals(Use(OPUS, Reason.FORCED), EngineSelector.choose(both, 2 * gib, OPUS))
    @Test fun forzadoSinInstalarPideEseMotor() = assertEquals(Missing(FIREFOX), EngineSelector.choose(setOf(OPUS), 8 * gib, FIREFOX))
    @Test fun wireIdaYVuelta() {
        assertEquals(OPUS, EngineId.fromWire("opus"))
        assertEquals(FIREFOX, EngineId.fromWire("firefox"))
        assertNull(EngineId.fromWire("Opus"))
        assertNull(EngineId.fromWire(""))
    }
}
