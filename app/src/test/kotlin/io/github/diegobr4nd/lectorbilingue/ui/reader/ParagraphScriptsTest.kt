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
            ParagraphScripts.insert(0, Card.Skeleton, labels),
            ParagraphScripts.insert(0, Card.Preparing, labels),
            ParagraphScripts.insert(0, Card.MissingModel("Falta el modelo (40 MB)"), labels),
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

    @Test fun `leer la respuesta de indexAt`() {
        assertEquals(3, ParagraphScripts.parseIndex("3"))
        assertNull(ParagraphScripts.parseIndex("-1"))
        assertNull(ParagraphScripts.parseIndex(null))
        assertNull(ParagraphScripts.parseIndex("null"))
        assertNull(ParagraphScripts.parseIndex("\"3\""))
        assertNull(ParagraphScripts.parseIndex("2.5"))
    }
}
