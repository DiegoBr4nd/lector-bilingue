package io.github.diegobr4nd.lectorbilingue.ui.enginetest

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineChoice
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineSelector
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.api.Reason
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

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
    private const val GIB = 1024.0 * 1024 * 1024

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

    /** RAM total en GB redondeados (7,6 GiB se muestra como 8). */
    fun ramGb(totalRamBytes: Long): Long = (totalRamBytes / GIB).roundToLong()

    /**
     * Texto de la RAM para la pantalla: GB enteros, salvo a ±0,5 GB del umbral de 4 GiB, donde lleva un decimal
     * (así "3,6" nunca se lee como "4" junto a "Firefox por RAM").
     */
    fun ramText(totalRamBytes: Long, locale: Locale = Locale.getDefault()): String {
        val gib = totalRamBytes / GIB
        val threshold = EngineSelector.OPUS_MIN_RAM_BYTES / GIB
        return if (abs(gib - threshold) <= 0.5) String.format(locale, "%.1f", gib) else ramGb(totalRamBytes).toString()
    }
}
