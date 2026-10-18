package io.github.diegobr4nd.lectorbilingue.books

import io.github.diegobr4nd.lectorbilingue.books.db.BookDao
import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Punto único para la Biblioteca y el Lector: libros, importar, borrar y posición. */
class BookRepository(
    private val dao: BookDao,
    private val files: BookFiles,
    private val importer: BookImporter,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val books: Flow<List<Book>> = dao.observeAll().map { rows -> rows.map { it.toBook() } }

    suspend fun import(open: () -> InputStream?, fileName: String?): ImportResult = importer.import(open, fileName)

    suspend fun get(id: String): Book? = dao.get(id)?.toBook()

    /**
     * Quita la fila, borra el EPUB y la portada, y al final vacía el WAL: que no queden título, autor ni posición en sus
     * copias de páginas. Si el vaciado fallara, lo borrado ya está borrado y SQLite lo vacía solo más adelante: no se
     * cierra la app por eso. Las traducciones guardadas no son por libro: se borran aparte, en Idiomas.
     */
    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            dao.delete(id)
            files.deleteBook(id)
            try {
                dao.checkpointWal()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ignorado a propósito: ver la explicación de arriba.
            }
        }
    }

    suspend fun savePosition(id: String, locator: String, progress: Float) =
        dao.savePosition(id, locator, progress.coerceIn(0f, 1f))

    /** Dirección de traducción del libro; null = automática. */
    suspend fun setDirection(id: String, direction: String?) = dao.setDirection(id, direction)

    suspend fun markOpened(id: String) = dao.markOpened(id, clock())

    fun epubFile(id: String): File = files.epub(id)

    private fun BookEntity.toBook() =
        Book(id, title, author, coverPath?.let { File(files.booksDir, it) }, progress, locator, direction)
}
