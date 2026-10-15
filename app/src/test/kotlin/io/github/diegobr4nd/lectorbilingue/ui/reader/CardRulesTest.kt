package io.github.diegobr4nd.lectorbilingue.ui.reader

import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.data.Priority
import io.github.diegobr4nd.lectorbilingue.data.ReadingMode
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
        waiting = "Traduciendo…",
        original = "Original",
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

    // ---------- Modos de lectura (3c-2) ----------

    @Test fun `los estados de Solo traduccion se vuelven tarjetas Solo y de espera`() {
        assertEquals(Card.Solo("Hola"), CardRules.card(CardState.Solo("Hola"), texts))
        assertEquals(Card.Waiting("Traduciendo…"), CardRules.card(CardState.Waiting, texts))
        // Viendo el original: la misma tarjeta Solo; la pantalla además muestra el original (originalShown).
        assertEquals(Card.Solo("Hola"), CardRules.card(CardState.ShowingOriginal("Hola"), texts))
        assertEquals(false, CardRules.originalShown(CardState.Solo("Hola")))
        assertEquals(true, CardRules.originalShown(CardState.ShowingOriginal("Hola")))
        assertEquals(null, CardRules.originalShown(CardState.Text("Hola")))
        assertEquals(null, CardRules.originalShown(CardState.Waiting))
        assertEquals("Hola", CardRules.spoken(CardState.Solo("Hola"), texts))
        assertEquals("Original", CardRules.spoken(CardState.ShowingOriginal("Hola"), texts))
        assertEquals(null, CardRules.spoken(CardState.Waiting, texts))
    }

    @Test fun `lo que muestra la pagina segun el modo`() {
        val text = CardState.Text("T")
        // Tocar: tal cual.
        assertEquals(text, ModeRules.display(ReadingMode.TAP, text, toggled = false))
        assertEquals(CardState.Skeleton, ModeRules.display(ReadingMode.TAP, CardState.Skeleton, toggled = true))
        // Intercalado: igual, salvo la oculta con un toque.
        assertEquals(text, ModeRules.display(ReadingMode.INTERLEAVED, text, toggled = false))
        assertEquals(null, ModeRules.display(ReadingMode.INTERLEAVED, text, toggled = true))
        // Solo traducción: la traducción en lugar del original; en espera, el original en gris.
        assertEquals(CardState.Solo("T"), ModeRules.display(ReadingMode.TRANSLATION_ONLY, text, toggled = false))
        assertEquals(CardState.ShowingOriginal("T"), ModeRules.display(ReadingMode.TRANSLATION_ONLY, text, toggled = true))
        assertEquals(CardState.Waiting, ModeRules.display(ReadingMode.TRANSLATION_ONLY, CardState.Skeleton, toggled = false))
        assertEquals(CardState.Waiting, ModeRules.display(ReadingMode.TRANSLATION_ONLY, CardState.Preparing, toggled = false))
        // Un error del párrafo o demasiado largo: su tarjeta de la 3b, bajo el original.
        assertEquals(CardState.TooLong, ModeRules.display(ReadingMode.TRANSLATION_ONLY, CardState.TooLong, toggled = false))
        assertEquals(CardState.Failed(false), ModeRules.display(ReadingMode.TRANSLATION_ONLY, CardState.Failed(false), toggled = false))
    }

    @Test fun `que hace un toque en cada modo`() {
        val text = CardState.Text("T")
        for (mode in ReadingMode.entries) assertEquals(TapAction.OPEN, ModeRules.tap(mode, null))
        assertEquals(TapAction.CLOSE, ModeRules.tap(ReadingMode.TAP, text))
        assertEquals(TapAction.CLOSE, ModeRules.tap(ReadingMode.TAP, CardState.Skeleton))
        assertEquals(TapAction.TOGGLE, ModeRules.tap(ReadingMode.INTERLEAVED, text))
        assertEquals(TapAction.TOGGLE, ModeRules.tap(ReadingMode.INTERLEAVED, CardState.Skeleton))
        assertEquals(TapAction.TOGGLE, ModeRules.tap(ReadingMode.TRANSLATION_ONLY, text))
        // Solo traducción sin traducir aún (el original ya se ve) o con error: nada.
        assertEquals(TapAction.NOTHING, ModeRules.tap(ReadingMode.TRANSLATION_ONLY, CardState.Skeleton))
        assertEquals(TapAction.NOTHING, ModeRules.tap(ReadingMode.TRANSLATION_ONLY, CardState.Failed(false)))
    }

    @Test fun `ancla y modo de la pagina`() {
        assertEquals(CardAnchor.SELF, ModeRules.anchor(ReadingMode.TAP))
        assertEquals(CardAnchor.SCREEN, ModeRules.anchor(ReadingMode.INTERLEAVED))
        assertEquals(CardAnchor.SCREEN, ModeRules.anchor(ReadingMode.TRANSLATION_ONLY))
        assertEquals("tocar", ModeRules.pageMode(ReadingMode.TAP))
        assertEquals("intercalado", ModeRules.pageMode(ReadingMode.INTERLEAVED))
        assertEquals("solo", ModeRules.pageMode(ReadingMode.TRANSLATION_ONLY))
        for (mode in ReadingMode.entries) assertTrue(ModeRules.pageMode(mode) in ParagraphScripts.MODES)
    }

    /** Párrafos "P{i}." de arriba abajo: los [onScreen] primeros tocan la pantalla (marca `v` de `visible()`). */
    private fun ps(range: IntRange, onScreen: Int = 2) =
        range.mapIndexed { k, i -> PageParagraph(i, "P$i.", onScreen = k < onScreen) }

    @Test fun `lo visible se pide como toque en orden y los siguientes como pretraduccion`() {
        val plan = ModeRules.plan(ps(0..11), open = emptySet(), queued = emptySet(), inFlight = 0, cached = emptySet())
        assertEquals((0..11).toList(), plan.requests.map { it.paragraph.index })
        assertEquals(listOf(Priority.TAP, Priority.TAP) + List(10) { Priority.PREFETCH }, plan.requests.map { it.priority })
        // La prioridad sale de la marca de la página, no de cuántos llegan: al final del capítulo todos en pantalla.
        assertEquals(List(4) { Priority.TAP }, ModeRules.plan(ps(0..3, onScreen = 4), emptySet(), emptySet(), 0, emptySet()).requests.map { it.priority })
        assertFalse(plan.more)
    }

    @Test fun `el plan no repite los que ya tienen tarjeta y respeta el tope de quince`() {
        val plan = ModeRules.plan(ps(0..29), open = setOf(0, 1), queued = emptySet(), inFlight = 2, cached = emptySet())
        assertEquals((2..14).toList(), plan.requests.map { it.paragraph.index }) // 2 en curso + 13 = 15
        assertEquals(15, ModeRules.MAX_ACTIVE)
        assertTrue(plan.more) // quedaron párrafos fuera por el tope
        // Los que ya esperan en la fila del modo cuentan para el tope y se conservan en el orden nuevo.
        val again = ModeRules.plan(ps(5..20), open = setOf(5, 6, 7), queued = setOf(6, 7), inFlight = 0, cached = emptySet())
        assertEquals((6..20).toList(), again.requests.map { it.paragraph.index })
        assertFalse(again.more) // justo 15: no sobra nada
    }

    @Test fun `el plan separa lo que ya esta en cache y lo demasiado largo`() {
        val paragraphs = listOf(
            PageParagraph(0, "Uno."), PageParagraph(1, "Dos  dos."), PageParagraph(2, "B.", cut = true),
            PageParagraph(3, "  "), PageParagraph(4, "Tres."),
        )
        val plan = ModeRules.plan(paragraphs, emptySet(), emptySet(), 0, cached = setOf("Dos dos."))
        assertEquals(listOf(1), plan.cached.map { it.index })
        assertEquals(listOf(2), plan.tooLong.map { it.index })
        assertEquals(listOf(0, 4), plan.requests.map { it.paragraph.index })
    }

    @Test fun `un siguiente de mas de 4000 caracteres no se pide hasta que se vea`() {
        val long = PageParagraph(5, "D".repeat(4_001))
        val following = ModeRules.plan(ps(0..4) + long + ps(6..14, onScreen = 0), emptySet(), emptySet(), 0, emptySet())
        assertTrue(following.requests.none { it.paragraph.index == 5 })
        val shown = long.copy(onScreen = true)
        val onScreen = ModeRules.plan(listOf(shown) + ps(6..15, onScreen = 0), emptySet(), emptySet(), 0, emptySet())
        assertEquals(ModeRequest(shown, Priority.TAP), onScreen.requests.first())
    }

    // Revisión Tarea 4 (importante 2): tres párrafos, todos en pantalla, uno de 5000 caracteres: se pide como toque.
    @Test fun `un parrafo largo en pantalla se pide como toque aunque lleguen pocos`() {
        val long = PageParagraph(1, "L".repeat(5_000), onScreen = true)
        val paragraphs = listOf(PageParagraph(0, "A.", onScreen = true), long, PageParagraph(2, "C.", onScreen = true))
        val plan = ModeRules.plan(paragraphs, emptySet(), emptySet(), 0, emptySet())
        assertEquals(listOf(0, 1, 2), plan.requests.map { it.paragraph.index })
        assertTrue(plan.requests.all { it.priority == Priority.TAP })
    }
}
