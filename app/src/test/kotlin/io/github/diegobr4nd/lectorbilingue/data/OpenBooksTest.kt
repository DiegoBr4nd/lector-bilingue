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

    /**
     * El caso de la Biblioteca: el Lector A muestra pubA y está terminando; la persona reabre el libro y la
     * Biblioteca abre SIEMPRE un objeto nuevo (pubB). El onDestroy tardío de A no cierra pubB.
     */
    @Test fun `reabrir mientras el Lector anterior termina no le cierra el libro al nuevo`() {
        val pubA = FakeBook("A")
        store.put(id, pubA, null) // Lector A.
        val readerA = id to pubA
        val pubB = FakeBook("B")
        store.put(id, pubB, null) // Reapertura: objeto nuevo.
        store.close(readerA.first, readerA.second) // onDestroy de A.
        assertSame(pubB, store.get(id))
        assertEquals(listOf("A"), closed)
        store.close(id, pubB) // Al final, el Lector B suelta el suyo.
        assertNull(store.get(id))
        assertEquals(listOf("A", "B"), closed)
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

    // El Lector muestra el título guardado (el mismo que la Biblioteca), no el del OPF.
    @Test fun `guarda el titulo junto al libro y lo cambia al reabrir`() {
        store.put(id, FakeBook("A"), null, "Mi libro")
        assertEquals("Mi libro", store.title(id))
        store.put(id, FakeBook("B"), null, "Otro")
        assertEquals("Otro", store.title(id))
        store.close(id)
        assertNull(store.title(id))
    }
}
