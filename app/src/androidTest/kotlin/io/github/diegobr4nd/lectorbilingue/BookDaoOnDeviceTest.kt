package io.github.diegobr4nd.lectorbilingue

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import io.github.diegobr4nd.lectorbilingue.books.db.LectorDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Room en memoria: no toca la base real de la app en el Pixel. */
@RunWith(AndroidJUnit4::class)
class BookDaoOnDeviceTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, LectorDatabase::class.java,
    ).build()
    private val dao = db.books()

    @After fun close() = db.close()

    private fun book(id: String, added: Long, opened: Long? = null) =
        BookEntity(id, "T$id", null, null, added, opened, 0f, null)

    @Test fun ordenPorUltimoAbiertoYLuegoPorAnadido() = runBlocking {
        dao.insert(book("a", added = 1))
        dao.insert(book("b", added = 2))
        dao.insert(book("c", added = 3, opened = 10))
        assertEquals(listOf("c", "b", "a"), dao.observeAll().first().map { it.id })
    }

    @Test fun guardarPosicionYMarcarAbierto() = runBlocking {
        dao.insert(book("a", added = 1))
        dao.savePosition("a", "{\"href\":\"c1.xhtml\"}", 0.42f)
        dao.markOpened("a", 99)
        val a = dao.get("a")!!
        assertEquals(0.42f, a.progress); assertEquals("{\"href\":\"c1.xhtml\"}", a.locator); assertEquals(99L, a.lastOpenedAt)
    }

    @Test fun borrar() = runBlocking {
        dao.insert(book("a", added = 1))
        dao.delete("a")
        assertNull(dao.get("a"))
    }
}
