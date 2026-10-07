package io.github.diegobr4nd.lectorbilingue.data

import io.github.diegobr4nd.lectorbilingue.books.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator

/**
 * Abre un libro guardado y lo deja en [store] con la posición y el título guardados, para que el Lector lo muestre.
 * Todo corre en [dispatcher] (fuera del hilo principal: Readium lee el OPF del disco).
 *
 * Siempre abre un objeto nuevo, nunca el que ya estaba en memoria: un Lector anterior que aún está terminando cierra
 * "su" objeto al final, y si fuera el mismo cerraría el del Lector nuevo. `put` cierra el anterior.
 * Genérico en [P] para probarlo en la JVM (un `Publication` de Readium no se puede crear ahí).
 */
class BookOpener<P : Any>(
    private val store: OpenStore<P>,
    /** Abre el EPUB del libro con ese id; null si no se pudo. */
    private val openFile: suspend (String) -> P?,
    /** La fila guardada del libro (título y posición). */
    private val stored: suspend (String) -> Book?,
    /** Convierte la posición guardada (JSON) en un localizador de Readium. */
    private val parseLocator: (String) -> Locator?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** true si quedó abierto en [store]; false si no abrió (la Biblioteca muestra el diálogo). */
    suspend fun open(id: String): Boolean = withContext(dispatcher) {
        try {
            // La fila se lee antes de abrir: así, una vez abierto, nada puede fallar antes de dejarlo en el almacén
            // (un libro abierto y no guardado quedaría sin cerrar).
            val book = stored(id)
            // Una posición dañada empieza desde el principio en vez de fallar.
            val initial = book?.locator?.let { json -> runCatching { parseLocator(json) }.getOrNull() }
            val pub = openFile(id) ?: return@withContext false
            store.put(id, pub, initial, book?.title)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false // Cualquier otra falla se trata como "no se pudo abrir" (diálogo), nunca como un cierre de la app.
        }
    }
}
