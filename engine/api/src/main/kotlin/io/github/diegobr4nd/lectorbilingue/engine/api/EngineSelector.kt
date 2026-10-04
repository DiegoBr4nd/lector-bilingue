package io.github.diegobr4nd.lectorbilingue.engine.api

/** Qué motor usar para un par. El motivo es un enum: la interfaz lo traduce. */
sealed interface EngineChoice {
    data class Use(val engine: EngineId, val reason: Reason) : EngineChoice
    /** No hay modelo instalado; [engine] es el que conviene descargar. */
    data class Missing(val engine: EngineId) : EngineChoice
}

enum class Reason { FORCED, RAM, ONLY_INSTALLED }

/**
 * Regla de la fase 2c (decisión de Juan): con RAM total ≥ 4 GiB, OPUS; si no, Firefox.
 * Si solo hay un motor instalado para el par, se usa ese. [forced] (interruptor de prueba)
 * manda si ese motor está instalado; si no, se pide ese motor. Nunca cambia de motor en silencio.
 */
object EngineSelector {
    const val OPUS_MIN_RAM_BYTES: Long = 4L * 1024 * 1024 * 1024

    fun choose(installed: Set<EngineId>, totalRamBytes: Long, forced: EngineId?): EngineChoice {
        if (forced != null) {
            return if (forced in installed) EngineChoice.Use(forced, Reason.FORCED) else EngineChoice.Missing(forced)
        }
        val preferred = if (totalRamBytes >= OPUS_MIN_RAM_BYTES) EngineId.OPUS else EngineId.FIREFOX
        return when {
            preferred in installed -> EngineChoice.Use(preferred, Reason.RAM)
            installed.size == 1 -> EngineChoice.Use(installed.single(), Reason.ONLY_INSTALLED)
            else -> EngineChoice.Missing(preferred)
        }
    }
}
