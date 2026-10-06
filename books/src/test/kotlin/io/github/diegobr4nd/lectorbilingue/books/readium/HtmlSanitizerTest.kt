package io.github.diegobr4nd.lectorbilingue.books.readium

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlSanitizerTest {
    private fun xhtml(body: String, head: String = "<title>t</title>") =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head>$head</head><body>$body</body></html>"""

    /** Cuántas veces aparece la CSP (la nuestra) en el resultado. */
    private fun cspCount(out: String) = Regex("Content-Security-Policy", RegexOption.IGNORE_CASE).findAll(out).count()

    // "<script" y no "script": la CSP insertada contiene "script-src" a propósito.
    @Test fun `quita script`() {
        val out = HtmlSanitizer.sanitize(xhtml("<p>hola</p><script>alert(1)</script><SCRIPT src='x.js'></SCRIPT>"))
        assertFalse(out.contains("<script", ignoreCase = true)); assertTrue(out.contains("<p>hola</p>"))
        assertFalse(out.contains("alert(1)")); assertFalse(out.contains("x.js"))
    }

    @Test fun `quita atributos on`() {
        val out = HtmlSanitizer.sanitize(xhtml("""<p onclick="x()" ONLOAD="y()" class="c">a</p><img src="i.png" onerror="z()"/>"""))
        assertFalse(out.contains("onclick", true)); assertFalse(out.contains("onload", true)); assertFalse(out.contains("onerror", true))
        assertTrue(out.contains("class=\"c\""))
    }

    @Test fun `quita enlaces javascript aunque lleven espacios o mayusculas`() {
        val out = HtmlSanitizer.sanitize(xhtml("""<a href=" JavaScript:alert(1)">a</a><a href="java&#x09;script:x">b</a><a href="c2.xhtml">c</a>"""))
        assertFalse(out.contains("javascript", true)); assertFalse(out.contains("java\tscript", true))
        assertTrue(out.contains("href=\"c2.xhtml\""))
    }

    @Test fun `quita iframe object embed form base y meta refresh`() {
        val out = HtmlSanitizer.sanitize(
            xhtml(
                """<iframe src="https://x"/><object data="x"/><embed src="x"/><form action="https://x"><input/></form>""",
                head = """<title>t</title><base href="https://x/"/><meta http-equiv="refresh" content="0;url=https://x"/>""",
            ),
        )
        for (t in listOf("<iframe", "<object", "<embed", "<form", "<base", "refresh")) assertFalse(out.contains(t, true), t)
    }

    @Test fun `inserta la CSP en head`() {
        val out = HtmlSanitizer.sanitize(xhtml("<p>a</p>"))
        assertTrue(out.contains("Content-Security-Policy")); assertTrue(out.contains("connect-src 'none'"))
        assertEquals(1, cspCount(out))
    }

    @Test fun `crea head si no hay`() {
        val out = HtmlSanitizer.sanitize("""<html xmlns="http://www.w3.org/1999/xhtml"><body><p>a</p></body></html>""")
        assertTrue(out.contains("Content-Security-Policy"))
    }

    @Test fun `SVG sin script ni on`() {
        val out = HtmlSanitizer.sanitize("""<svg xmlns="http://www.w3.org/2000/svg"><script>x()</script><rect onload="y()"/></svg>""", isSvg = true)
        assertFalse(out.contains("script", true)); assertFalse(out.contains("onload", true))
    }

    @Test fun `conserva texto con acentos y entidades basicas`() {
        val out = HtmlSanitizer.sanitize(xhtml("<p>Canción &amp; señal</p>"))
        assertTrue(out.contains("Canción &amp; señal"))
    }

    // Readium 3.4.0 aplica el saneado dos veces (spike, P3): el resultado no debe cambiar.
    @Test fun `sanear dos veces da lo mismo y una sola CSP`() {
        val once = HtmlSanitizer.sanitize(xhtml("<p onclick=\"x()\">a</p><script>y()</script>"))
        val twice = HtmlSanitizer.sanitize(once)
        assertEquals(once, twice)
        assertEquals(1, cspCount(twice))
    }

    @Test fun `la CSP del libro se cambia por la nuestra`() {
        val out = HtmlSanitizer.sanitize(
            xhtml("<p>a</p>", head = """<title>t</title><meta http-equiv="content-security-policy" content="default-src *"/>"""),
        )
        assertEquals(1, cspCount(out)); assertFalse(out.contains("default-src *")); assertTrue(out.contains(HtmlSanitizer.CSP))
    }
}
