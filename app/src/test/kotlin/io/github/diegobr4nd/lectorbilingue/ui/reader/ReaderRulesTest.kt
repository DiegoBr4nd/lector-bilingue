package io.github.diegobr4nd.lectorbilingue.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderRulesTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"

    // Arrastre: offset.y < 0 = el dedo sube = el texto avanza (se lee hacia adelante).
    @Test fun `leer hacia adelante oculta`() =
        assertFalse(ReaderRules.barsVisible(dragDy = -40.0, atEnd = false, wasVisible = true, touchExploration = false))

    @Test fun `volver atras muestra`() =
        assertTrue(ReaderRules.barsVisible(dragDy = 40.0, atEnd = false, wasVisible = false, touchExploration = false))

    @Test fun `sin arrastre conserva`() {
        assertFalse(ReaderRules.barsVisible(null, atEnd = false, wasVisible = false, touchExploration = false))
        assertTrue(ReaderRules.barsVisible(null, atEnd = false, wasVisible = true, touchExploration = false))
        assertFalse(ReaderRules.barsVisible(0.0, atEnd = false, wasVisible = false, touchExploration = false))
        assertTrue(ReaderRules.barsVisible(0.0, atEnd = false, wasVisible = true, touchExploration = false))
    }

    @Test fun `al final del libro se ven aunque se siga leyendo`() {
        assertTrue(ReaderRules.barsVisible(-40.0, atEnd = true, wasVisible = false, touchExploration = false))
        assertTrue(ReaderRules.barsVisible(null, atEnd = true, wasVisible = false, touchExploration = false))
    }

    @Test fun `con TalkBack siempre se ven`() =
        assertTrue(ReaderRules.barsVisible(-40.0, atEnd = false, wasVisible = false, touchExploration = true))

    // Paso de capítulo al llegar al borde (fix/cambio-de-capitulo). dragDy < 0 = el dedo sube = leer hacia adelante.
    private val bottom = PageEdges(atTop = false, atBottom = true)
    private val top = PageEdges(atTop = true, atBottom = false)
    private val middle = PageEdges(atTop = false, atBottom = false)
    private val min = ReaderRules.CHAPTER_TURN_MIN_DP * 2.625 // px del Pixel 7

    @Test fun `abajo del todo y seguir subiendo pasa al siguiente`() = assertEquals(1, ReaderRules.chapterStep(bottom, -200.0, min))

    @Test fun `arriba del todo y bajar vuelve al anterior`() = assertEquals(-1, ReaderRules.chapterStep(top, 200.0, min))

    @Test fun `a mitad de capitulo nunca cambia`() {
        assertEquals(0, ReaderRules.chapterStep(middle, -500.0, min))
        assertEquals(0, ReaderRules.chapterStep(middle, 500.0, min))
    }

    @Test fun `gesto corto en el borde no cambia`() {
        assertEquals(0, ReaderRules.chapterStep(bottom, -(min - 1), min))
        assertEquals(0, ReaderRules.chapterStep(top, min - 1, min))
        assertEquals(1, ReaderRules.chapterStep(bottom, -min, min))
    }

    @Test fun `en el borde pero hacia el otro lado solo desplaza`() {
        assertEquals(0, ReaderRules.chapterStep(bottom, 200.0, min))
        assertEquals(0, ReaderRules.chapterStep(top, -200.0, min))
    }

    @Test fun `capitulo que cabe en la pantalla va a los dos lados`() {
        val both = PageEdges(atTop = true, atBottom = true)
        assertEquals(1, ReaderRules.chapterStep(both, -200.0, min))
        assertEquals(-1, ReaderRules.chapterStep(both, 200.0, min))
    }

    @Test fun `sin bordes conocidos o sin gesto no cambia`() {
        assertEquals(0, ReaderRules.chapterStep(null, -200.0, min))
        assertEquals(0, ReaderRules.chapterStep(bottom, Double.NaN, min))
        assertEquals(0, ReaderRules.chapterStep(bottom, 0.0, min))
    }

    // Tras un paso de capítulo nuestro, los gestos esperan a que el capítulo nuevo esté en su sitio (revisión M1).
    @Test fun `sin paso pendiente el gesto vale`() = assertTrue(ReaderRules.dragAllowed(null, "OEBPS/c1.xhtml", 0))

    @Test fun `recien pasado y aun en el capitulo viejo no vale`() {
        assertFalse(ReaderRules.dragAllowed("OEBPS/c3.xhtml", "OEBPS/c2.xhtml", 100))
        assertFalse(ReaderRules.dragAllowed("OEBPS/c3.xhtml", "OEBPS/c2.xhtml", ReaderRules.TURN_SETTLE_MS))
    }

    @Test fun `ya en el capitulo nuevo vale tras asentarse`() {
        assertFalse(ReaderRules.dragAllowed("OEBPS/c3.xhtml", "OEBPS/c3.xhtml", ReaderRules.TURN_SETTLE_MS - 1))
        assertTrue(ReaderRules.dragAllowed("OEBPS/c3.xhtml", "OEBPS/c3.xhtml", ReaderRules.TURN_SETTLE_MS))
    }

    @Test fun `si el capitulo nuevo nunca llega se vuelve a permitir`() {
        assertFalse(ReaderRules.dragAllowed("OEBPS/c3.xhtml", null, ReaderRules.TURN_TIMEOUT_MS - 1))
        assertTrue(ReaderRules.dragAllowed("OEBPS/c3.xhtml", null, ReaderRules.TURN_TIMEOUT_MS))
    }

    private val order = listOf("OEBPS/c1.xhtml", "OEBPS/c2.xhtml", "OEBPS/c3.xhtml")

    @Test fun `capitulo vecino en el orden de lectura`() {
        assertEquals(2, ReaderRules.neighborChapter(order, "OEBPS/c2.xhtml", 1))
        assertEquals(0, ReaderRules.neighborChapter(order, "OEBPS/c2.xhtml", -1))
        assertEquals(1, ReaderRules.neighborChapter(order, "OEBPS/c1.xhtml#parte", 1))
    }

    @Test fun `sin vecino en los extremos o con href desconocido`() {
        assertNull(ReaderRules.neighborChapter(order, "OEBPS/c3.xhtml", 1))
        assertNull(ReaderRules.neighborChapter(order, "OEBPS/c1.xhtml", -1))
        assertNull(ReaderRules.neighborChapter(order, "OEBPS/otro.xhtml", 1))
        assertNull(ReaderRules.neighborChapter(order, null, 1))
        assertNull(ReaderRules.neighborChapter(order, "OEBPS/c2.xhtml", 0))
    }

    @Test fun `fin del libro desde 0,999`() {
        assertTrue(ReaderRules.atEnd(1.0))
        assertTrue(ReaderRules.atEnd(0.999))
        assertFalse(ReaderRules.atEnd(0.99))
        assertFalse(ReaderRules.atEnd(null))
        assertFalse(ReaderRules.atEnd(Double.NaN))
    }

    @Test fun `etiqueta con y sin capitulo`() {
        assertEquals(PositionLabel("La tormenta", 42), ReaderRules.label("La tormenta", 0.429))
        assertEquals(PositionLabel(null, 42), ReaderRules.label("   ", 0.429))
        assertEquals(PositionLabel(null, null), ReaderRules.label(null, null))
        assertEquals(PositionLabel(null, 100), ReaderRules.label(null, 1.4))
        assertEquals(PositionLabel(null, null), ReaderRules.label(null, Double.NaN))
    }

    // Sin título: null (la pantalla muestra "Sección sin título"), nunca el nombre del archivo.
    @Test fun `indice aplanado con profundidad`() {
        val toc = listOf(
            TocEntrySource("Parte 1", "p1.xhtml", listOf(TocEntrySource("Cap 1", "c1.xhtml", emptyList()), TocEntrySource(null, "c2.xhtml", emptyList()))),
            TocEntrySource("  ", "p2.xhtml", emptyList()),
        )
        assertEquals(
            listOf(TocEntry("Parte 1", 0, "p1.xhtml"), TocEntry("Cap 1", 1, "c1.xhtml"), TocEntry(null, 1, "c2.xhtml"), TocEntry(null, 0, "p2.xhtml")),
            ReaderRules.flattenToc(toc),
        )
    }

    @Test fun `capitulo actual compara sin el fragmento`() {
        val entries = listOf(TocEntry("A", 0, "OEBPS/a.xhtml"), TocEntry("B", 0, "OEBPS/b.xhtml#s1"), TocEntry("B2", 1, "OEBPS/b.xhtml#s2"))
        assertEquals(0, ReaderRules.currentTocIndex(entries, "OEBPS/a.xhtml"))
        assertEquals(1, ReaderRules.currentTocIndex(entries, "OEBPS/b.xhtml#x"))
        assertNull(ReaderRules.currentTocIndex(entries, "OEBPS/c.xhtml"))
        assertNull(ReaderRules.currentTocIndex(entries, null))
    }

    @Test fun `solo http, https y mailto salen del libro`() {
        assertTrue(ReaderRules.externalLinkAllowed("https://ejemplo.org/a"))
        assertTrue(ReaderRules.externalLinkAllowed("HTTP://ejemplo.org"))
        assertTrue(ReaderRules.externalLinkAllowed("mailto:alguien@ejemplo.org"))
        assertFalse(ReaderRules.externalLinkAllowed("javascript:alert(1)"))
        assertFalse(ReaderRules.externalLinkAllowed("intent://x#Intent;end"))
        assertFalse(ReaderRules.externalLinkAllowed("file:///sdcard/x"))
        assertFalse(ReaderRules.externalLinkAllowed("content://x/y"))
        assertFalse(ReaderRules.externalLinkAllowed("tel:123"))
        assertFalse(ReaderRules.externalLinkAllowed("sin-esquema"))
        assertFalse(ReaderRules.externalLinkAllowed(""))
    }

    @Test fun `arranque con libro abierto`() = assertEquals(ReaderStart.Decision.Show(id), ReaderStart.decide(id) { true })
    @Test fun `arranque tras cierre de Android se cierra`() = assertEquals(ReaderStart.Decision.Finish, ReaderStart.decide(id) { false })
    @Test fun `id ausente o raro se cierra`() {
        assertEquals(ReaderStart.Decision.Finish, ReaderStart.decide(null) { true })
        assertEquals(ReaderStart.Decision.Finish, ReaderStart.decide("../x") { true })
        assertEquals(ReaderStart.Decision.Finish, ReaderStart.decide(id.uppercase()) { true })
    }
}
