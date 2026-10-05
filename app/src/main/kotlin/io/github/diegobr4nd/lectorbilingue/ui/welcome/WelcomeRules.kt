package io.github.diegobr4nd.lectorbilingue.ui.welcome

import io.github.diegobr4nd.lectorbilingue.data.HubRules
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.data.RowStatus

/** Reglas puras (sin Android) del tercer paso de la Bienvenida: se prueban en la JVM. */
object WelcomeRules {
    /** Una opción de idioma: [recommended] es el modelo que conviene a este teléfono. */
    data class PairOption(val pair: String, val recommended: RowStatus, val selectedByDefault: Boolean)

    /** [message] explica por qué no hay opciones (o un aviso del catálogo); `null` si no hay nada que avisar. */
    data class Step3(val options: List<PairOption>, val canDownload: Boolean, val message: ModelMessage?)

    /** Inglés a español viene elegido; español a inglés es opcional. */
    private const val DEFAULT_PAIR = "en-es"

    fun step3(pairs: List<PairStatus>, catalogMessage: ModelMessage?, totalRam: Long): Step3 {
        if (pairs.isEmpty() || catalogMessage == ModelMessage.NO_CATALOG) {
            return Step3(emptyList(), canDownload = false, message = catalogMessage ?: ModelMessage.NO_CATALOG)
        }
        val options = pairs.mapNotNull { status ->
            HubRules.recommended(status, totalRam)?.let { PairOption(status.pair, it, status.pair == DEFAULT_PAIR) }
        }
        return Step3(options, canDownload = options.any { !it.recommended.installed }, message = catalogMessage)
    }

    /** Bytes por descargar: solo las elegidas y las que aún no están instaladas. */
    fun downloadBytes(selected: List<PairOption>): Long =
        selected.filter { !it.recommended.installed }.sumOf { it.recommended.sizeBytes }
}
