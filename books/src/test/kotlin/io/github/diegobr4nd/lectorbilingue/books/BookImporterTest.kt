package io.github.diegobr4nd.lectorbilingue.books

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BookImporterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val id = "123e4567-e89b-12d3-a456-426614174000"
    private lateinit var files: BookFiles
    private val dao = FakeBookDao()

    private fun importer(meta: MetadataRead = MetadataRead.Ok(BookMetadata("Título", "Autora", null))) =
        BookImporter(files, dao, readMetadata = { meta }, saveCover = { _, f -> f.writeText("png") }, clock = { 1000L }, newId = { id })
            .also { files.booksDir.mkdirs() }

    private fun source(file: File): () -> InputStream? = { file.inputStream() }

    private fun leftovers(): List<String> =
        (files.booksDir.listFiles().orEmpty().toList() + files.tmpDir.listFiles().orEmpty().toList()).filter { it.isFile }.map { it.name }

    @org.junit.Before fun setUp() { files = BookFiles(tmp.newFolder("files")) }

    @Test fun `importa un EPUB valido`() = runTest {
        val epub = TestEpub.build(tmp.root)
        val r = importer().import(source(epub), "mi-libro.epub")
        assertEquals(ImportResult.Ok(id), r)
        assertTrue(files.epub(id).exists())
        val row = dao.get(id)!!
        assertEquals("Título", row.title); assertEquals("Autora", row.author); assertEquals(1000L, row.addedAt); assertEquals(0f, row.progress)
        assertEquals(listOf("$id.epub"), leftovers())
    }

    @Test fun `titulo en blanco usa el nombre del archivo`() = runTest {
        val r = importer(MetadataRead.Ok(BookMetadata("   ", null, null))).import(source(TestEpub.build(tmp.root)), "Mi libro favorito.epub")
        assertIs<ImportResult.Ok>(r)
        assertEquals("Mi libro favorito", dao.get(id)!!.title)
    }

    @Test fun `sin titulo ni nombre usa Libro sin titulo`() = runTest {
        importer(MetadataRead.Ok(BookMetadata(null, null, null))).import(source(TestEpub.build(tmp.root)), null)
        assertEquals(BookImporter.UNTITLED, dao.get(id)!!.title)
    }

    @Test fun `demasiado grande corta la copia`() = runTest {
        val huge: () -> InputStream = {
            object : InputStream() {
                var left = ImportLimits.MAX_FILE_BYTES + 1
                override fun read(): Int = if (left-- > 0) 0 else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0) return -1
                    val n = minOf(len.toLong(), left).toInt(); left -= n; return n
                }
            }
        }
        assertEquals(ImportResult.Error(ImportError.TOO_BIG), importer().import(huge, "x.epub"))
        assertEquals(emptyList(), leftovers())
    }

    @Test fun `archivo que no es EPUB`() = runTest {
        val r = importer().import({ ByteArrayInputStream("hola".toByteArray()) }, "x.epub")
        assertEquals(ImportResult.Error(ImportError.NOT_EPUB), r)
        assertEquals(emptyList(), leftovers())
    }

    @Test fun `DRM se rechaza`() = runTest {
        val epub = TestEpub.build(tmp.root) { entry("META-INF/license.lcpl", "{}".toByteArray()) }
        assertEquals(ImportResult.Error(ImportError.DRM), importer().import(source(epub), "x.epub"))
        assertEquals(emptyList(), leftovers())
    }

    @Test fun `fallo de Readium no deja nada`() = runTest {
        val r = importer(MetadataRead.Failed(ImportError.DAMAGED)).import(source(TestEpub.build(tmp.root)), "x.epub")
        assertEquals(ImportResult.Error(ImportError.DAMAGED), r)
        assertEquals(emptyList(), leftovers())
    }

    @Test fun `fallo al guardar la fila no deja archivos`() = runTest {
        dao.failInsert = true
        val r = importer().import(source(TestEpub.build(tmp.root)), "x.epub")
        assertEquals(ImportResult.Error(ImportError.DAMAGED), r)
        assertEquals(emptyList(), leftovers())
        assertEquals(null, dao.get(id))
    }

    @Test fun `no se puede abrir el origen`() = runTest {
        assertEquals(ImportResult.Error(ImportError.DAMAGED), importer().import({ null }, "x.epub"))
    }
}
