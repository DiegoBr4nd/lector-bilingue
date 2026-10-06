package io.github.diegobr4nd.lectorbilingue.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Un `Publication` de Readium no se puede crear en la JVM: se prueba la misma lógica con un libro de mentira. */
class OpenBooksTest {
    private class FakeBook(val name: String)

    private val closed = mutableListOf<String>()
    private val store = OpenStore<FakeBook> { closed += it.name }
    private val id = "123e4567-e89b-12d3-a456-426614174000"

    @Test fun `cerrar con un libro viejo no toca el que esta abierto ahora`() {
        val a = FakeBook("A")
        val b = FakeBook("B")
        store.put(id, a, null)
        store.put(id, b, null) // Se reabrió el libro mientras el Lector A terminaba: A se cierra aquí.
        assertEquals(listOf("A"), closed)
        store.close(id, a) // onDestroy tardío del Lector A.
        assertSame(b, store.get(id))
        assertEquals(listOf("A"), closed)
    }

    @Test fun `cerrar con el libro actual lo cierra y lo quita`() {
        val a = FakeBook("A")
        store.put(id, a, null)
        store.close(id, a)
        assertNull(store.get(id))
        assertEquals(listOf("A"), closed)
    }

    @Test fun `poner el mismo libro otra vez no lo cierra`() {
        val a = FakeBook("A")
        store.put(id, a, null)
        store.put(id, a, null)
        assertSame(a, store.get(id))
        assertEquals(emptyList(), closed)
    }

    @Test fun `cerrar por id cierra lo que haya`() {
        store.put(id, FakeBook("A"), null)
        store.close(id)
        assertNull(store.get(id))
        assertEquals(listOf("A"), closed)
        store.close(id) // Sin nada abierto: no pasa nada.
        assertEquals(listOf("A"), closed)
    }
}
