package io.github.diegobr4nd.lectorbilingue.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cuándo se puede cargar un motor según la memoria que informa Android (números medidos en el Pixel 7, en MB). */
class EngineMemoryTest {
    private val mb = 1024L * 1024

    // Lo que vio Juan: 376 y 420 MB libres con muchas apps en segundo plano. La carga real de OPUS funcionó con
    // 420 MB libres (Android cierra apps de fondo para hacer sitio); la regla vieja de 450 MB la negaba.
    @Test fun conPocaMemoriaLibrePeroSinAvisoDelSistemaSeCarga() {
        assertTrue(EngineMemory.canLoad(availBytes = 376 * mb, thresholdBytes = 216 * mb, lowMemory = false))
        assertTrue(EngineMemory.canLoad(availBytes = 420 * mb, thresholdBytes = 216 * mb, lowMemory = false))
    }

    @Test fun conElSistemaEnMemoriaBajaNoSeCarga() {
        assertFalse(EngineMemory.canLoad(availBytes = 188 * mb, thresholdBytes = 216 * mb, lowMemory = true))
    }

    @Test fun enElUmbralOPorDebajoNoSeCargaAunqueElAvisoNoHayaLlegado() {
        assertFalse(EngineMemory.canLoad(availBytes = 216 * mb, thresholdBytes = 216 * mb, lowMemory = false))
        assertFalse(EngineMemory.canLoad(availBytes = 100 * mb, thresholdBytes = 216 * mb, lowMemory = false))
    }
}
