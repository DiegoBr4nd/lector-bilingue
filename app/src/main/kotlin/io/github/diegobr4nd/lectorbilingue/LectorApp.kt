package io.github.diegobr4nd.lectorbilingue

import android.app.Application
import android.graphics.Bitmap
import io.github.diegobr4nd.lectorbilingue.books.BookFiles
import io.github.diegobr4nd.lectorbilingue.books.BookImporter
import io.github.diegobr4nd.lectorbilingue.books.BookRepository
import io.github.diegobr4nd.lectorbilingue.books.MetadataRead
import io.github.diegobr4nd.lectorbilingue.books.db.LectorDatabase
import io.github.diegobr4nd.lectorbilingue.books.readium.ReadiumBooks
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.ModelHub
import io.github.diegobr4nd.lectorbilingue.data.OpenBooks
import java.io.File

/**
 * La "Application" vive mientras viva el proceso: es el lugar para lo que debe existir una sola vez.
 * El gestor de modelos no se cancela nunca, así que solo puede haber una copia.
 */
class LectorApp : Application() {
    val hub: ModelHub by lazy { ModelHub(this) }
    val settings: AppSettings by lazy { AppSettings.of(this) }
    val readium: ReadiumBooks by lazy { ReadiumBooks(this) }
    val openBooks = OpenBooks()
    private val bookFiles by lazy { BookFiles(filesDir) }
    val books: BookRepository by lazy {
        val dao = LectorDatabase.open(this).books()
        BookRepository(
            dao, bookFiles,
            BookImporter(
                bookFiles, dao,
                readMetadata = { file ->
                    readium.metadata(file).fold({ MetadataRead.Ok(it) }, { MetadataRead.Failed(it) })
                },
                saveCover = ::saveCover,
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Restos de una importación cortada: fuera del hilo principal. Si falla, se reintenta en el próximo
        // arranque; no se registra nada (ni rutas ni nombres).
        Thread({ runCatching { bookFiles.cleanTmp() } }, "limpiar-tmp-libros").start()
    }

    /** Portada reducida a 480 px de alto como máximo: suficiente para la lista y liviana. */
    private fun saveCover(bitmap: Bitmap, target: File) {
        val scale = minOf(1f, 480f / bitmap.height.coerceAtLeast(1))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), 480, true) else bitmap
        target.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
