package io.github.diegobr4nd.lectorbilingue.data

import io.github.diegobr4nd.lectorbilingue.books.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Un `Publication` de Readium no se puede crear en la JVM: se prueba con un libro de mentira. */
class BookOpenerTest {
    private class FakeBook(val name: String)

    private val id = "123e4567-e89b-12d3-a456-426614174000"
    private val closed = mutableListOf<String>()
    private val store = OpenStore<FakeBook> { closed += it.name }
    private val book = Book(id, "Mi libro", null, null, 0.4f, """{"href":"c1.xhtml"}""")

    private fun opener(
        openFile: suspend (String) -> FakeBook? = { FakeBook("A") },
        stored: suspend (String) -> Book? = { book },
        parseLocator: (String) -> org.readium.r2.shared.publication.Locator? = { null },
    ) = BookOpener(store, openFile, stored, parseLocator)

    @Test fun `abre y deja el libro con el titulo guardado`() = runTest {
        val parsed = mutableListOf<String>()
        assertTrue(opener(parseLocator = { parsed += it; null }).open(id))
        assertNotNull(store.get(id))
        assertEquals("Mi libro", store.title(id))
        assertEquals(listOf("""{"href":"c1.xhtml"}"""), parsed) // La posición guardada se relee al abrir.
    }

    // Readium lee el OPF del disco: nunca en el hilo principal (aquí, el hilo de la prueba).
    @Test fun `abre fuera del hilo de quien llama`() = runTest {
        val caller = Thread.currentThread()
        var opened: Thread? = null
        opener(openFile = { opened = Thread.currentThread(); FakeBook("A") }).open(id)
        assertNotNull(opened)
        assertNotSame(caller, opened)
    }

    @Test fun `siempre abre un objeto nuevo y suelta el anterior`() = runTest {
        var n = 0
        val o = opener(openFile = { FakeBook("P${n++}") })
        o.open(id)
        val first = store.get(id)
        o.open(id)
        assertNotSame(first, store.get(id))
        assertEquals(listOf("P0"), closed)
    }

    @Test fun `si Readium no abre devuelve false y no deja nada`() = runTest {
        assertFalse(opener(openFile = { null }).open(id))
        assertNull(store.get(id))
    }

    @Test fun `una falla inesperada es no se pudo abrir, no un cierre de la app`() = runTest {
        assertFalse(opener(stored = { error("base rota") }).open(id))
        assertFalse(opener(openFile = { throw IllegalStateException() }).open(id))
        assertNull(store.get(id))
    }

    @Test fun `una posicion danada empieza desde el principio`() = runTest {
        assertTrue(opener(parseLocator = { throw IllegalArgumentException() }).open(id))
        assertNull(store.initialLocator(id))
        assertEquals("Mi libro", store.title(id))
    }

    @Test fun `cancelar no se disfraza de falla`() = runTest {
        assertFailsWith<CancellationException> { opener(openFile = { throw CancellationException() }).open(id) }
    }

    @Test fun `sin fila guardada abre sin posicion ni titulo`() = runTest {
        val pub = FakeBook("A")
        assertTrue(opener(openFile = { pub }, stored = { null }).open(id))
        assertSame(pub, store.get(id))
        assertNull(store.title(id))
    }
}
