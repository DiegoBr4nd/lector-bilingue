package io.github.diegobr4nd.lectorbilingue.ui.reader

import io.github.diegobr4nd.lectorbilingue.books.BookFiles

/** Texto de la barra inferior: título del capítulo (si tiene) y % leído (si Readium ya lo sabe). */
data class PositionLabel(val chapter: String?, val percent: Int?)

data class TocEntry(val title: String, val depth: Int, val href: String)
data class TocEntrySource(val title: String?, val href: String, val children: List<TocEntrySource>)

/** Reglas puras del Lector (sin Android): se prueban en la JVM. */
object ReaderRules {
    /**
     * ¿Se ven las barras? Las mueve el gesto, no la posición: Readium avisa la posición unos 180 ms después de
     * soltar el dedo, y las barras deben reaccionar mientras se desliza.
     *
     * [dragDy] es `DragEvent.offset.y` de Readium: el desplazamiento del dedo desde que empezó el gesto, en
     * píxeles. **Negativo** = el dedo sube = el texto avanza (se lee hacia adelante) → se ocultan.
     * **Positivo** = el dedo baja = se vuelve atrás → aparecen. Comprobado en el Pixel 7 (Readium 3.4.0).
     *
     * Al final del libro se ven siempre. Con TalkBack también: quien no ve la pantalla no puede "deslizar para
     * que aparezcan".
     */
    fun barsVisible(dragDy: Double?, atEnd: Boolean, wasVisible: Boolean, touchExploration: Boolean): Boolean = when {
        touchExploration -> true
        atEnd -> true
        dragDy == null || dragDy == 0.0 || dragDy.isNaN() -> wasVisible
        dragDy < 0 -> false
        else -> true
    }

    /** Final del libro: Readium da 1.0 o casi (redondeo de la última posición). */
    fun atEnd(totalProgression: Double?): Boolean = totalProgression != null && totalProgression >= 0.999

    fun label(chapterTitle: String?, totalProgression: Double?): PositionLabel = PositionLabel(
        chapterTitle?.trim()?.takeIf { it.isNotEmpty() },
        totalProgression?.takeIf { !it.isNaN() }?.let { (it.coerceIn(0.0, 1.0) * 100).toInt() },
    )

    /** Índice en una lista con sangría. Un capítulo sin título muestra su archivo para no quedar en blanco. */
    fun flattenToc(links: List<TocEntrySource>, depth: Int = 0): List<TocEntry> = links.flatMap { l ->
        listOf(TocEntry(l.title?.trim()?.takeIf { it.isNotEmpty() } ?: l.href, depth, l.href)) + flattenToc(l.children, depth + 1)
    }

    /** Entrada del índice del capítulo que se está leyendo: la primera con el mismo archivo (sin `#fragmento`). */
    fun currentTocIndex(entries: List<TocEntry>, locatorHref: String?): Int? {
        val current = locatorHref?.substringBefore('#') ?: return null
        return entries.indexOfFirst { it.href.substringBefore('#') == current }.takeIf { it >= 0 }
    }

    /** Un enlace del libro solo puede salir a la web o al correo. Todo lo demás (intent:, file:, javascript:…) se ignora. */
    fun externalLinkAllowed(url: String): Boolean {
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme in ExternalSchemes
    }

    private val ExternalSchemes = setOf("http", "https", "mailto")
}

object ReaderStart {
    sealed interface Decision {
        data class Show(val id: String) : Decision
        data object Finish : Decision
    }

    /** Sin id válido, o sin el libro abierto en memoria (Android cerró la app), el Lector se cierra y vuelve a la Biblioteca. */
    fun decide(extraId: String?, isOpen: (String) -> Boolean): Decision =
        if (extraId != null && BookFiles.isValidId(extraId) && isOpen(extraId)) Decision.Show(extraId) else Decision.Finish
}
