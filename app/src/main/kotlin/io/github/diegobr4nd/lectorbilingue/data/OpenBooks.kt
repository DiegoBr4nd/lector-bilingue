package io.github.diegobr4nd.lectorbilingue.data

import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Libros abiertos que la Biblioteca le pasa al Lector (ReaderActivity necesita el libro ya abierto antes de crearse),
 * con la posición guardada. Vive en memoria: si Android cierra la app, se vacía y el Lector vuelve a la Biblioteca.
 */
class OpenBooks {
    private class Entry(val pub: Publication, val initial: Locator?)
    private val open = mutableMapOf<String, Entry>()

    @Synchronized fun put(id: String, pub: Publication, initial: Locator?) {
        open.remove(id)?.takeIf { it.pub !== pub }?.pub?.close()
        open[id] = Entry(pub, initial)
    }

    @Synchronized fun get(id: String): Publication? = open[id]?.pub

    @Synchronized fun initialLocator(id: String): Locator? = open[id]?.initial

    @Synchronized fun close(id: String) { open.remove(id)?.pub?.close() }
}
