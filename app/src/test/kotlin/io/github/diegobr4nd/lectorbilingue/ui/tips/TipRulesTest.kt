package io.github.diegobr4nd.lectorbilingue.ui.tips

import io.github.diegobr4nd.lectorbilingue.data.ReadingMode
import io.github.diegobr4nd.lectorbilingue.data.TipId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TipRulesTest {
    private val fresh = TipState(
        seen = emptySet(), seenLoaded = true, pageReady = true, blocked = false,
        mode = ReadingMode.TAP, sourceIsSpanish = false, translatedByTap = false, reachedChapterEnd = false,
    )

    @Test fun `libro en ingles nuevo en Tocar pide tocar`() = assertEquals(TipId.TAP, TipRules.next(fresh, null))

    @Test fun `nada hasta leer los vistos ni con la pagina sin preparar ni bloqueado`() {
        assertNull(TipRules.next(fresh.copy(seenLoaded = false), null))
        assertNull(TipRules.next(fresh.copy(pageReady = false), null))
        assertNull(TipRules.next(fresh.copy(blocked = true), TipId.TAP))
    }

    @Test fun `libro en espanol muestra primero la direccion`() {
        assertEquals(TipId.DIRECTION, TipRules.next(fresh.copy(sourceIsSpanish = true), null))
        assertEquals(TipId.TAP, TipRules.next(fresh.copy(sourceIsSpanish = true, seen = setOf(TipId.DIRECTION)), null))
    }

    @Test fun `tras la primera traduccion por toque, ocultar y modos`() {
        val s = fresh.copy(seen = setOf(TipId.TAP), translatedByTap = true)
        assertEquals(TipId.HIDE_AND_MODES, TipRules.next(s, null))
    }

    @Test fun `tocar y ocultar solo en el modo Tocar`() {
        assertNull(TipRules.next(fresh.copy(mode = ReadingMode.INTERLEAVED), null))
        assertNull(TipRules.next(fresh.copy(mode = ReadingMode.TRANSLATION_ONLY, translatedByTap = true, seen = setOf(TipId.TAP)), null))
    }

    @Test fun `borde de capitulo cuando se llega y en cualquier modo`() {
        val s = fresh.copy(mode = ReadingMode.INTERLEAVED, reachedChapterEnd = true)
        assertEquals(TipId.CHAPTER_EDGE, TipRules.next(s, null))
    }

    @Test fun `un consejo visto no vuelve`() {
        val all = TipId.entries.toSet()
        assertNull(TipRules.next(fresh.copy(seen = all, sourceIsSpanish = true, reachedChapterEnd = true), null))
    }

    @Test fun `el que esta en pantalla se queda aunque otro de mayor prioridad se vuelva elegible`() {
        val s = fresh.copy(seen = setOf(TipId.TAP), translatedByTap = false, reachedChapterEnd = true, sourceIsSpanish = true)
        assertEquals(TipId.CHAPTER_EDGE, TipRules.next(s, TipId.CHAPTER_EDGE))
        // Sin consejo en pantalla, gana la prioridad.
        assertEquals(TipId.DIRECTION, TipRules.next(s, null))
    }

    @Test fun `el pegajoso se suelta si ya no es elegible o ya se vio`() {
        assertEquals(TipId.TAP, TipRules.next(fresh, TipId.CHAPTER_EDGE)) // CHAPTER_EDGE ya no es elegible
        assertEquals(TipId.TAP, TipRules.next(fresh.copy(reachedChapterEnd = true, seen = setOf(TipId.CHAPTER_EDGE)), TipId.CHAPTER_EDGE))
    }

    @Test fun `que evento cierra cada consejo`() {
        assertTrue(TipRules.dismissedBy(TipId.TAP, TipEvent.TAPPED))
        assertTrue(TipRules.dismissedBy(TipId.HIDE_AND_MODES, TipEvent.CARD_HIDDEN))
        assertTrue(TipRules.dismissedBy(TipId.HIDE_AND_MODES, TipEvent.SETTINGS_OPENED))
        assertTrue(TipRules.dismissedBy(TipId.CHAPTER_EDGE, TipEvent.CHAPTER_CHANGED))
        assertTrue(TipRules.dismissedBy(TipId.DIRECTION, TipEvent.DIRECTION_OPENED))
        assertFalse(TipRules.dismissedBy(TipId.TAP, TipEvent.SETTINGS_OPENED))
        assertFalse(TipRules.dismissedBy(TipId.DIRECTION, TipEvent.TAPPED))
        assertFalse(TipRules.dismissedBy(TipId.CHAPTER_EDGE, TipEvent.TAPPED))
    }
}
