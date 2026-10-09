package io.github.diegobr4nd.lectorbilingue.ui.reader

import io.github.diegobr4nd.lectorbilingue.data.ModelActions
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
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
}

/** Lo que la pantalla hace en la página: poner (o actualizar) la tarjeta del párrafo [index] de [resource], o quitarla. */
sealed interface CardOp {
    data class Show(val resource: String, val index: Int, val card: CardState) : CardOp
    data class Hide(val resource: String, val index: Int) : CardOp
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
    }

    /**
     * Lo que TalkBack dice cuando llega el resultado de un toque (Ruling L); null mientras traduce. Para el texto es solo
     * la traducción: la pantalla le antepone "Traducción:". Solo va al servicio de accesibilidad, nunca a registros.
     */
    fun spoken(state: CardState, texts: CardTexts): String? = when (state) {
        CardState.Skeleton, CardState.Preparing -> null
        is CardState.Text -> state.translation
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
