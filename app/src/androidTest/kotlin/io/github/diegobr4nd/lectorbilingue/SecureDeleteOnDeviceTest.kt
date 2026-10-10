package io.github.diegobr4nd.lectorbilingue

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.diegobr4nd.lectorbilingue.books.db.LectorDatabase
import io.github.diegobr4nd.lectorbilingue.books.db.TranslationEntity
import java.io.File
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
 * ("secure-delete-test.db"), nunca lector.db de Juan.
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

    @Test
    fun tras_borrar_la_frase_no_queda_en_db_ni_wal() = runBlocking {
        val frase = "frase-unica-secure-delete-${System.nanoTime()}"
        val db = LectorDatabase.open(context, nombre)
        try {
            val dao = db.translations()
            dao.put(TranslationEntity("k1", frase, 1L))
            val archivo = context.getDatabasePath(nombre)
            // Control: antes de borrar, tras el checkpoint, la frase SI esta en el archivo.
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            assertTrue(contiene(archivo, frase), "control: la frase debería estar antes de borrar")

            dao.deleteAll()
            assertEquals(0, dao.count())
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()

            assertFalse(contiene(archivo, frase), "la frase quedó en el .db")
            assertFalse(contiene(File(archivo.path + "-wal"), frase), "la frase quedó en el -wal")
        } finally {
            db.close()
        }
    }
}
