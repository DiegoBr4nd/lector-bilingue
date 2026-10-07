package io.github.diegobr4nd.lectorbilingue.data

import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Libros abiertos que la Biblioteca le pasa al Lector (ReaderActivity necesita el libro ya abierto antes de crearse),
 * con la posición y el título guardados. Vive en memoria: si Android cierra la app, se vacía y el Lector vuelve a la Biblioteca.
 */
class OpenBooks : OpenStore<Publication>({ it.close() })

/**
 * La lógica de [OpenBooks] sin Readium, para probarla en la JVM (un `Publication` no se puede crear ahí).
 * [release] cierra un libro que ya nadie va a usar.
 */
open class OpenStore<P : Any>(private val release: (P) -> Unit) {
    private class Entry<P>(val pub: P, val initial: Locator?, val title: String?)
    private val open = mutableMapOf<String, Entry<P>>()

    /** [title] es el título guardado en la Biblioteca: el Lector muestra ese, no el del libro, para que coincidan. */
    @Synchronized fun put(id: String, pub: P, initial: Locator?, title: String? = null) {
        open.remove(id)?.takeIf { it.pub !== pub }?.let { release(it.pub) }
        open[id] = Entry(pub, initial, title)
    }

    @Synchronized fun get(id: String): P? = open[id]?.pub

    @Synchronized fun initialLocator(id: String): Locator? = open[id]?.initial

    @Synchronized fun title(id: String): String? = open[id]?.title

    /** Cierra lo que haya abierto con ese id (p. ej. al borrar el libro). */
    @Synchronized fun close(id: String) { open.remove(id)?.let { release(it.pub) } }

    /**
     * Cierra solo si [pub] sigue siendo el libro abierto con ese id. Lo usa un Lector que termina: si mientras tanto
     * se reabrió el mismo libro, el nuevo no se toca (el viejo ya lo cerró [put]).
     */
    @Synchronized fun close(id: String, pub: P) {
        if (open[id]?.pub === pub) open.remove(id)?.let { release(it.pub) }
    }
}
