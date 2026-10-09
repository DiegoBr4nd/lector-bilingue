package io.github.diegobr4nd.lectorbilingue.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParagraphScriptsTest {
    private val labels = CardLabels("Traducción", "Traduciendo…", "Preparando el traductor…")
    private val forbidden = listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "Function(")

    @Test fun `el texto hostil va como dato JSON y no rompe el script`() {
        val evil = "\"); alert(1); (\" </script><img src=x onerror=alert(2)>   fin"
        val js = ParagraphScripts.insert(3, Card.Text(evil), labels)
        assertFalse(js.contains("</script>"), "</script> sin escapar")
        assertFalse(js.contains(" "))
        assertTrue(js.contains("textContent"))
        forbidden.forEach { assertFalse(js.contains(it), "usa $it") }
    }

    @Test fun `ningun script usa marcado ni atributos on`() {
        val all = listOf(
            ParagraphScripts.find(1.0, 2.0),
            ParagraphScripts.remove(1),
            ParagraphScripts.indexAt(1.0, 2.0),
            ParagraphScripts.removeAll("OEBPS/c1.xhtml"),
            ParagraphScripts.insert(0, Card.Skeleton, labels),
            ParagraphScripts.insert(0, Card.Preparing, labels),
            ParagraphScripts.insert(0, Card.MissingModel("Falta el modelo (40 MB)", "Descargar"), labels),
            ParagraphScripts.insert(0, Card.Failed("No se pudo traducir", "Reintentar"), labels),
        )
        for (js in all) {
            forbidden.forEach { assertFalse(js.contains(it), "usa $it: $js") }
            assertFalse(Regex("""setAttribute\(\s*['"]on""", RegexOption.IGNORE_CASE).containsMatchIn(js), js)
        }
    }

    @Test fun `los separadores de linea y parrafo van escapados en todos los datos`() {
        val js = ParagraphScripts.insert(1, Card.Failed("a b", "c d"), CardLabels("x ", "y", "z"))
        assertFalse(js.contains(" ")); assertFalse(js.contains(" "))
        assertTrue(js.contains("\\u2028")); assertTrue(js.contains("\\u2029"))
    }

    @Test fun `insert crea un aside con rol de nota y lo pone despues del parrafo`() {
        val js = ParagraphScripts.insert(7, Card.Text("Hola"), labels)
        for (part in listOf("createElement('aside')", "classList.add('lector-tarjeta')", "dataset.lectorI", "setAttribute('role', 'note')",
            "setAttribute('aria-label'", ".after(", "lector-esqueleto", "\"Traducción\"", "\"Hola\"")) {
            assertTrue(js.contains(part), "falta $part")
        }
    }

    @Test fun `leer la respuesta de find`() {
        assertEquals(listOf(PageParagraph(4, "Hola."), PageParagraph(5, "Adiós.")), ParagraphScripts.parseFind("""[{"i":4,"t":"Hola."},{"i":5,"t":"Adiós."}]"""))
        assertEquals(emptyList(), ParagraphScripts.parseFind(null))
        assertEquals(emptyList(), ParagraphScripts.parseFind("no es json"))
        assertEquals(emptyList(), ParagraphScripts.parseFind("""[{"i":-1,"t":"x"}]"""))
    }

    // WebView.evaluateJavascript entrega el valor como JSON: el resultado de JSON.stringify llega como cadena citada.
    @Test fun `la respuesta doblemente citada se desenvuelve una vez`() {
        val quoted = org.json.JSONObject.quote("""[{"i":2,"t":"Uno."}]""")
        assertEquals(listOf(PageParagraph(2, "Uno.")), ParagraphScripts.parseFind(quoted))
        assertEquals(emptyList(), ParagraphScripts.parseFind("null"))
        assertEquals(emptyList(), ParagraphScripts.parseFind(org.json.JSONObject.quote(org.json.JSONObject.quote("[]"))))
    }

    @Test fun `parseFind colapsa espacios y descarta lo que no cuadra`() {
        assertEquals(listOf(PageParagraph(0, "Hola mundo.")), ParagraphScripts.parseFind("""[{"i":0,"t":"  Hola\n\t  mundo.  "}]"""))
        // El tocado sin texto: no hay párrafo.
        assertEquals(emptyList(), ParagraphScripts.parseFind("""[{"i":0,"t":"   "},{"i":1,"t":"b"}]"""))
        // Un siguiente vacío se salta; el resto se conserva.
        assertEquals(listOf(PageParagraph(0, "a"), PageParagraph(2, "c")), ParagraphScripts.parseFind("""[{"i":0,"t":"a"},{"i":1,"t":" "},{"i":2,"t":"c"}]"""))
        // Datos con forma rara: nada (fallar cerrado).
        assertEquals(emptyList(), ParagraphScripts.parseFind("""[{"i":"0","t":"a"}]"""))
        assertEquals(emptyList(), ParagraphScripts.parseFind("""[{"i":1.5,"t":"a"}]"""))
        assertEquals(emptyList(), ParagraphScripts.parseFind("""[{"i":0}]"""))
        assertEquals(emptyList(), ParagraphScripts.parseFind("""{"i":0,"t":"a"}"""))
        assertEquals(emptyList(), ParagraphScripts.parseFind("[]"))
    }

    @Test fun `find respeta el selector y excluye las tarjetas`() {
        val js = ParagraphScripts.find(10.0, 20.0)
        assertTrue(js.contains(ParagraphScripts.SELECTOR)); assertTrue(js.contains("lector-tarjeta"))
        assertTrue(js.contains("elementFromPoint")); assertTrue(js.contains("closest("))
    }

    // Ruling G: la tarjeta de un párrafo es SOLO su hermano siguiente con nuestra clase y su índice; un aside del libro
    // en otro sitio no cuenta (insert, remove e indexAt usan la misma regla).
    @Test fun `la tarjeta se busca solo como hermano siguiente del parrafo`() {
        val scripts = mapOf(
            "insert" to ParagraphScripts.insert(2, Card.Text("x"), labels),
            "remove" to ParagraphScripts.remove(2),
            "indexAt" to ParagraphScripts.indexAt(1.0, 2.0),
        )
        for ((name, js) in scripts) {
            assertTrue(js.contains("nextElementSibling"), "$name: $js")
            assertTrue(js.contains("matches('aside.lector-tarjeta[data-lector-i=\"' + "), "$name: $js")
            assertFalse(js.contains("document.querySelector('aside"), "$name busca en toda la página: $js")
            assertTrue(js.contains(ParagraphScripts.SELECTOR), "$name necesita la lista de párrafos")
        }
        // indexAt solo acepta la tarjeta si de verdad sigue a su párrafo.
        assertTrue(scripts.getValue("indexAt").contains("all[n].nextElementSibling === card"))
    }

    // Ruling H: solo hojas: un elemento del selector que contiene otro (blockquote > p, li > p) no cuenta.
    @Test fun `la lista de parrafos solo tiene hojas`() {
        for (js in listOf(
            ParagraphScripts.find(1.0, 2.0), ParagraphScripts.insert(0, Card.Skeleton, labels),
            ParagraphScripts.remove(0), ParagraphScripts.indexAt(1.0, 2.0),
        )) {
            assertTrue(js.contains("!e.querySelector(a.sel)"), js)
        }
    }

    @Test fun `el punto tocado pasa a px CSS y lo que no es finito se descarta`() {
        assertEquals(5.0 to 10.0, ParagraphScripts.cssPoint(10f, 20f, 2f))
        assertEquals(0.0 to 0.0, ParagraphScripts.cssPoint(0f, 0f, 1f))
        assertNull(ParagraphScripts.cssPoint(Float.NaN, 1f, 2f))
        assertNull(ParagraphScripts.cssPoint(1f, Float.POSITIVE_INFINITY, 2f))
        assertNull(ParagraphScripts.cssPoint(1f, 1f, Float.NaN))
        assertNull(ParagraphScripts.cssPoint(1f, 1f, 0f))
        assertNull(ParagraphScripts.cssPoint(1f, 1f, -2f))
    }

    @Test fun `leer la respuesta de indexAt`() {
        assertEquals(3, ParagraphScripts.parseIndex("3"))
        assertNull(ParagraphScripts.parseIndex("-1"))
        assertNull(ParagraphScripts.parseIndex(null))
        assertNull(ParagraphScripts.parseIndex("null"))
        assertNull(ParagraphScripts.parseIndex("\"3\""))
        assertNull(ParagraphScripts.parseIndex("2.5"))
    }

    // Ruling K: al quedar lista la página se quitan TODAS nuestras tarjetas antes de reponer las abiertas.
    @Test fun `removeAll solo quita y solo si la pagina lista es la esperada`() {
        val js = ParagraphScripts.removeAll("OEBPS/cap 1\"</script>.xhtml")
        // Solo quitar del DOM: no crea, no escribe texto ni atributos.
        for (bad in listOf("createElement", "appendChild", "textContent", "setAttribute", ".after(", "dataset.lectorEstado =")) {
            assertFalse(js.contains(bad), "usa $bad: $js")
        }
        assertTrue(js.contains(".remove()"))
        assertTrue(js.contains("querySelectorAll('aside.lector-tarjeta[data-lector-i]')"))
        // Página lista y del recurso esperado (spec §12); si no, responde false y la pantalla reintenta.
        assertTrue(js.contains("document.readyState !== 'complete'"))
        assertTrue(js.contains("location.pathname"))
        // La ruta va como dato JSON, escapada.
        assertFalse(js.contains("</script>"))
        assertTrue(js.contains("\"path\""))
    }

    @Test fun `la tarjeta sin modelo lleva su accion como enlace`() {
        val js = ParagraphScripts.insert(0, Card.MissingModel("Falta el idioma inglés → español (227 MB)", "Descargar"), labels)
        assertTrue(js.contains("\"retry\":\"Descargar\""), js)
        assertTrue(js.contains("\"kind\":\"falta-modelo\""), js)
    }

    @Test fun `leer la respuesta de removeAll`() {
        assertTrue(ParagraphScripts.parseReady("true"))
        assertFalse(ParagraphScripts.parseReady("false"))
        assertFalse(ParagraphScripts.parseReady(null))
        assertFalse(ParagraphScripts.parseReady("\"true\""))
    }
}
