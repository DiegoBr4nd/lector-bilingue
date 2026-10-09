package io.github.diegobr4nd.lectorbilingue.ui.reader

import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.data.RowStatus
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
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

    private val texts = CardTexts(
        paragraphFailed = "No se pudo traducir este párrafo",
        prepareFailed = "No se pudo preparar el traductor",
        retry = "Reintentar",
        download = "Descargar",
        tooLong = "Este párrafo es demasiado largo para traducirlo",
        missing = { engine -> "Falta $engine" },
        missingSpoken = { engine -> "Falta $engine, dicho" },
    )

    @Test fun `cada estado se vuelve la tarjeta con sus textos`() {
        assertEquals(Card.Skeleton, CardRules.card(CardState.Skeleton, texts))
        assertEquals(Card.Preparing, CardRules.card(CardState.Preparing, texts))
        assertEquals(Card.Text("Hola"), CardRules.card(CardState.Text("Hola"), texts))
        assertEquals(Card.MissingModel("Falta OPUS", "Descargar", "Falta OPUS, dicho"), CardRules.card(CardState.MissingModel(EngineId.OPUS), texts))
        assertEquals(Card.Failed("No se pudo traducir este párrafo", "Reintentar", "No se pudo traducir este párrafo. Reintentar"), CardRules.card(CardState.Failed(prepare = false), texts))
        assertEquals(Card.Failed("No se pudo preparar el traductor", "Reintentar", "No se pudo preparar el traductor. Reintentar"), CardRules.card(CardState.Failed(prepare = true), texts))
        // Sin enlace: reintentar daría lo mismo.
        assertEquals(Card.TooLong("Este párrafo es demasiado largo para traducirlo"), CardRules.card(CardState.TooLong, texts))
    }

    @Test fun `no pretraduce parrafos de mas de 4000 caracteres`() {
        val long = "B. ".repeat(1_500).trim() // 4 499 caracteres
        val hit = listOf(PageParagraph(0, "A."), PageParagraph(1, long), PageParagraph(2, "C."), PageParagraph(3, "D".repeat(4_000)))
        assertEquals(listOf(PageParagraph(2, "C."), PageParagraph(3, "D".repeat(4_000))), CardRules.prefetchTargets(hit, emptySet()))
    }

    @Test fun `tamano del modelo que falta segun el catalogo`() {
        val pairs = listOf(
            PairStatus("en-es", listOf(RowStatus("o", EngineId.OPUS, 238_524_992, false, null), RowStatus("f", EngineId.FIREFOX, 40L * 1024 * 1024, false, null))),
            PairStatus("es-en", listOf(RowStatus("o2", EngineId.OPUS, 1024L * 1024, false, null))),
        )
        assertEquals(227L, CardRules.modelMegabytes(pairs, "en-es", EngineId.OPUS))
        assertEquals(40L, CardRules.modelMegabytes(pairs, "en-es", EngineId.FIREFOX))
        assertEquals(null, CardRules.modelMegabytes(pairs, "es-en", EngineId.FIREFOX))
        assertEquals(null, CardRules.modelMegabytes(emptyList(), "en-es", EngineId.OPUS)) // sin catálogo: sin tamaño
    }

    @Test fun `que oye TalkBack al llegar el resultado de un toque`() {
        assertEquals("Hola", CardRules.spoken(CardState.Text("Hola"), texts))
        assertEquals("Falta OPUS, dicho", CardRules.spoken(CardState.MissingModel(EngineId.OPUS), texts))
        assertEquals("No se pudo traducir este párrafo. Reintentar", CardRules.spoken(CardState.Failed(prepare = false), texts))
        assertEquals("No se pudo preparar el traductor. Reintentar", CardRules.spoken(CardState.Failed(prepare = true), texts))
        assertEquals(null, CardRules.spoken(CardState.Skeleton, texts))
        assertEquals(null, CardRules.spoken(CardState.Preparing, texts))
        assertEquals("Este párrafo es demasiado largo para traducirlo", CardRules.spoken(CardState.TooLong, texts))
    }

    @Test fun `no pretraduce un parrafo recortado por la pagina`() {
        val hit = listOf(PageParagraph(0, "A."), PageParagraph(1, "B.", cut = true), PageParagraph(2, "C."))
        assertEquals(listOf(PageParagraph(2, "C.")), CardRules.prefetchTargets(hit, emptySet()))
    }
}
