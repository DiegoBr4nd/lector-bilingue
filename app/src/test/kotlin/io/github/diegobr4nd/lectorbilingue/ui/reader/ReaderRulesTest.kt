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
