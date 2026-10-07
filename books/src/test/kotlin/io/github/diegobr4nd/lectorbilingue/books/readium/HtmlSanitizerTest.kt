package io.github.diegobr4nd.lectorbilingue.books.readium

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
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

    // Segunda barrera (seguridad M3): si un script en línea se colara, la CSP lo frena; sin marcos ni workers.
    @Test fun `la CSP no permite scripts en linea ni marcos ni workers`() {
        val directives = HtmlSanitizer.CSP.split(';').map { it.trim() }.associate { d -> d.substringBefore(' ') to d.substringAfter(' ') }
        assertFalse(directives.getValue("script-src").contains("'unsafe-inline'"))
        assertFalse(directives.getValue("script-src").contains("'unsafe-eval'"))
        assertEquals("'none'", directives["frame-src"])
        assertEquals("'none'", directives["worker-src"])
        assertEquals("'none'", directives["manifest-src"])
    }

    @Test fun `crea head si no hay`() {
        val out = HtmlSanitizer.sanitize("""<html xmlns="http://www.w3.org/1999/xhtml"><body><p>a</p></body></html>""")
        assertTrue(out.contains("Content-Security-Policy"))
    }

    @Test fun `SVG sin script ni on`() {
        val out = HtmlSanitizer.sanitize("""<svg xmlns="http://www.w3.org/2000/svg"><script>x()</script><rect onload="y()"/></svg>""", HtmlSanitizer.Kind.SVG)
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

    // --- Ronda 1 de revisión: desvíos del sanitizador ---

    /** Vuelve a leer el resultado como lo haría el navegador y dice si queda algún atributo on*. */
    private fun hasOnAttribute(out: String, html: Boolean): Boolean {
        val parser = if (html) Parser.htmlParser() else Parser.xmlParser()
        return Jsoup.parse(out, "", parser).allElements.any { el ->
            el.attributes().asList().any { it.key.substringAfterLast(':').lowercase().startsWith("on") }
        }
    }

    @Test fun `quita script con prefijo de espacio de nombres`() {
        val out = HtmlSanitizer.sanitize(
            """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:h="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body><h:script>alert(1)</h:script><s:script xmlns:s="http://www.w3.org/2000/svg">alert(2)</s:script><h:iframe src="x"/><h:object data="x"/><h:embed src="x"/><h:base href="https://x/"/><h:meta http-equiv="refresh" content="0;url=https://x"/><p>a</p></body></html>""",
        )
        // "<h:object" y no "object": la CSP contiene "object-src".
        for (t in listOf("script>", "alert(", "<h:iframe", "<h:object", "<h:embed", "<h:base", "refresh")) assertFalse(out.contains(t, true), t)
        assertEquals(1, cspCount(out)); assertTrue(out.contains("<p>a</p>"))
    }

    @Test fun `raiz h-html con prefijo recibe la CSP`() {
        val out = HtmlSanitizer.sanitize(
            """<h:html xmlns:h="http://www.w3.org/1999/xhtml"><h:head><h:title>t</h:title></h:head><h:body><h:script>alert(1)</h:script><h:p>a</h:p></h:body></h:html>""",
        )
        assertFalse(out.contains("alert(")); assertEquals(1, cspCount(out))
        assertTrue(out.contains("<h:meta"), out)
    }

    @Test fun `documento sin html no se sirve tal cual`() {
        val out = HtmlSanitizer.sanitize("""<foo xmlns:h="http://www.w3.org/1999/xhtml"><h:p onclick="x()">a</h:p><h:img src="i.png"/></foo>""")
        assertEquals(1, cspCount(out)); assertFalse(out.contains("<foo")); assertFalse(out.contains("onclick"))
    }

    @Test fun `quita javascript en cualquier atributo y prefijo`() {
        val out = HtmlSanitizer.sanitize(
            xhtml("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:x="http://www.w3.org/1999/xlink"><a x:href="javascript:alert(1)"><text>a</text></a><a q:href=" vbscript:x" xmlns:q="http://www.w3.org/1999/xlink"><text>b</text></a></svg><a href="data:text/html,&lt;script&gt;x&lt;/script&gt;">c</a><p h:onclick="x()" xmlns:h="http://www.w3.org/1999/xhtml">d</p><img src="data:image/png;base64,AAAA"/>"""),
        )
        for (t in listOf("javascript", "vbscript", "data:text/html", "onclick")) assertFalse(out.contains(t, true), t)
        assertTrue(out.contains("data:image/png;base64,AAAA"))
    }

    @Test fun `comentario dentro de title no revive en modo HTML`() {
        val payload = """<html><head><title><!--</title><img src="x" onerror="alert(1)">--></title></head><body/></html>"""
        val asHtml = HtmlSanitizer.sanitize(payload, HtmlSanitizer.Kind.HTML)
        assertFalse(hasOnAttribute(asHtml, html = true), asHtml); assertFalse(asHtml.contains("onerror"))
        assertEquals(1, cspCount(asHtml))
        val asXml = HtmlSanitizer.sanitize(payload, HtmlSanitizer.Kind.XHTML)
        assertFalse(hasOnAttribute(asXml, html = true), asXml); assertFalse(hasOnAttribute(asXml, html = false), asXml)
    }

    @Test fun `CDATA en style queda como texto y no revive como HTML`() {
        val out = HtmlSanitizer.sanitize(xhtml("<p>a</p>", head = """<title>t</title><style><![CDATA[p{color:red}</style><img src=x onerror=alert(1)>]]></style>"""))
        assertFalse(out.contains("<![CDATA[")); assertFalse(hasOnAttribute(out, html = true), out); assertTrue(out.contains("p{color:red}"))
    }

    @Test fun `noscript svg y math se quitan en modo HTML`() {
        val out = HtmlSanitizer.sanitize(
            """<html><body><noscript><p title="</noscript><img src=x onerror=alert(1)>"></p></noscript><math><mtext><table><mglyph><style><img src=x onerror=alert(2)></style></mglyph></table></mtext></math><svg><p>z</p></svg><p>ok</p></body></html>""",
            HtmlSanitizer.Kind.HTML,
        )
        assertFalse(hasOnAttribute(out, html = true), out); assertFalse(out.contains("onerror")); assertTrue(out.contains("<p>ok</p>"))
        assertFalse(out.contains("<noscript")); assertFalse(out.contains("<math")); assertFalse(out.contains("<svg"))
    }

    @Test fun `quita xml-stylesheet y otras instrucciones de proceso`() {
        val out = HtmlSanitizer.sanitize(
            """<?xml version="1.0" encoding="UTF-8"?><?xml-stylesheet type="text/xsl" href="x.xsl"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body><?php echo 1 ?><p>a</p></body></html>""",
        )
        assertFalse(out.contains("xml-stylesheet")); assertFalse(out.contains("x.xsl")); assertFalse(out.contains("php"))
        assertTrue(out.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), out)
    }

    @Test fun `quita DOCTYPE con entidades internas`() {
        val out = HtmlSanitizer.sanitize(
            """<?xml version="1.0"?><!DOCTYPE html [<!ENTITY x "<script>alert(1)</script>">]><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body><p>&x;</p></body></html>""",
        )
        assertFalse(out.contains("<!ENTITY")); assertFalse(out.contains("<!DOCTYPE")); assertFalse(out.contains("<script", true))
    }

    @Test fun `quita animaciones SVG que cambian href`() {
        val out = HtmlSanitizer.sanitize(
            """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"><a><set attributeName="href" to="javascript:alert(1)"/><animate attributeName="xlink:href" values="x;javascript:alert(2)"/><animateMotion attributeName=" HREF " values="x"/><animateTransform attributeName="transform" type="rotate" from="0" to="90"/><text>a</text></a></svg>""",
            HtmlSanitizer.Kind.SVG,
        )
        assertFalse(out.contains("<set")); assertFalse(out.contains("<animate ")); assertFalse(out.contains("animateMotion"))
        assertTrue(out.contains("animateTransform")); assertFalse(out.contains("javascript"))
    }

    @Test fun `lee ISO-8859-1 por la declaracion XML y sale en UTF-8`() {
        val input = """<?xml version="1.0" encoding="ISO-8859-1"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body><p>Canción</p></body></html>""".toByteArray(Charsets.ISO_8859_1)
        val out = HtmlSanitizer.sanitize(input, HtmlSanitizer.Kind.XHTML).toString(Charsets.UTF_8)
        assertTrue(out.contains("<p>Canción</p>"), out); assertTrue(out.contains("encoding=\"UTF-8\"")); assertFalse(out.contains("ISO-8859-1", true))
    }

    @Test fun `lee ISO-8859-1 por meta charset en modo HTML y sale en UTF-8`() {
        val input = """<html><head><meta charset="iso-8859-1"><title>t</title></head><body><p>Canción</p></body></html>""".toByteArray(Charsets.ISO_8859_1)
        val out = HtmlSanitizer.sanitize(input, HtmlSanitizer.Kind.HTML).toString(Charsets.UTF_8)
        assertTrue(out.contains("<p>Canción</p>"), out); assertTrue(out.contains("charset=\"UTF-8\"")); assertFalse(out.contains("iso-8859-1", true))
    }

    @Test fun `quita el BOM UTF-8`() {
        val input = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xhtml("<p>Canción</p>").toByteArray(Charsets.UTF_8)
        val out = HtmlSanitizer.sanitize(input, HtmlSanitizer.Kind.XHTML).toString(Charsets.UTF_8)
        assertTrue(out.startsWith("<?xml"), out); assertTrue(out.contains("Canción"))
    }

    @Test fun `idempotente en los tres modos`() {
        val xml = xhtml("<p>Canción</p>", head = "<title>t</title><style><![CDATA[a{}]]></style>")
        for (kind in HtmlSanitizer.Kind.values()) {
            val once = HtmlSanitizer.sanitize(xml.toByteArray(), kind)
            assertTrue(once.contentEquals(HtmlSanitizer.sanitize(once, kind)), kind.name)
        }
    }
    @Test fun `quita link con pistas de red en cualquier mayuscula y prefijo`() {
        val head = """<title>t</title>
            <link rel="preconnect" href="https://espia.example"/>
            <link rel="DNS-Prefetch" href="https://espia.example"/>
            <link rel="prefetch" href="https://espia.example/a"/>
            <link rel="prerender" href="https://espia.example/b"/>
            <link rel="preload" href="https://espia.example/c" as="image"/>
            <link rel="modulepreload" href="https://espia.example/d.js"/>
            <link rel="stylesheet  preconnect" href="https://espia.example/e.css"/>
            <link rel="stylesheet" href="estilo.css"/>"""
        for (kind in listOf(HtmlSanitizer.Kind.XHTML, HtmlSanitizer.Kind.HTML)) {
            val out = HtmlSanitizer.sanitize(xhtml("<p>a</p>", head), kind)
            assertFalse(out.contains("espia.example"), "$kind: $out")
            assertTrue(out.contains("estilo.css"), "$kind: $out")
        }
        // Con prefijo solo tiene sentido en XML (en modo HTML el prefijo no existe).
        val prefixed = HtmlSanitizer.sanitize(
            xhtml("<p>a</p>", """<title>t</title><h:link xmlns:h="http://www.w3.org/1999/xhtml" h:rel="prefetch" href="https://espia.example/f"/>"""),
        )
        assertFalse(prefixed.contains("espia.example"), prefixed)
    }

    @Test fun `quita ping srcdoc y attributionsrc`() {
        val out = HtmlSanitizer.sanitize(
            xhtml("""<a href="c2.xhtml" ping="https://espia.example/p" PING="https://espia.example/q">a</a>""" +
                """<img src="i.png" attributionsrc="https://espia.example/r"/><p h:srcdoc="x" xmlns:h="http://www.w3.org/1999/xhtml">b</p>"""),
        )
        assertFalse(out.contains("ping", true)); assertFalse(out.contains("espia.example")); assertFalse(out.contains("srcdoc", true))
        assertTrue(out.contains("href=\"c2.xhtml\"")); assertTrue(out.contains("src=\"i.png\""))
    }

    // Con target="_blank" el WebView del Lector descarta el clic (no abre ventanas nuevas): el enlace externo no
    // haría nada y nunca saldría el diálogo. Sin target se comporta como cualquier otro enlace.
    @Test fun `quita target de los enlaces`() {
        val out = HtmlSanitizer.sanitize(xhtml("""<a href="https://example.org/" target="_blank">a</a><a href="c2.xhtml" TARGET="x">b</a>"""))
        assertFalse(out.contains("target", true)); assertFalse(out.contains("_blank"))
        assertTrue(out.contains("href=\"https://example.org/\"")); assertTrue(out.contains("href=\"c2.xhtml\""))
    }
}
