package io.github.diegobr4nd.lectorbilingue.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationGateTest {
    @Test
    fun deja_pasar_como_mucho_uno_por_segundo_la_primera_y_la_final() {
        var now = 0L
        val gate = NotificationGate(nanoClock = { now })
        val allowed = mutableListOf<Long>()
        for (i in 0..40) { // 4 avisos por segundo durante 10 s
            now = i * 250_000_000L
            val bytes = if (i == 40) 1000L else i * 20L
            if (gate.shouldUpdate(bytes, 1000)) allowed += bytes
        }
        assertEquals(0L, allowed.first())
        assertEquals(1000L, allowed.last())
        assertEquals(11, allowed.size) // 0 s, 1 s, ... 10 s (el 10 s coincide con el final)
    }

    @Test
    fun el_final_pasa_aunque_acabe_de_salir_otro() {
        var now = 0L
        val gate = NotificationGate(nanoClock = { now })
        assertTrue(gate.shouldUpdate(10, 100))
        now = 1_000_000
        assertFalse(gate.shouldUpdate(50, 100))
        assertTrue(gate.shouldUpdate(100, 100))
    }
}
