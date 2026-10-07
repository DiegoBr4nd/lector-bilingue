package io.github.diegobr4nd.lectorbilingue.books.readium

import io.github.diegobr4nd.lectorbilingue.books.readium.HtmlSanitizer.Kind
import kotlinx.coroutines.test.runTest
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.resource.Resource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceSanitizingTest {
    @Test fun `solo pasan sin sanear los tipos inertes`() {
        for (
            mt in listOf(
                "image/png", "image/jpeg", "image/webp", "font/woff2", "application/font-woff", "application/vnd.ms-opentype",
                "application/x-font-ttf", "audio/mpeg", "video/mp4", "text/css", "TEXT/CSS; charset=utf-8",
            )
        ) {
            assertNull(ResourceSanitizing.kindFor(mt), mt)
        }
    }

    @Test fun `SVG se sanea como SVG`() {
        assertEquals(Kind.SVG, ResourceSanitizing.kindFor("image/svg+xml"))
        assertEquals(Kind.SVG, ResourceSanitizing.kindFor("Image/SVG+XML;charset=utf-8"))
    }

    @Test fun `tipos XML se sanean como XHTML`() {
        for (mt in listOf("application/xhtml+xml", "application/xml", "text/xml", "application/x-dtbncx+xml", "application/oebps-package+xml")) {
            assertEquals(Kind.XHTML, ResourceSanitizing.kindFor(mt), mt)
        }
    }

    @Test fun `HTML desconocido o sin tipo se sanea como HTML`() {
        for (mt in listOf("text/html", null, "", "text/plain", "application/octet-stream", "application/javascript", "image/svg")) {
            assertEquals(Kind.HTML, ResourceSanitizing.kindFor(mt), mt.toString())
        }
    }

    @Test fun `un fallo del sanitizador da error, nunca los bytes sin sanear`() {
        val raw = "<script>x()</script>".toByteArray()
        assertTrue(ResourceSanitizing.sanitizeSafely(raw, Kind.XHTML) { _, _ -> throw IllegalStateException("x") }.isFailure)
        assertTrue(ResourceSanitizing.sanitizeSafely(raw, Kind.XHTML) { _, _ -> throw StackOverflowError() }.isFailure)
        assertTrue(ResourceSanitizing.sanitizeSafely(raw, Kind.XHTML) { _, _ -> throw OutOfMemoryError() }.isFailure)
        val ok = ResourceSanitizing.sanitizeSafely(raw, Kind.XHTML)
        assertTrue(ok.isSuccess); assertFalse(ok.getOrNull()!!.toString(Charsets.UTF_8).contains("<script"))
    }

    // Ronda 2: el charset del tipo del manifiesto llega como Content-Type y manda sobre nuestra salida UTF-8.
    @Test fun `charset del tipo servido distinto de UTF-8 se rechaza`() {
        for (mt in listOf(
            "application/xhtml+xml; charset=iso-2022-jp", "text/html;charset=\"ISO-8859-1\"", "text/html; CHARSET = Shift_JIS",
            "application/xhtml+xml; charset=", "text/html; foo=bar; charset='utf-16'",
        )) {
            assertTrue(ResourceSanitizing.hasForeignCharset(mt), mt)
        }
        for (mt in listOf(
            null, "", "application/xhtml+xml", "text/html; charset=utf-8", "text/html;charset=\"UTF-8\"", "text/html; charset= UTF8 ",
            "application/xhtml+xml; profile=x",
        )) {
            assertFalse(ResourceSanitizing.hasForeignCharset(mt), mt.toString())
        }
    }

    @Test fun `tamano de muestreo de la portada`() {
        assertEquals(1, ResourceSanitizing.coverSampleSize(800, 1200, 2000))
        assertEquals(1, ResourceSanitizing.coverSampleSize(2000, 2000, 2000))
        assertEquals(2, ResourceSanitizing.coverSampleSize(2001, 100, 2000))
        assertEquals(16, ResourceSanitizing.coverSampleSize(30000, 30000, 2000))
        assertEquals(8192, ResourceSanitizing.coverSampleSize(1, 10_000_000, 2000))
        assertNull(ResourceSanitizing.coverSampleSize(0, 10, 2000)); assertNull(ResourceSanitizing.coverSampleSize(-1, 10, 2000))
    }

    // ---------- Tope de marcado: un capítulo enorme de etiquetas diminutas no llega a jsoup ----------

    /** Recurso de prueba que cuenta cuántos bytes entrega y si alguien pidió leerlo entero (sin rango). */
    private class SpyResource(private val bytes: ByteArray) : Resource {
        var served = 0L
        var readWithoutRange = false
        override val sourceUrl: AbsoluteUrl? = null
        override suspend fun properties(): Try<Resource.Properties, ReadError> = Try.success(Resource.Properties())
        override suspend fun length(): Try<Long, ReadError> = Try.success(bytes.size.toLong())
        override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
            if (range == null) readWithoutRange = true
            val r = range ?: 0L until bytes.size
            val first = r.first.coerceIn(0L, bytes.size.toLong()).toInt()
            val end = (r.last + 1).coerceIn(first.toLong(), bytes.size.toLong()).toInt()
            return Try.success(bytes.copyOfRange(first, end).also { served += it.size })
        }
        override fun close() = Unit
    }

    private fun chapter(paragraphs: Int) =
        ("""<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>""" +
            "<p>a</p>".repeat(paragraphs) + "<script>x()</script></body></html>").toByteArray()

    @Test fun `capitulo por encima del tope sirve una pagina de aviso sin leerlo entero`() = runTest {
        val spy = SpyResource(chapter(10_000)) // unos 80 KB
        val out = ResourceSanitizing.sanitizing(spy, Kind.XHTML, maxBytes = 1024).read().getOrNull()!!.toString(Charsets.UTF_8)
        assertTrue(out.contains(ResourceSanitizing.OVERSIZE_MESSAGE)); assertTrue(out.contains(HtmlSanitizer.CSP))
        assertFalse(out.contains("<p>a</p>")); assertFalse(out.contains("<script", ignoreCase = true))
        assertFalse(spy.readWithoutRange, "pidió el recurso entero")
        assertTrue(spy.served <= 1025, "leyó ${spy.served} bytes")
    }

    @Test fun `pagina de aviso en modo HTML y SVG`() = runTest {
        val html = ResourceSanitizing.sanitizing(SpyResource(chapter(10_000)), Kind.HTML, maxBytes = 1024).read().getOrNull()!!.toString(Charsets.UTF_8)
        assertTrue(html.contains(ResourceSanitizing.OVERSIZE_MESSAGE)); assertTrue(html.contains(HtmlSanitizer.CSP)); assertFalse(html.contains("<p>a</p>"))
        val svg = ResourceSanitizing.sanitizing(SpyResource(chapter(10_000)), Kind.SVG, maxBytes = 1024).read().getOrNull()!!.toString(Charsets.UTF_8)
        assertTrue(svg.contains("<svg")); assertFalse(svg.contains("<p>a</p>"))
    }

    @Test fun `capitulo por debajo del tope se sanea normal`() = runTest {
        val bytes = chapter(10)
        val spy = SpyResource(bytes)
        val out = ResourceSanitizing.sanitizing(spy, Kind.XHTML, maxBytes = bytes.size.toLong()).read().getOrNull()!!.toString(Charsets.UTF_8)
        assertTrue(out.contains("<p>a</p>")); assertTrue(out.contains(HtmlSanitizer.CSP))
        assertFalse(out.contains("<script", ignoreCase = true)); assertFalse(out.contains(ResourceSanitizing.OVERSIZE_MESSAGE))
    }

    @Test fun `el tope real es de 8 MB`() {
        assertEquals(8L * 1024 * 1024, ResourceSanitizing.MAX_MARKUP_BYTES)
    }

    // ---------- Billion laughs en el OPF y el NCX (seguridad, bajo) ----------
    // Un capítulo puede enlazar al OPF o al NCX y Readium los sirve al WebView: pasan por el mismo saneado.
    // (Readium también los lee al importar con el analizador XML de Android; eso solo se puede probar en el
    // teléfono: MaliciousEpubOnDeviceTest, x5 y x6.)

    /** Entidades encadenadas: lol9 = 10^9 veces "lol" (unos 3 GB si alguien las expandiera). */
    private val lol = (1..9).joinToString("") { i -> """<!ENTITY lol$i "${"&lol${i - 1};".repeat(10)}">""" }

    private val opfLaughs = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE package [ <!ENTITY lol0 "lol"> $lol ]>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:1</dc:identifier>
<dc:title>&lol9;</dc:title><dc:language>es</dc:language></metadata>
<manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine>
</package>"""

    private val ncxLaughs = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx [ <!ENTITY lol0 "lol"> $lol ]>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>&lol9;</text></docTitle>
<navMap><navPoint id="n1" playOrder="1"><navLabel><text>&lol9;</text></navLabel><content src="c1.xhtml"/></navPoint></navMap>
</ncx>"""

    private fun assertLaughsDefused(xml: String, mediaType: String) = runTest {
        val kind = ResourceSanitizing.kindFor(mediaType)!!
        val start = System.nanoTime()
        val out = ResourceSanitizing.sanitizing(SpyResource(xml.toByteArray()), kind).read()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 10_000, "$mediaType tardó $ms ms")
        // Vale un error limpio o una página sin el contenido expandido.
        val html = out.getOrNull()?.toString(Charsets.UTF_8) ?: return@runTest
        assertFalse(html.contains("<!DOCTYPE", ignoreCase = true), mediaType); assertFalse(html.contains("<!ENTITY"), mediaType)
        assertFalse(Regex("(lol){342,}").containsMatchIn(html), "$mediaType: tramo de lol de más de 1 KB")
        assertTrue(html.length < 10_000, "$mediaType: ${html.length} caracteres")
    }

    @Test fun `billion laughs en el OPF servido no se expande`() = assertLaughsDefused(opfLaughs, "application/oebps-package+xml")

    @Test fun `billion laughs en el NCX servido no se expande`() = assertLaughsDefused(ncxLaughs, "application/x-dtbncx+xml")
}
