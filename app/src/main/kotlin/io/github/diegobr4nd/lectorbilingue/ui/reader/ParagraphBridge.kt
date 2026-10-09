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
    /** Párrafo bajo el punto tocado (en px del aparato, como llega a `onTap`) y los siguientes. */
    suspend fun paragraphsAt(xPx: Float, yPx: Float, density: Float): List<PageParagraph> =
        ParagraphScripts.parseFind(run(ParagraphScripts.find(xPx.toDouble() / density, yPx.toDouble() / density)))

    /** Índice del párrafo o de la tarjeta bajo el punto (px del aparato), o null. */
    suspend fun indexAt(xPx: Float, yPx: Float, density: Float): Int? =
        ParagraphScripts.parseIndex(run(ParagraphScripts.indexAt(xPx.toDouble() / density, yPx.toDouble() / density)))

    suspend fun show(index: Int, card: Card, labels: CardLabels) {
        run(ParagraphScripts.insert(index, card, labels))
    }

    suspend fun hide(index: Int) {
        run(ParagraphScripts.remove(index))
    }

    // El WebView solo se usa en el hilo principal.
    private suspend fun run(script: String): String? = withContext(Dispatchers.Main.immediate) {
        navigator()?.evaluateJavascript(script)
    }
}
