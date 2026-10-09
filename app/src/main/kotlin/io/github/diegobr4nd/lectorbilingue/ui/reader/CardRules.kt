package io.github.diegobr4nd.lectorbilingue.ui.reader

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
}

/** Lo que la pantalla hace en la página: poner (o actualizar) la tarjeta del párrafo [index] de [resource], o quitarla. */
sealed interface CardOp {
    data class Show(val resource: String, val index: Int, val card: CardState) : CardOp
    data class Hide(val resource: String, val index: Int) : CardOp
}

/** Reglas puras de las tarjetas del Lector. */
object CardRules {
    /** true = el toque abre la tarjeta del párrafo [index]; false = la cierra (ya estaba abierta, en cualquier estado). */
    fun toggle(open: Map<Int, CardState>, index: Int): Boolean = index !in open

    /**
     * Qué pretraducir tras tocar `hit[0]`: los siguientes de [hit] cuyo texto normalizado no esté en [cachedOrOpen]
     * (ya traducidos o con tarjeta), sin vacíos ni repetidos.
     */
    fun prefetchTargets(hit: List<PageParagraph>, cachedOrOpen: Set<String>): List<PageParagraph> {
        val seen = HashSet(cachedOrOpen)
        return hit.drop(1).filter { p ->
            val text = TranslationRules.normalize(p.text)
            text.isNotEmpty() && seen.add(text)
        }
    }
}
