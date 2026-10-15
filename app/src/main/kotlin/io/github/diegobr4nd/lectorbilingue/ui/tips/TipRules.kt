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
        TipId.LIBRARY_HOWTO -> false
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
        TipId.LIBRARY_HOWTO -> false // se muestra en la Biblioteca, no en el Lector
    }
}

/**
 * Cuándo el final de un capítulo cuenta para "Sigue deslizando…" (m5). Solo si se llegó leyendo: en este capítulo hubo
 * antes una posición medida lejos del final. Así no cuenta caer en el final del capítulo anterior con un paso hacia
 * atrás (Readium puede dar varias posiciones ya en el final), ni abrir el libro ya en el final. La primera posición de
 * cada capítulo ni se mide (ahorra un script).
 */
class ChapterEdgeWatch {
    private var href: String? = null
    private var awayFromEnd = false

    /** Nueva posición en [href]: ¿hay que medir los bordes? */
    fun onPosition(href: String): Boolean {
        if (href == this.href) return true
        this.href = href
        awayFromEnd = false
        return false
    }

    /** Lo medido en [href] con la página quieta: true → se llegó al final leyendo. */
    fun onMeasured(href: String, atBottom: Boolean): Boolean {
        if (href != this.href) return false
        if (!atBottom) awayFromEnd = true
        return atBottom && awayFromEnd
    }
}
