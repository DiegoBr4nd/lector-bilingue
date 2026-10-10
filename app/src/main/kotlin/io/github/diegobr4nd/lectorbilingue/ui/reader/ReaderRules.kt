package io.github.diegobr4nd.lectorbilingue.ui.reader

import io.github.diegobr4nd.lectorbilingue.books.BookFiles

/** Texto de la barra inferior: título del capítulo (si tiene) y % leído (si Readium ya lo sabe). */
data class PositionLabel(val chapter: String?, val percent: Int?)

/** Una línea del Índice. [title] null = el capítulo no tiene título (la pantalla pone "Sección sin título"). */
data class TocEntry(val title: String?, val depth: Int, val href: String)
data class TocEntrySource(val title: String?, val href: String, val children: List<TocEntrySource>)

/** Bordes de la página al empezar un gesto. Un capítulo que cabe entero en la pantalla está en los dos. */
data class PageEdges(val atTop: Boolean, val atBottom: Boolean)

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

    /** Recorrido mínimo del dedo (en dp) para pasar de capítulo en el borde: un toque o un temblor no cuentan. */
    const val CHAPTER_TURN_MIN_DP = 48.0

    /**
     * Paso de capítulo al soltar el dedo: +1 siguiente, -1 anterior, 0 nada. Solo si la página YA estaba en el
     * borde al empezar el gesto ([edges]) y el dedo siguió hacia fuera al menos [CHAPTER_TURN_MIN_DY]: abajo del
     * todo y subir → siguiente; arriba del todo y bajar → anterior. A mitad de capítulo nunca cambia (fix del
     * cambio de capítulo por un gesto algo diagonal). [dragDy] es `DragEvent.offset.y` del final del gesto, en px del
     * aparato (el script de Readium multiplica por `devicePixelRatio`), y [minDy] el mínimo en esos mismos px.
     */
    fun chapterStep(edges: PageEdges?, dragDy: Double, minDy: Double): Int = when {
        edges == null || dragDy.isNaN() -> 0
        edges.atBottom && dragDy <= -minDy -> 1
        edges.atTop && dragDy >= minDy -> -1
        else -> 0
    }

    /** Índice del capítulo a [step] del actual en el orden de lectura (sin `#fragmento`), o null si no hay. */
    fun neighborChapter(readingOrder: List<String>, current: String?, step: Int): Int? {
        if (current == null || step == 0) return null
        val i = readingOrder.indexOf(current.substringBefore('#'))
        if (i < 0) return null
        return (i + step).takeIf { it in readingOrder.indices }
    }

    /** Final del libro: Readium da 1.0 o casi (redondeo de la última posición). */
    fun atEnd(totalProgression: Double?): Boolean = totalProgression != null && totalProgression >= 0.999

    fun label(chapterTitle: String?, totalProgression: Double?): PositionLabel = PositionLabel(
        chapterTitle?.trim()?.takeIf { it.isNotEmpty() },
        totalProgression?.takeIf { !it.isNaN() }?.let { (it.coerceIn(0.0, 1.0) * 100).toInt() },
    )

    /**
     * Índice en una lista con sangría. Un capítulo sin título queda con título null: la pantalla muestra un texto
     * propio, nunca el nombre del archivo ("OEBPS/Text/chap03.xhtml" es un tecnicismo).
     */
    fun flattenToc(links: List<TocEntrySource>, depth: Int = 0): List<TocEntry> = links.flatMap { l ->
        listOf(TocEntry(l.title?.trim()?.takeIf { it.isNotEmpty() }, depth, l.href)) + flattenToc(l.children, depth + 1)
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
