package io.github.diegobr4nd.lectorbilingue.books

import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BookRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val id = "123e4567-e89b-12d3-a456-426614174000"

    @Test fun `borrar quita fila, epub y portada`() = runTest {
        val files = BookFiles(tmp.root).apply { booksDir.mkdirs(); epub(id).writeText("e"); cover(id).writeText("c") }
        val dao = FakeBookDao().apply { insert(BookEntity(id, "T", null, "$id.cover.png", 1, null, 0f, null)) }
        val repo = BookRepository(dao, files, BookImporter(files, dao, { error("no") }, { _, _ -> }))
        repo.delete(id)
        assertNull(dao.get(id)); assertFalse(files.epub(id).exists()); assertFalse(files.cover(id).exists())
    }

    @Test fun `libros con portada como archivo y progreso`() = runTest {
        val files = BookFiles(tmp.root)
        val dao = FakeBookDao().apply { insert(BookEntity(id, "T", "A", "$id.cover.png", 1, null, 0.5f, null)) }
        val repo = BookRepository(dao, files, BookImporter(files, dao, { error("no") }, { _, _ -> }))
        assertEquals(listOf(Book(id, "T", "A", files.cover(id), 0.5f, null)), repo.books.first())
    }

    @Test fun `abrir marca la hora`() = runTest {
        val files = BookFiles(tmp.root)
        val dao = FakeBookDao().apply { insert(BookEntity(id, "T", null, null, 1, null, 0f, null)) }
        val repo = BookRepository(dao, files, BookImporter(files, dao, { error("no") }, { _, _ -> }), clock = { 77L })
        repo.markOpened(id)
        assertEquals(77L, dao.get(id)!!.lastOpenedAt)
    }

    @Test fun `progreso fuera de rango se recorta`() = runTest {
        val files = BookFiles(tmp.root)
        val dao = FakeBookDao().apply { insert(BookEntity(id, "T", null, null, 1, null, 0f, null)) }
        val repo = BookRepository(dao, files, BookImporter(files, dao, { error("no") }, { _, _ -> }))
        repo.savePosition(id, "{}", 1.7f)
        assertEquals(1f, dao.get(id)!!.progress)
    }
}
