package io.github.diegobr4nd.lectorbilingue.engine.api

/**
 * Ajustes del motor.
 * threads: nunca más que los núcleos rápidos; en el Pixel 7, 8 hilos es 4 veces más lento que 4.
 */
data class EngineConfig(
    val beamSize: Int = 1,
    val threads: Int = 4,
) {
    init {
        require(beamSize >= 1) { "beamSize debe ser >= 1" }
        require(threads >= 1) { "threads debe ser >= 1" }
    }
}
