package io.github.diegobr4nd.lectorbilingue.books

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `rutas dentro de files-books`() {
        val f = BookFiles(tmp.root)
        val id = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals(java.io.File(tmp.root, "books/$id.epub"), f.epub(id))
        assertEquals(java.io.File(tmp.root, "books/$id.cover.png"), f.cover(id))
        assertTrue(f.newTmp().parentFile == f.tmpDir)
    }

    @Test fun `id que no es UUID se rechaza`() {
        val f = BookFiles(tmp.root)
        assertFailsWith<IllegalArgumentException> { f.epub("../../x") }
    }

    @Test fun `cleanTmp borra restos`() {
        val f = BookFiles(tmp.root)
        f.newTmp().writeText("resto")
        f.cleanTmp()
        assertEquals(0, f.tmpDir.listFiles()?.size ?: 0)
    }

    @Test fun `deleteBook borra epub y portada`() {
        val f = BookFiles(tmp.root)
        val id = "123e4567-e89b-12d3-a456-426614174000"
        f.booksDir.mkdirs(); f.epub(id).writeText("e"); f.cover(id).writeText("c")
        f.deleteBook(id)
        assertFalse(f.epub(id).exists()); assertFalse(f.cover(id).exists())
    }
}
