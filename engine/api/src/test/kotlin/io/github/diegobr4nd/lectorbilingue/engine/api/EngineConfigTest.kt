package io.github.diegobr4nd.lectorbilingue.engine.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EngineConfigTest {
    @Test
    fun `por defecto usa beam 1 y 4 hilos`() {
        val config = EngineConfig()
        assertEquals(1, config.beamSize)
        assertEquals(4, config.threads)
    }

    @Test
    fun `rechaza cero hilos`() {
        assertFailsWith<IllegalArgumentException> { EngineConfig(threads = 0) }
    }

    @Test
    fun `rechaza beam cero`() {
        assertFailsWith<IllegalArgumentException> { EngineConfig(beamSize = 0) }
    }
}
