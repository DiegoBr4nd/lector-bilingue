package io.github.diegobr4nd.lectorbilingue.books

import android.graphics.Bitmap
import io.github.diegobr4nd.lectorbilingue.books.db.BookDao
import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Añade un libro: copia con tope de tamaño → valida → lee metadatos → guarda portada → mueve → crea la fila.
 * Si algo falla, no queda ni archivo ni fila. La fila se crea al final: una app cerrada a mitad no deja libros fantasma.
 */
class BookImporter(
    private val files: BookFiles,
    private val dao: BookDao,
    private val readMetadata: suspend (File) -> MetadataRead,
    private val saveCover: (Bitmap, File) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun import(open: () -> InputStream?, fileName: String?): ImportResult = withContext(Dispatchers.IO) {
        val tmp = files.newTmp()
        val id = newId()
        var result: ImportResult = ImportResult.Error(ImportError.DAMAGED)
        try {
            result = steps(open, fileName, tmp, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            result = ImportResult.Error(if (files.booksDir.usableSpace in 0 until MIN_FREE_BYTES) ImportError.NO_SPACE else ImportError.DAMAGED)
        } catch (e: Exception) {
            result = ImportResult.Error(ImportError.DAMAGED)
        } finally {
            // Pase lo que pase: sin temporal; y si no terminó bien, sin EPUB ni portada.
            tmp.delete()
            if (result !is ImportResult.Ok) {
                files.epub(id).delete()
                files.cover(id).delete()
            }
        }
        result
    }

    private suspend fun steps(open: () -> InputStream?, fileName: String?, tmp: File, id: String): ImportResult {
        copyLimited(open, tmp)?.let { return ImportResult.Error(it) }
        EpubArchiveCheck.check(tmp)?.let { return ImportResult.Error(it) }
        val meta = when (val read = readMetadata(tmp)) {
            is MetadataRead.Failed -> return ImportResult.Error(read.reason)
            is MetadataRead.Ok -> read.metadata
        }
        files.booksDir.mkdirs()
        val coverFile = meta.cover?.let { bmp -> files.cover(id).also { saveCover(bmp, it) } }
        if (!tmp.renameTo(files.epub(id))) return ImportResult.Error(ImportError.DAMAGED)
        dao.insert(
            BookEntity(
                id = id,
                title = titleFor(meta.title, fileName),
                author = meta.author,
                coverPath = coverFile?.name,
                addedAt = clock(),
                lastOpenedAt = null,
                progress = 0f,
                locator = null,
            ),
        )
        return ImportResult.Ok(id)
    }

    /** Copia contando bytes: el tope se aplica aunque el origen no diga su tamaño. */
    private fun copyLimited(open: () -> InputStream?, target: File): ImportError? {
        val input = open() ?: return ImportError.DAMAGED
        input.use { src ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > ImportLimits.MAX_FILE_BYTES) return ImportError.TOO_BIG
                    out.write(buf, 0, n)
                }
            }
        }
        return null
    }

    private fun titleFor(meta: String?, fileName: String?): String =
        meta?.trim()?.takeIf { it.isNotEmpty() }
            ?: fileName?.substringAfterLast('/')?.let { if (it.endsWith(".epub", ignoreCase = true)) it.dropLast(5) else it }?.trim()?.takeIf { it.isNotEmpty() }
            ?: UNTITLED

    companion object {
        /**
         * Título vacío = "sin título": la interfaz muestra su propio texto traducible ("Libro sin título")
         * cuando el título está en blanco. No se usa un carácter NUL: SQLite podría truncarlo.
         */
        const val UNTITLED = ""
        private const val MIN_FREE_BYTES = 5L * 1024 * 1024
    }
}
