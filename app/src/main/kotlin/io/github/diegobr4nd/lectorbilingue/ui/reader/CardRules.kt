package io.github.diegobr4nd.lectorbilingue.ui.reader

import io.github.diegobr4nd.lectorbilingue.data.ModelActions
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.data.Priority
import io.github.diegobr4nd.lectorbilingue.data.ReadingMode
import io.github.diegobr4nd.lectorbilingue.data.TranslationRules
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId

/** Estado de una tarjeta abierta. La pantalla lo convierte en [Card] con los textos de strings.xml. */
sealed interface CardState {
    /** "Traduciendo…": el motor ya está cargado. */
    data object Skeleton : CardState

    /** "Preparando el traductor…": el motor se carga con este pedido. */
    data object Preparing : CardState

    data class Text(val translation: String) : CardState

    /** Falta el modelo de [engine] para la dirección del libro: la tarjeta lleva a Idiomas. */
    data class MissingModel(val engine: EngineId) : CardState

    /** [prepare] = no se pudo preparar el motor; si no, falló este párrafo. Tocar la tarjeta reintenta. */
    data class Failed(val prepare: Boolean) : CardState

    /** El párrafo pasa del tope ([TranslationRules.MAX_PARAGRAPH_CHARS]): no se traduce ni se reintenta. */
    data object TooLong : CardState

    // Los tres siguientes solo los ve la página en Solo traducción (3c-2): el ViewModel guarda el estado de la
    // traducción (Text, Skeleton…) y [ModeRules.display] los arma según el modo.

    /** Solo traducción: la traducción en lugar del original, con la letra del libro. */
    data class Solo(val translation: String) : CardState

    /** Solo traducción, aún sin traducir: el original en gris con "Traduciendo…". */
    data object Waiting : CardState

    /** Solo traducción: la persona tocó para ver el original (marca "Original"); otro toque vuelve a [translation]. */
    data class ShowingOriginal(val translation: String) : CardState
}

/**
 * Lo que la pantalla hace en la página, en este orden: poner (o actualizar) la tarjeta del párrafo [index] de
 * [resource], quitarla, o cambiar el modo de la página. [anchor]: qué queda quieto en pantalla (Ruling F de la 3c-2):
 * [CardAnchor.SELF] en Tocar (la tarjeta justo bajo el párrafo tocado, como en la 3b), [CardAnchor.SCREEN] en
 * Intercalado y Solo traducción (lo que se está leyendo).
 */
sealed interface CardOp {
    data class Show(val resource: String, val index: Int, val card: CardState, val anchor: CardAnchor = CardAnchor.SELF) : CardOp
    data class Hide(val resource: String, val index: Int, val anchor: CardAnchor = CardAnchor.SELF) : CardOp

    /**
     * El modo cambió: la pantalla pone el modo de la página ([ModeRules.pageMode]) ANTES de las tarjetas que siguen,
     * que ya vienen adaptadas (una tarjeta Solo solo entra en una página en modo "solo").
     */
    data class Mode(val mode: ReadingMode) : CardOp

    /**
     * Salió el aviso del capítulo: la pantalla quita de la página de [resource] TODAS las tarjetas en espera
     * ("Traduciendo…", "Preparando…", el gris de Solo) y devuelve sus originales. Las ya traducidas se quedan.
     * Va después de los `Hide` de cada tarjeta: es la segunda barrera si alguno no llegó.
     */
    data class RemoveWaiting(val resource: String) : CardOp
}

/**
 * Aviso único arriba del capítulo en Intercalado y Solo traducción (en vez de un error por párrafo). Se quita con
 * "Reintentar" (`retryChapter`), al cerrar Idiomas, al cambiar de dirección o al volver a Tocar.
 */
sealed interface ChapterNotice {
    /** Falta el modelo de [engine] para la dirección del libro: "Falta el idioma … · Descargar". */
    data class MissingModel(val engine: EngineId) : ChapterNotice

    /** No se pudo preparar el motor: "No se pudo preparar el traductor · Reintentar". */
    data object EngineFailed : ChapterNotice
}

/**
 * Textos de las tarjetas (vienen de strings.xml). [missing] arma "Falta el idioma …" para el motor que falta y
 * [missingSpoken], lo mismo como lo oye TalkBack ("inglés a español, 227 megabytes. Descargar"); [retry] y [download]
 * son el enlace subrayado del final.
 */
class CardTexts(
    val paragraphFailed: String,
    val prepareFailed: String,
    val retry: String,
    val download: String,
    val tooLong: String,
    /** "Traduciendo…" bajo el original en gris (Solo traducción). */
    val waiting: String,
    /** "Original": lo que oye TalkBack al alternar al original en Solo traducción. */
    val original: String,
    val missing: (EngineId) -> String,
    val missingSpoken: (EngineId) -> String,
)

/** Reglas puras de las tarjetas del Lector. */
object CardRules {
    /** true = el toque abre la tarjeta del párrafo [index]; false = la cierra (ya estaba abierta, en cualquier estado). */
    fun toggle(open: Map<Int, CardState>, index: Int): Boolean = index !in open

    /** Un párrafo más largo no se pretraduce (ocuparía el motor mucho rato); se traduce si se toca. */
    const val MAX_PREFETCH_CHARS = 4_000

    /**
     * Qué pretraducir tras tocar `hit[0]`: los siguientes de [hit] cuyo texto normalizado no esté en [cachedOrOpen]
     * (ya traducidos o con tarjeta), sin vacíos, repetidos ni de más de [MAX_PREFETCH_CHARS] caracteres.
     */
    fun prefetchTargets(hit: List<PageParagraph>, cachedOrOpen: Set<String>): List<PageParagraph> {
        val seen = HashSet(cachedOrOpen)
        return hit.drop(1).filter { p ->
            val text = TranslationRules.normalize(p.text)
            !p.cut && text.isNotEmpty() && text.length <= MAX_PREFETCH_CHARS && seen.add(text)
        }
    }

    /** El estado de una tarjeta, ya con sus textos, como lo pone la página. */
    fun card(state: CardState, texts: CardTexts): Card = when (state) {
        CardState.Skeleton -> Card.Skeleton
        CardState.Preparing -> Card.Preparing
        is CardState.Text -> Card.Text(state.translation)
        is CardState.MissingModel -> Card.MissingModel(texts.missing(state.engine), texts.download, texts.missingSpoken(state.engine))
        is CardState.Failed -> Card.Failed(failedLabel(state, texts), texts.retry, spoken(state, texts)!!)
        CardState.TooLong -> Card.TooLong(texts.tooLong)
        is CardState.Solo -> Card.Solo(state.translation)
        CardState.Waiting -> Card.Waiting(texts.waiting)
        // La misma tarjeta Solo; la pantalla además muestra el original ([originalShown]).
        is CardState.ShowingOriginal -> Card.Solo(state.translation)
    }

    /**
     * Solo traducción: true si tras poner la tarjeta hay que mostrar el original (`showOriginal(i, true)`), false si
     * hay que volver a la traducción; null para cualquier otra tarjeta (no se llama a `showOriginal`).
     */
    fun originalShown(state: CardState): Boolean? = when (state) {
        is CardState.Solo -> false
        is CardState.ShowingOriginal -> true
        else -> null
    }

    /**
     * Lo que TalkBack dice cuando llega el resultado de un toque (Ruling L); null mientras traduce. Para el texto es solo
     * la traducción: la pantalla le antepone "Traducción:". Solo va al servicio de accesibilidad, nunca a registros.
     */
    fun spoken(state: CardState, texts: CardTexts): String? = when (state) {
        CardState.Skeleton, CardState.Preparing, CardState.Waiting -> null
        is CardState.Text -> state.translation
        is CardState.Solo -> state.translation
        is CardState.ShowingOriginal -> texts.original
        is CardState.MissingModel -> texts.missingSpoken(state.engine)
        is CardState.Failed -> "${failedLabel(state, texts)}. ${texts.retry}"
        CardState.TooLong -> texts.tooLong
    }

    private fun failedLabel(state: CardState.Failed, texts: CardTexts) =
        if (state.prepare) texts.prepareFailed else texts.paragraphFailed

    /** MB del modelo de [engine] para [pair] según el catálogo, o null si el catálogo aún no lo trae. */
    fun modelMegabytes(pairs: List<PairStatus>, pair: String, engine: EngineId): Long? =
        pairs.firstOrNull { it.pair == pair }?.rows?.firstOrNull { it.engine == engine }?.let { ModelActions.megabytes(it.sizeBytes) }
}

/** Qué hace un toque sobre un párrafo (o su tarjeta), según el modo: ver [ModeRules.tap]. */
enum class TapAction {
    /** Sin tarjeta: se abre y se pide su traducción (como en la 3b). */
    OPEN,

    /** Tocar: se cierra y se olvida. */
    CLOSE,

    /** Intercalado: oculta o vuelve a mostrar la traducción. Solo traducción: alterna traducción ↔ original. */
    TOGGLE,

    NOTHING,
}

/** Un párrafo que la guía de modo pide al servicio, con su prioridad. */
data class ModeRequest(val paragraph: PageParagraph, val priority: Priority)

/**
 * Qué hacer con los párrafos visibles: [tooLong] llevan su tarjeta "demasiado largo" (sin pedirlos), [cached] ya
 * tienen traducción guardada (salen al instante) y [requests] se piden en este orden. [more]: alguno quedó fuera por el
 * tope; se vuelve a planear cuando la fila del modo se vacía.
 */
data class ModePlan(val tooLong: List<PageParagraph>, val cached: List<PageParagraph>, val requests: List<ModeRequest>, val more: Boolean = false)

/** Reglas puras de los modos de lectura (3c-2, spec §5.3). */
object ModeRules {
    /** Cuántos párrafos de más abajo de la pantalla se piden a `visible()`. */
    const val FOLLOWING = 10

    /** Como máximo tantos pedidos del modo a la vez (en curso más en espera); lo que sobra, en la siguiente vuelta. */
    const val MAX_ACTIVE = 15

    /** Ruling F: Tocar fija el párrafo tocado; Intercalado y Solo traducción, lo que se lee en pantalla. */
    fun anchor(mode: ReadingMode): CardAnchor = if (mode == ReadingMode.TAP) CardAnchor.SELF else CardAnchor.SCREEN

    /** El modo de la página (`data-lector-modo`, uno de [ParagraphScripts.MODES]). */
    fun pageMode(mode: ReadingMode): String = when (mode) {
        ReadingMode.TAP -> "tocar"
        ReadingMode.INTERLEAVED -> "intercalado"
        ReadingMode.TRANSLATION_ONLY -> "solo"
    }

    /**
     * Lo que la página muestra para una tarjeta en estado [state] (Text, Skeleton…) en [mode]; null = sin tarjeta.
     * [toggled]: la persona la tocó (Intercalado: la ocultó; Solo traducción: está viendo el original).
     */
    fun display(mode: ReadingMode, state: CardState, toggled: Boolean): CardState? = when (mode) {
        ReadingMode.TAP -> state
        ReadingMode.INTERLEAVED -> if (toggled) null else state
        ReadingMode.TRANSLATION_ONLY -> when (state) {
            is CardState.Text -> if (toggled) CardState.ShowingOriginal(state.translation) else CardState.Solo(state.translation)
            CardState.Skeleton, CardState.Preparing -> CardState.Waiting
            else -> state // error del párrafo o demasiado largo: su tarjeta de la 3b, bajo el original
        }
    }

    /** Qué hace un toque en [mode] sobre un párrafo cuya tarjeta está en [state] (null = sin tarjeta). */
    fun tap(mode: ReadingMode, state: CardState?): TapAction = when {
        state == null -> TapAction.OPEN
        mode == ReadingMode.TAP -> TapAction.CLOSE
        mode == ReadingMode.INTERLEAVED -> TapAction.TOGGLE
        // Solo traducción: solo hay original que alternar si ya está traducido (en espera, el original ya se ve).
        state is CardState.Text -> TapAction.TOGGLE
        else -> TapAction.NOTHING
    }

    /**
     * El plan para los párrafos de `visible()` (de arriba abajo): los que tocan la pantalla ([PageParagraph.onScreen],
     * marca de la página) como [Priority.TAP] y los siguientes como [Priority.PREFETCH].
     *
     * - Sin repetir los que ya tienen tarjeta ([open]), salvo los que aún esperan en la fila del modo ([queued]):
     *   esos se conservan, en el orden nuevo.
     * - Vacíos fuera; recortados o de más del tope → [ModePlan.tooLong]; ya en [cached] (texto normalizado) →
     *   [ModePlan.cached]; un siguiente de más de [CardRules.MAX_PREFETCH_CHARS] espera a verse.
     * - Tope: [inFlight] + pedidos ≤ [MAX_ACTIVE]; si alguno quedó fuera por el tope, [ModePlan.more].
     */
    fun plan(paragraphs: List<PageParagraph>, open: Set<Int>, queued: Set<Int>, inFlight: Int, cached: Set<String>): ModePlan {
        val tooLong = ArrayList<PageParagraph>()
        val fromCache = ArrayList<PageParagraph>()
        val requests = ArrayList<ModeRequest>()
        var more = false
        for (p in paragraphs) {
            val text = TranslationRules.normalize(p.text)
            val waiting = p.index in queued
            if (text.isEmpty() || (p.index in open && !waiting)) continue
            val following = !p.onScreen
            when {
                !waiting && (p.cut || TranslationRules.tooLong(text)) -> tooLong += p
                following && text.length > CardRules.MAX_PREFETCH_CHARS -> Unit
                !waiting && text in cached -> fromCache += p
                inFlight + requests.size < MAX_ACTIVE ->
                    requests += ModeRequest(p, if (following) Priority.PREFETCH else Priority.TAP)
                else -> more = true
            }
        }
        return ModePlan(tooLong, fromCache, requests, more)
    }
}
