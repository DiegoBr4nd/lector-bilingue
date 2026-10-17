package io.github.diegobr4nd.lectorbilingue

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.diegobr4nd.lectorbilingue.books.BookFiles
import io.github.diegobr4nd.lectorbilingue.books.BookImporter
import io.github.diegobr4nd.lectorbilingue.books.BookRepository
import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import io.github.diegobr4nd.lectorbilingue.books.db.LectorDatabase
import io.github.diegobr4nd.lectorbilingue.books.db.TranslationEntity
import io.github.diegobr4nd.lectorbilingue.data.EngineProvider
import io.github.diegobr4nd.lectorbilingue.data.TranslationService
import io.github.diegobr4nd.lectorbilingue.data.translationScope
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.api.TranslationEngine
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Borrar traducciones no debe dejar el texto en el archivo. Usa un archivo temporal propio
 * ("secure-delete-test.db"), nunca lector.db de Juan. Borra con el camino de la app ([TranslationService.clearCache]
 * con el DAO real): la prueba no hace checkpoints por su cuenta.
 */
@RunWith(AndroidJUnit4::class)
class SecureDeleteOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nombre = "secure-delete-test.db"

    @Before
    fun limpiarAntes() { context.deleteDatabase(nombre) }

    @After
    fun limpiarDespues() { context.deleteDatabase(nombre) }

    private fun contiene(f: File, frase: String): Boolean =
        f.exists() && String(f.readBytes(), Charsets.ISO_8859_1).contains(frase) ||
            f.exists() && String(f.readBytes(), Charsets.UTF_16LE).contains(frase)

    /** Sin modelos: clearCache no carga ningún motor. */
    private val sinMotores = object : EngineProvider {
        override fun installed(pair: LanguagePair) = emptyMap<EngineId, String>()
        override fun engine(id: EngineId): TranslationEngine = error("no se usa")
        override fun config(id: EngineId) = EngineConfig()
        override fun totalRamBytes() = 8L * 1024 * 1024 * 1024
        override fun forced(): EngineId? = null
        override fun hasMemoryFor(id: EngineId) = true
    }

    @Test
    fun tras_borrar_la_frase_no_queda_en_db_ni_wal() = runBlocking {
        val frase = "frase-unica-secure-delete-${System.nanoTime()}"
        val db = LectorDatabase.open(context, nombre)
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "motor-prueba") }.asCoroutineDispatcher()
        val scope = translationScope(worker)
        try {
            val dao = db.translations()
            dao.put(TranslationEntity("k1", frase, 1L))
            val archivo = context.getDatabasePath(nombre)
            val wal = File(archivo.path + "-wal")
            // Control: antes de borrar la frase está en el .db o en el -wal (si no, la prueba no probaría nada).
            assertTrue(contiene(archivo, frase) || contiene(wal, frase), "control: la frase debería estar antes de borrar")

            TranslationService(sinMotores, dao, worker = worker, scope = scope).clearCache()
            assertEquals(0, dao.count())

            assertFalse(contiene(archivo, frase), "la frase quedó en el .db")
            assertFalse(contiene(wal, frase), "la frase quedó en el -wal")
        } finally {
            scope.cancel()
            db.close()
            worker.close()
        }
    }

    /**
     * Auditoría de seguridad (M3): borrar un libro (con el camino de la app, [BookRepository.delete]) no deja su título
     * ni su posición en el .db ni en el -wal. Archivo propio y carpeta temporal: nunca la biblioteca de Juan.
     */
    @Test
    fun tras_borrar_un_libro_su_titulo_no_queda_en_db_ni_wal() = runBlocking {
        val titulo = "titulo-unico-secure-delete-${System.nanoTime()}"
        val db = LectorDatabase.open(context, nombre)
        val carpeta = File(context.cacheDir, "secure-delete-books").apply { deleteRecursively(); mkdirs() }
        try {
            val dao = db.books()
            val id = "00000000-0000-0000-0000-00000000d31e"
            dao.insert(BookEntity(id, titulo, null, null, 1L, null, 0.3f, "{\"href\":\"$titulo.xhtml\"}"))
            val archivo = context.getDatabasePath(nombre)
            val wal = File(archivo.path + "-wal")
            assertTrue(contiene(archivo, titulo) || contiene(wal, titulo), "control: el título debería estar antes de borrar")

            val files = BookFiles(carpeta)
            BookRepository(dao, files, BookImporter(files, dao, { error("no se usa") }, { _, _ -> })).delete(id)
            assertEquals(null, dao.get(id))

            assertFalse(contiene(archivo, titulo), "el título quedó en el .db")
            assertFalse(contiene(wal, titulo), "el título quedó en el -wal")
        } finally {
            db.close()
            carpeta.deleteRecursively()
        }
    }
}
