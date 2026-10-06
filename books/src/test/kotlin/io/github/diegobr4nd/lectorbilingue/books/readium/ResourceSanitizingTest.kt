package io.github.diegobr4nd.lectorbilingue.books.readium

import io.github.diegobr4nd.lectorbilingue.books.readium.HtmlSanitizer.Kind
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
}
