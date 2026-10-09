package io.github.diegobr4nd.lectorbilingue.ui.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * Puente con la página del libro: lee el párrafo tocado y pone o quita tarjetas de traducción, con los scripts de
 * [ParagraphScripts]. Solo ve el recurso visible (el capítulo vecino que el ViewPager tiene cargado no cuenta).
 * Si el Lector aún no tiene página, no hace nada.
 *
 * [navigator] se pide en cada llamada porque el fragmento de Readium puede crearse o recrearse después.
 */
class ParagraphBridge(private val navigator: () -> EpubNavigatorFragment?) {
    /** Párrafo bajo el punto tocado (en px del aparato, como llega a `onTap`) y los siguientes. Punto no finito → vacío. */
    suspend fun paragraphsAt(xPx: Float, yPx: Float, density: Float): List<PageParagraph> {
        val (x, y) = ParagraphScripts.cssPoint(xPx, yPx, density) ?: return emptyList()
        return ParagraphScripts.parseFind(run(ParagraphScripts.find(x, y)))
    }

    /** Índice del párrafo o de la tarjeta bajo el punto (px del aparato), o null (también si el punto no es finito). */
    suspend fun indexAt(xPx: Float, yPx: Float, density: Float): Int? {
        val (x, y) = ParagraphScripts.cssPoint(xPx, yPx, density) ?: return null
        return ParagraphScripts.parseIndex(run(ParagraphScripts.indexAt(x, y)))
    }

    suspend fun show(index: Int, card: Card, labels: CardLabels) {
        run(ParagraphScripts.insert(index, card, labels))
    }

    suspend fun hide(index: Int) {
        run(ParagraphScripts.remove(index))
    }

    /**
     * Si la página visible ya cargó y es [resource], quita todas las tarjetas y responde true; si no (o aún no hay
     * página), false.
     */
    suspend fun hideAll(resource: String): Boolean = ParagraphScripts.parseReady(run(ParagraphScripts.removeAll(resource)))

    // El WebView solo se usa en el hilo principal.
    private suspend fun run(script: String): String? = withContext(Dispatchers.Main.immediate) {
        navigator()?.evaluateJavascript(script)
    }
}
