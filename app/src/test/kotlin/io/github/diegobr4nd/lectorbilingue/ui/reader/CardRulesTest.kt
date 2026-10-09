package io.github.diegobr4nd.lectorbilingue.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardRulesTest {
    @Test fun `tocar un parrafo sin tarjeta la abre y con tarjeta la cierra`() {
        assertTrue(CardRules.toggle(emptyMap(), 0))
        assertFalse(CardRules.toggle(mapOf(0 to CardState.Text("T")), 0))
        assertFalse(CardRules.toggle(mapOf(2 to CardState.Skeleton), 2)) // también mientras traduce
        assertTrue(CardRules.toggle(mapOf(0 to CardState.Text("T")), 1))
    }

    @Test fun `pretraduce los siguientes sin el tocado`() {
        val hit = (0..5).map { PageParagraph(it, "P$it.") }
        assertEquals(hit.drop(1), CardRules.prefetchTargets(hit, emptySet()))
    }

    @Test fun `no pretraduce lo que ya esta en cache o con tarjeta`() {
        val hit = listOf(PageParagraph(3, "Uno."), PageParagraph(4, "Dos."), PageParagraph(5, "Tres  tres."), PageParagraph(6, "Cuatro."))
        // Se compara con el texto normalizado (espacios colapsados), como lo guarda el caché.
        assertEquals(listOf(PageParagraph(6, "Cuatro.")), CardRules.prefetchTargets(hit, setOf("Dos.", "Tres tres.")))
    }

    @Test fun `sin parrafos o solo el tocado no hay nada que pretraducir`() {
        assertEquals(emptyList(), CardRules.prefetchTargets(emptyList(), emptySet()))
        assertEquals(emptyList(), CardRules.prefetchTargets(listOf(PageParagraph(0, "a")), emptySet()))
    }

    @Test fun `un siguiente repetido o vacio se pide una sola vez`() {
        val hit = listOf(PageParagraph(0, "a"), PageParagraph(1, "b"), PageParagraph(2, " b "), PageParagraph(3, "  "))
        assertEquals(listOf(PageParagraph(1, "b")), CardRules.prefetchTargets(hit, emptySet()))
    }
}
