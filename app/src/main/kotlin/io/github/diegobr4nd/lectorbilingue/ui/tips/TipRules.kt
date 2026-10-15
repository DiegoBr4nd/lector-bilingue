package io.github.diegobr4nd.lectorbilingue.ui.tips

import io.github.diegobr4nd.lectorbilingue.data.ReadingMode
import io.github.diegobr4nd.lectorbilingue.data.TipId

/**
 * Lo que las reglas necesitan saber del Lector.
 * [seenLoaded]: los vistos ya se leyeron del disco (antes no sale nada, así no parpadea un consejo ya visto).
 * [pageReady]: hay página del libro. [blocked]: hay una hoja, "Idiomas", "Cómo se usa" o la banda del aviso.
 * [translatedByTap]: en esta sesión un toque mostró una traducción. [reachedChapterEnd]: la lectura llegó al final de
 * un capítulo que no es el último.
 */
data class TipState(
    val seen: Set<TipId>,
    val seenLoaded: Boolean,
    val pageReady: Boolean,
    val blocked: Boolean,
    val mode: ReadingMode,
    val sourceIsSpanish: Boolean,
    val translatedByTap: Boolean,
    val reachedChapterEnd: Boolean,
)

/** Lo que hace la persona y puede cerrar el consejo en pantalla. */
enum class TipEvent { TAPPED, CARD_HIDDEN, SETTINGS_OPENED, DIRECTION_OPENED, CHAPTER_CHANGED }

/** Reglas puras de los consejos (spec 3c-3 §3): uno a la vez, una sola vez cada uno. */
object TipRules {
    /**
     * El consejo que se ve ahora, o null. [current] es el que ya estaba en pantalla: se queda mientras siga elegible y
     * sin ver (otro que se vuelva elegible después espera su turno).
     */
    fun next(state: TipState, current: TipId?): TipId? {
        if (!state.seenLoaded || !state.pageReady || state.blocked) return null
        if (current != null && current !in state.seen && eligible(current, state)) return current
        return TipId.entries.firstOrNull { it !in state.seen && eligible(it, state) }
    }

    /** ¿[event] cierra (y marca visto) a [tip]? Solo cuenta para el consejo que está en pantalla. */
    fun dismissedBy(tip: TipId, event: TipEvent): Boolean = when (tip) {
        TipId.DIRECTION -> event == TipEvent.DIRECTION_OPENED
        TipId.TAP -> event == TipEvent.TAPPED
        TipId.HIDE_AND_MODES -> event == TipEvent.CARD_HIDDEN || event == TipEvent.SETTINGS_OPENED
        TipId.CHAPTER_EDGE -> event == TipEvent.CHAPTER_CHANGED
    }

    /**
     * ¿Se dibuja la tarjeta de [tip]? Se esconde con las barras, salvo la del borde: sale justo cuando se lee hacia
     * adelante (barras ocultas) y es el momento en que sirve (Ruling D).
     */
    fun cardVisible(tip: TipId?, barsVisible: Boolean): Boolean = tip != null && (barsVisible || tip == TipId.CHAPTER_EDGE)

    private fun eligible(tip: TipId, s: TipState): Boolean = when (tip) {
        TipId.DIRECTION -> s.sourceIsSpanish
        TipId.TAP -> s.mode == ReadingMode.TAP && !s.translatedByTap
        TipId.HIDE_AND_MODES -> s.mode == ReadingMode.TAP && s.translatedByTap
        TipId.CHAPTER_EDGE -> s.reachedChapterEnd
    }
}
