package io.github.diegobr4nd.lectorbilingue

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.books.ImportError
import io.github.diegobr4nd.lectorbilingue.books.readium.ReadiumBooks
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private val conScript =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>c</title></head><body><p onclick="x()">a</p><script>y()</script></body></html>""".toByteArray()

    private fun opfCon(items: String, spine: String) = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">x</dc:identifier><dc:title>Libro de prueba</dc:title><dc:language>es</dc:language></metadata>
<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$items</manifest><spine>$spine</spine></package>""".toByteArray()

    // Ronda 1: el tipo lo decide el manifiesto (o Android por la extensión), nunca la extensión sola.
    @Test fun capitulosSinExtensionHtmlTambienSeSanean() = runBlocking {
        val f = TestEpub.build(dir, "ext.epub") {
            replace(
                "OEBPS/content.opf",
                opfCon(
                    items = """<item id="c1" href="c1.xml" media-type="application/xhtml+xml"/><item id="c2" href="c2" media-type="application/xhtml+xml"/><item id="c3" href="c3.html" media-type="text/html"/>""",
                    spine = """<itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/>""",
                ),
            )
            entry("OEBPS/c1.xml", conScript); entry("OEBPS/c2", conScript); entry("OEBPS/c3.html", conScript)
            entry("OEBPS/suelto", conScript) // fuera del manifiesto y sin extensión
        }
        val pub: Publication = books.open(f).getOrNull()!!
        try {
            for (href in listOf("OEBPS/c1.xml", "OEBPS/c2", "OEBPS/c3.html", "OEBPS/suelto")) {
                val html = pub.get(Url(href)!!)!!.read().getOrNull()!!.toString(Charsets.UTF_8)
                assertFalse(html.contains("<script", true), href); assertFalse(html.contains("onclick", true), href)
                assertEquals(1, Regex("Content-Security-Policy", RegexOption.IGNORE_CASE).findAll(html).count(), href)
            }
        } finally { pub.close() }
    }

    // Ronda 2: el charset del tipo del manifiesto llega al WebView como Content-Type; si no es UTF-8, no se sirve.
    @Test fun charsetAjenoEnElManifiestoNoSeSirve() = runBlocking {
        val f = TestEpub.build(dir, "charset.epub") {
            replace(
                "OEBPS/content.opf",
                opfCon(
                    items = """<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml; charset=iso-2022-jp"/><item id="c2" href="c2.xhtml" media-type="application/xhtml+xml; charset=&quot;UTF-8&quot;"/>""",
                    spine = """<itemref idref="c1"/><itemref idref="c2"/>""",
                ),
            )
            replace("OEBPS/c1.xhtml", conScript); entry("OEBPS/c2.xhtml", conScript)
        }
        val pub: Publication = books.open(f).getOrNull()!!
        try {
            // Condición previa: Readium conserva el parámetro, así que acabaría en el Content-Type.
            val tipo = pub.linkWithHref(Url("OEBPS/c1.xhtml")!!)!!.mediaType.toString()
            assertTrue(tipo.contains("iso-2022-jp", ignoreCase = true), tipo)
            assertTrue(pub.get(Url("OEBPS/c1.xhtml")!!)!!.read().isFailure)
            val c2 = pub.get(Url("OEBPS/c2.xhtml")!!)!!.read().getOrNull()!!.toString(Charsets.UTF_8)
            assertFalse(c2.contains("<script", true)); assertEquals(1, Regex("Content-Security-Policy").findAll(c2).count())
        } finally { pub.close() }
    }

    private fun png(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** PNG de pocos bytes que DECLARA side × side (IDAT corto): decodificarlo entero pediría side² × 4 bytes. */
    private fun pngBomba(side: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun chunk(type: String, data: ByteArray) {
            val t = type.toByteArray(Charsets.US_ASCII)
            out.write(ByteBuffer.allocate(4).putInt(data.size).array()); out.write(t); out.write(data)
            out.write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(t); update(data) }.value.toInt()).array())
        }
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunk("IHDR", ByteBuffer.allocate(13).putInt(side).putInt(side).put(8).put(2).put(0).put(0).put(0).array())
        val zeros = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o).use { it.write(ByteArray(64 * 1024)) } }.toByteArray()
        chunk("IDAT", zeros)
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun conPortada(nombre: String, bytes: ByteArray) = TestEpub.build(dir, nombre) {
        replace(
            "OEBPS/content.opf",
            opfCon(
                items = """<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/><item id="cov" href="cover.png" media-type="image/png" properties="cover-image"/>""",
                spine = """<itemref idref="c1"/>""",
            ),
        )
        entry("OEBPS/cover.png", bytes)
    }

    @Test fun leeLaPortada() = runBlocking {
        val cover = books.metadata(conPortada("portada.epub", png(30, 40))).getOrNull()!!.cover!!
        assertEquals(30, cover.width); assertEquals(40, cover.height)
    }

    // 30000² pediría 3,6 GB; 10000² "solo" 400 MB, que Android sí intenta reservar si se decodifica entero.
    @Test fun portadaGiganteNoAgotaLaMemoria() = runBlocking {
        for (side in listOf(30000, 10000)) {
            val m = books.metadata(conPortada("bomba$side.epub", pngBomba(side))).getOrNull()!!
            assertEquals("Libro de prueba", m.title)
            val cover = m.cover
            assertTrue(cover == null || (cover.width <= 2000 && cover.height <= 2000), "$side: ${cover?.width}x${cover?.height}")
        }
    }
}
