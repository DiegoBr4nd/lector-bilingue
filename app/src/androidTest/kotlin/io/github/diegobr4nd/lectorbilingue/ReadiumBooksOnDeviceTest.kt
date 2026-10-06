package io.github.diegobr4nd.lectorbilingue

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.books.ImportError
import io.github.diegobr4nd.lectorbilingue.books.readium.ReadiumBooks
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Readium de verdad en el teléfono, con EPUBs inventados en la caché de la app. */
@RunWith(AndroidJUnit4::class)
class ReadiumBooksOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "readium-test").apply { deleteRecursively(); mkdirs() }
    private val books = ReadiumBooks(context)

    @Test fun leeTituloYAutor() = runBlocking {
        val m = books.metadata(TestEpub.build(dir)).getOrNull()!!
        assertEquals("Libro de prueba", m.title); assertEquals("Autora Inventada", m.author); assertNull(m.cover)
    }

    @Test fun sinTituloDevuelveNull() = runBlocking {
        assertNull(books.metadata(TestEpub.build(dir) { title = null }).getOrNull()!!.title)
    }

    @Test fun opfRotoEsDamaged() = runBlocking {
        val f = TestEpub.build(dir) { replace("OEBPS/content.opf", "<<no es xml".toByteArray()) }
        assertEquals(ImportError.DAMAGED, books.metadata(f).failureOrNull())
    }

    @Test fun xxeNoLeeArchivosDelTelefono() = runBlocking {
        val secreto = File(context.filesDir, "xxe-secreto.txt").apply { writeText("SECRETO_XXE") }
        try {
            // OPF con una entidad externa que apunta a un archivo privado de la app.
            val opf = """<?xml version="1.0"?><!DOCTYPE package [<!ENTITY xxe SYSTEM "file://${secreto.absolutePath}">]>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">x</dc:identifier><dc:title>&xxe;</dc:title><dc:language>es</dc:language></metadata>
<manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>"""
            val g = TestEpub.build(dir, "xxe.epub") { replace("OEBPS/content.opf", opf.toByteArray()) }
            val title = books.metadata(g).getOrNull()?.title.orEmpty()
            assertFalse(title.contains("SECRETO_XXE"))
        } finally { secreto.delete() }
    }

    // Guarda de que el sanitizador corre de verdad dentro de Readium (en 3.4.0 el del constructor no se llama).
    @Test fun elHtmlServidoNoTraeScripts() = runBlocking {
        val f = TestEpub.build(dir, "js.epub") {
            replace(
                "OEBPS/c1.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>c</title></head><body><p onclick="x()">a</p><script>y()</script></body></html>""".toByteArray(),
            )
        }
        val pub: Publication = books.open(f).getOrNull()!!
        try {
            val html = pub.get(Url("OEBPS/c1.xhtml")!!)!!.read().getOrNull()!!.toString(Charsets.UTF_8)
            assertFalse(html.contains("<script", true)); assertFalse(html.contains("onclick", true))
            // Una sola CSP aunque Readium aplique el saneado dos veces.
            assertEquals(1, Regex("Content-Security-Policy", RegexOption.IGNORE_CASE).findAll(html).count())
        } finally { pub.close() }
    }
}
