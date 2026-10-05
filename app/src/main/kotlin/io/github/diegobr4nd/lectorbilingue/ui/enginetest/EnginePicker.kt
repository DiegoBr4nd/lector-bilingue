package io.github.diegobr4nd.lectorbilingue.ui.enginetest

import io.github.diegobr4nd.lectorbilingue.data.HubRules
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineChoice
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineSelector
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.api.Reason

/** Interruptor de prueba: Automático deja decidir a [EngineSelector]; los otros fuerzan un motor. */
enum class EngineSwitch(val forced: EngineId?) {
    AUTO(null), OPUS(EngineId.OPUS), FIREFOX(EngineId.FIREFOX);

    companion object {
        /** El interruptor que fuerza [engine]. */
        fun of(engine: EngineId): EngineSwitch = entries.first { it.forced == engine }
    }
}

/** Los dos pares de la pantalla. [wire] es el nombre en el catálogo y en las carpetas. */
enum class PairChoice(val source: String, val target: String) {
    EN_ES("en", "es"), ES_EN("es", "en");

    val pair: LanguagePair get() = LanguagePair(source, target)
    val wire: String get() = "$source-$target"
}

/** Qué hacer con el interruptor y lo instalado: cargar un motor, o pedir que se descargue su modelo. */
sealed interface EnginePlan {
    data class Load(val engine: EngineId, val reason: Reason) : EnginePlan
    data class Download(val engine: EngineId) : EnginePlan
}

/** Lógica pura (sin Android) de la pantalla de prueba: se prueba en la JVM. */
object EnginePicker {
    fun plan(switch: EngineSwitch, installed: Set<EngineId>, totalRamBytes: Long): EnginePlan =
        when (val c = EngineSelector.choose(installed, totalRamBytes, switch.forced)) {
            is EngineChoice.Use -> EnginePlan.Load(c.engine, c.reason)
            is EngineChoice.Missing -> EnginePlan.Download(c.engine)
        }

    /**
     * Tras fallar la carga de [failed]: el otro motor que se puede ofrecer con un botón "Usar ...".
     * Solo en Automático; con un motor forzado solo se muestra el error. Nunca cambia solo.
     */
    fun fallbackOffer(switch: EngineSwitch, failed: EngineId, installed: Set<EngineId>): EngineId? =
        if (switch == EngineSwitch.AUTO) installed.firstOrNull { it != failed } else null

    /** Texto de la RAM para la pantalla; la regla vive en [HubRules.ramText] (la comparten las pantallas nuevas). */
    fun ramText(totalRamBytes: Long): String = HubRules.ramText(totalRamBytes)
}
