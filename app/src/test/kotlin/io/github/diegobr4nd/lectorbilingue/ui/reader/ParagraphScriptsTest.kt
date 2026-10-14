package io.github.diegobr4nd.lectorbilingue.ui.reader

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParagraphScriptsTest {
    private val labels = CardLabels("Traducción", "Traduciendo…", "Preparando el traductor…", "Original")
    private val forbidden = listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "Function(")

    @Test fun `el texto hostil va como dato JSON y no rompe el script`() {
        val evil = "\"); alert(1); (\" </script><img src=x onerror=alert(2)>   fin"
        val js = ParagraphScripts.insert(3, Card.Text(evil), labels, CardAnchor.SELF)
        assertFalse(js.contains("</script>"), "</script> sin escapar")
        assertFalse(js.contains(" "))
        assertTrue(js.contains("textContent"))
        forbidden.forEach { assertFalse(js.contains(it), "usa $it") }
    }

    @Test fun `ningun script usa marcado ni atributos on`() {
        val all = listOf(
            ParagraphScripts.find(1.0, 2.0),
            ParagraphScripts.remove(1, CardAnchor.SELF),
            ParagraphScripts.indexAt(1.0, 2.0),
            ParagraphScripts.removeAll("OEBPS/c1.xhtml"),
            ParagraphScripts.edges(),
            ParagraphScripts.setTheme("oscuro"),
            ParagraphScripts.insert(0, Card.Skeleton, labels, CardAnchor.SELF),
            ParagraphScripts.insert(0, Card.Preparing, labels, CardAnchor.SELF),
            ParagraphScripts.insert(0, Card.MissingModel("Falta el modelo (40 MB)", "Descargar", "Falta el modelo, 40 megabytes. Descargar"), labels, CardAnchor.SELF),
            ParagraphScripts.insert(0, Card.Failed("No se pudo traducir", "Reintentar", "No se pudo traducir. Reintentar"), labels, CardAnchor.SELF),
        )
        for (js in all) {
            forbidden.forEach { assertFalse(js.contains(it), "usa $it: $js") }
            assertFalse(Regex("""setAttribute\(\s*['"]on""", RegexOption.IGNORE_CASE).containsMatchIn(js), js)
        }
    }

    @Test fun `los separadores de linea y parrafo van escapados en todos los datos`() {
        val js = ParagraphScripts.insert(1, Card.Failed("a b", "c d", "e f"), CardLabels("x ", "y", "z", "w"), CardAnchor.SELF)
        assertFalse(js.contains(" ")); assertFalse(js.contains(" "))
        assertTrue(js.contains("\\u2028")); assertTrue(js.contains("\\u2029"))
    }

    @Test fun `insert crea un aside con rol de nota y lo pone despues del parrafo`() {
        val js = ParagraphScripts.insert(7, Card.Text("Hola"), labels, CardAnchor.SELF)
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
            "insert" to ParagraphScripts.insert(2, Card.Text("x"), labels, CardAnchor.SELF),
            "remove" to ParagraphScripts.remove(2, CardAnchor.SELF),
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
            ParagraphScripts.find(1.0, 2.0), ParagraphScripts.insert(0, Card.Skeleton, labels, CardAnchor.SELF),
            ParagraphScripts.remove(0, CardAnchor.SELF), ParagraphScripts.indexAt(1.0, 2.0),
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
        val js = ParagraphScripts.insert(0, Card.MissingModel("Falta el idioma inglés → español (227 MB)", "Descargar", "Falta el idioma inglés a español"), labels, CardAnchor.SELF)
        assertTrue(js.contains("\"retry\":\"Descargar\""), js)
        // TalkBack oye "a" en vez de "flecha": nombre propio de la tarjeta, que se renueva en cada cambio de estado.
        assertTrue(js.contains("\"spoken\":\"Falta el idioma inglés a español\""), js)
        assertTrue(js.contains("card.setAttribute('aria-label', typeof a.spoken === 'string' ? a.spoken : a.prefix)"), js)
        assertTrue(js.contains("\"kind\":\"falta-modelo\""), js)
    }

    @Test fun `leer la respuesta de removeAll`() {
        assertTrue(ParagraphScripts.parseReady("true"))
        assertFalse(ParagraphScripts.parseReady("false"))
        assertFalse(ParagraphScripts.parseReady(null))
        assertFalse(ParagraphScripts.parseReady("\"true\""))
    }

    // "Preparando el traductor…" late como el esqueleto (va en el mismo span).
    @Test fun `preparando usa el span que late`() {
        val js = ParagraphScripts.insert(0, Card.Preparing, labels, CardAnchor.SELF)
        assertTrue(js.contains("a.kind === 'esqueleto' || a.kind === 'preparando'"), js)
    }

    // Seguridad 3b: la página manda a lo sumo el tope + 1 por párrafo (Kotlin ve que es demasiado largo sin recibir megas).
    // Se normaliza como TranslationRules.normalize y, si pasa del tope, se corta y se marca con "…": así el recorte nunca
    // acaba en un espacio que Kotlin quitaría (dejaría justo el tope y se traduciría a medias).
    @Test fun `find recorta cada texto al tope mas uno sin acabar en espacio`() {
        val js = ParagraphScripts.find(1.0, 2.0)
        assertTrue(js.contains("\"max\":20000"), js)
        assertTrue(js.contains("""t = t.replace(/[ \t\n\x0B\f\r\u00A0]+/g, ' ').trim();"""), js)
        // Revisión final 3b (M1): además del "…", una marca explícita de recorte.
        assertTrue(js.contains("return t.length > a.max ? { i: i, t: t.slice(0, a.max) + '…', cut: true } : { i: i, t: t };"), js)
        assertTrue(js.contains("var out = [item(at, text)];"), js)
        assertTrue(js.contains("out.push(item(k, t))"), js)
    }

    // Diseño 3b (M1): la tarjeta con texto no lleva aria-label (taparía la traducción al deslizar con TalkBack).
    @Test fun `la tarjeta con texto no lleva nombre propio y las demas si`() {
        val js = ParagraphScripts.insert(0, Card.Text("Hola"), labels, CardAnchor.SELF)
        assertTrue(js.contains("if (a.kind === 'texto' || a.kind === 'solo') card.removeAttribute('aria-label');"), js)
        assertTrue(js.contains("else card.setAttribute('aria-label', typeof a.spoken === 'string' ? a.spoken : a.prefix);"), js)
        assertTrue(js.contains("setAttribute('role', 'note')"), js)
    }

    @Test fun `la tarjeta de parrafo demasiado largo es de error sin enlace`() {
        val js = ParagraphScripts.insert(0, Card.TooLong("Este párrafo es demasiado largo para traducirlo"), labels, CardAnchor.SELF)
        assertTrue(js.contains("\"kind\":\"error\""), js)
        assertTrue(js.contains("\"spoken\":\"Este párrafo es demasiado largo para traducirlo\""), js)
        assertFalse(js.contains("\"retry\""), js)
    }

    // Revisión final 3b (M3): la tarjeta de error lleva su propio nombre hablado, no "Traducción".
    @Test fun `la tarjeta de error lleva su texto hablado como nombre`() {
        val js = ParagraphScripts.insert(0, Card.Failed("No se pudo traducir", "Reintentar", "No se pudo traducir. Reintentar"), labels, CardAnchor.SELF)
        assertTrue(js.contains("\"spoken\":\"No se pudo traducir. Reintentar\""), js)
        assertTrue(js.contains("\"kind\":\"error\""), js)
    }

    // Revisión final 3b (M1/M5): Kotlin quita al recortar caracteres que la página deja (U+001C a U+001F). Sin la marca,
    // un párrafo recortado podía quedar justo en el tope y traducirse a medias; la marca `cut` lo dice igual.
    @Test fun `parseFind conserva la marca de recorte aunque el texto quede en el tope`() {
        val json = JSONArray()
            .put(JSONObject().put("i", 0).put("t", "a".repeat(20_000) + Char(0x1F)).put("cut", true))
            .put(JSONObject().put("i", 1).put("t", "b").put("cut", "true")) // solo el booleano true cuenta
            .put(JSONObject().put("i", 2).put("t", "c"))
            .toString()
        assertEquals(
            listOf(PageParagraph(0, "a".repeat(20_000), cut = true), PageParagraph(1, "b"), PageParagraph(2, "c")),
            ParagraphScripts.parseFind(json),
        )
    }

    // Bordes de la página (fix/cambio-de-capitulo): 1 = arriba del todo, 2 = abajo del todo, 3 = los dos.
    @Test fun `lee los bordes de la pagina`() {
        assertEquals(PageEdges(atTop = false, atBottom = false), ParagraphScripts.parseEdges("0"))
        assertEquals(PageEdges(atTop = true, atBottom = false), ParagraphScripts.parseEdges("1"))
        assertEquals(PageEdges(atTop = false, atBottom = true), ParagraphScripts.parseEdges("2"))
        assertEquals(PageEdges(atTop = true, atBottom = true), ParagraphScripts.parseEdges("3"))
    }

    @Test fun `bordes desconocidos si la respuesta no sirve`() {
        for (bad in listOf(null, "null", "4", "-1", "\"2\"", "1.5", "true", "{}")) assertNull(ParagraphScripts.parseEdges(bad), "con $bad")
    }

    @Test fun `setTheme pone el atributo del tema con el valor como dato JSON`() {
        for (theme in listOf("claro", "sepia", "oscuro", "negro")) {
            val js = ParagraphScripts.setTheme(theme)
            assertTrue(js.contains("setAttribute('data-lector-tema'"), js)
            assertTrue(js.contains("document.documentElement"), js)
            assertTrue(js.contains("{\"tema\":\"$theme\"}"), js) // el tema llega como dato, no como código
            forbidden.forEach { assertFalse(js.contains(it), "usa $it") }
        }
    }

    @Test fun `setTheme rechaza temas fuera de la lista`() {
        assertFailsWith<IllegalArgumentException> { ParagraphScripts.setTheme("<x>") }
        assertFailsWith<IllegalArgumentException> { ParagraphScripts.setTheme("") }
        assertFailsWith<IllegalArgumentException> { ParagraphScripts.setTheme("Oscuro") }
        assertFailsWith<IllegalArgumentException> { ParagraphScripts.setTheme("oscuro\"]") }
    }

    @Test fun `todo tema de la regla lo acepta setTheme`() {
        for (theme in io.github.diegobr4nd.lectorbilingue.data.PageTheme.entries) for (dark in listOf(false, true)) {
            val card = ReadingRules.cardTheme(io.github.diegobr4nd.lectorbilingue.data.ReadingSettings(theme = theme), dark)
            assertTrue(card in ParagraphScripts.CARD_THEMES, card)
        }
    }

    // ---- 3c-2: modos de traducción ----

    @Test fun `setMode pone el atributo del modo con el valor como dato JSON`() {
        assertEquals(setOf("tocar", "intercalado", "solo"), ParagraphScripts.MODES)
        for (mode in ParagraphScripts.MODES) {
            val js = ParagraphScripts.setMode(mode)
            assertTrue(js.contains("document.documentElement.setAttribute('data-lector-modo', a.modo)"), js)
            assertTrue(js.contains("\"modo\":\"$mode\""), js) // el modo llega como dato, no como código
            forbidden.forEach { assertFalse(js.contains(it), "usa $it") }
        }
    }

    @Test fun `setMode rechaza modos fuera de la lista`() {
        for (bad in listOf("", "Solo", "solo\"]", "<x>", "tap", "translation_only", " solo")) {
            assertFailsWith<IllegalArgumentException>(bad) { ParagraphScripts.setMode(bad) }
        }
    }

    // Ruling D: al salir de "solo" se quita el estilo copiado y se vuelven a mostrar los originales.
    @Test fun `setMode fuera de solo quita el estilo copiado y muestra los originales`() {
        val js = ParagraphScripts.setMode("intercalado")
        assertTrue(js.contains("if (a.modo !== 'solo')"), js)
        assertTrue(js.contains("style.removeProperty("), js)
        assertTrue(js.contains("classList.remove('lector-oculto')"), js)
        assertTrue(js.contains("removeAttribute('data-lector-original')"), js)
    }

    @Test fun `visible usa el mismo selector y filtro que find y excluye las tarjetas`() {
        val js = ParagraphScripts.visible()
        assertTrue(js.contains(ParagraphScripts.SELECTOR), js)
        assertTrue(js.contains("!e.closest('.lector-tarjeta') && !e.querySelector(a.sel)"), js)
        assertTrue(js.contains("getBoundingClientRect()"), js)
        assertTrue(js.contains("window.innerHeight"), js)
        assertTrue(js.contains("\"n\":10"), js)
        assertTrue(js.contains("\"max\":20000"), js)
        // Mismo recorte y marca que find.
        assertTrue(js.contains("""t = t.replace(/[ \t\n\x0B\f\r\u00A0]+/g, ' ').trim();"""), js)
        assertTrue(js.contains("return t.length > a.max ? { i: i, t: t.slice(0, a.max) + '…', cut: true } : { i: i, t: t };"), js)
        assertTrue(ParagraphScripts.visible(3).contains("\"n\":3"))
    }

    // Ruling E: un original oculto (display:none mide 0×0) se mide con el rectángulo de su tarjeta.
    @Test fun `visible mide un original oculto con su tarjeta`() {
        val js = ParagraphScripts.visible()
        assertTrue(js.contains("classList.contains('lector-oculto')"), js)
        assertTrue(js.contains("nextElementSibling"), js)
        assertTrue(js.contains("matches('aside.lector-tarjeta[data-lector-i=\"' + "), js)
    }

    @Test fun `visible rechaza un numero de siguientes fuera de rango`() {
        assertFailsWith<IllegalArgumentException> { ParagraphScripts.visible(-1) }
        assertFailsWith<IllegalArgumentException> { ParagraphScripts.visible(51) }
        ParagraphScripts.visible(0); ParagraphScripts.visible(50)
    }

    @Test fun `leer la respuesta de visible`() {
        assertEquals(
            listOf(PageParagraph(4, "Hola."), PageParagraph(5, "Adiós mundo.", cut = true)),
            ParagraphScripts.parseVisible("""[{"i":4,"t":"Hola."},{"i":5,"t":" Adiós\n mundo. ","cut":true}]"""),
        )
        // Cadena citada (JSON.stringify) se desenvuelve una vez.
        assertEquals(listOf(PageParagraph(2, "Uno.")), ParagraphScripts.parseVisible(JSONObject.quote("""[{"i":2,"t":"Uno."}]""")))
        // Uno vacío se salta (aquí no hay "tocado" que deba tener texto).
        assertEquals(listOf(PageParagraph(1, "b")), ParagraphScripts.parseVisible("""[{"i":0,"t":"  "},{"i":1,"t":"b"}]"""))
        assertEquals(emptyList(), ParagraphScripts.parseVisible("[]"))
        // Marca `v: 1`: toca la pantalla. Solo el 1 cuenta (otro valor o sin marca: es de los siguientes).
        assertEquals(
            listOf(PageParagraph(0, "a", onScreen = true), PageParagraph(1, "b"), PageParagraph(2, "c"), PageParagraph(3, "d")),
            ParagraphScripts.parseVisible("""[{"i":0,"t":"a","v":1},{"i":1,"t":"b"},{"i":2,"t":"c","v":true},{"i":3,"t":"d","v":"1"}]"""),
        )
        // find no lleva la marca.
        assertFalse(ParagraphScripts.parseFind("""[{"i":0,"t":"a","v":1}]""").single().onScreen)
    }

    // Revisión Tarea 4 (importante 2): visible() marca con `v: 1` los que tocan la pantalla (el script ya los separa).
    @Test fun `visible marca los que tocan la pantalla`() {
        val js = ParagraphScripts.visible()
        assertTrue(js.contains("var it = item(k, t); it.v = 1; out.push(it);"), js)
    }

    @Test fun `parseVisible falla cerrado`() {
        for (bad in listOf(
            null, "null", "no es json", "{}", "3", "\"x\"",
            """[{"i":"0","t":"a"}]""", """[{"i":1.5,"t":"a"}]""", """[{"i":0}]""", """[{"i":-1,"t":"a"}]""", """[3]""",
            // Fuera de orden o repetidos: la página manda de arriba abajo; otra cosa no es de fiar.
            """[{"i":2,"t":"a"},{"i":1,"t":"b"}]""", """[{"i":2,"t":"a"},{"i":2,"t":"b"}]""",
            JSONObject.quote(JSONObject.quote("[]")),
        )) {
            assertEquals(emptyList(), ParagraphScripts.parseVisible(bad), "con $bad")
        }
        // Demasiados para lo pedido (lo visible + los siguientes nunca llega a mil): fuera.
        val many = JSONArray().apply { for (k in 0..1000) put(JSONObject().put("i", k).put("t", "x")) }.toString()
        assertEquals(emptyList(), ParagraphScripts.parseVisible(many))
    }

    @Test fun `los scripts nuevos no usan marcado ni eval`() {
        val all = listOf(
            ParagraphScripts.visible(),
            ParagraphScripts.setMode("tocar"), ParagraphScripts.setMode("intercalado"), ParagraphScripts.setMode("solo"),
            ParagraphScripts.showOriginal(2, true, CardAnchor.SCREEN), ParagraphScripts.showOriginal(2, false, CardAnchor.SCREEN),
            ParagraphScripts.insert(0, Card.Solo("Hola"), labels, CardAnchor.SELF),
            ParagraphScripts.insert(0, Card.Waiting("Traduciendo…"), labels, CardAnchor.SELF),
        )
        for (js in all) {
            forbidden.forEach { assertFalse(js.contains(it), "usa $it: $js") }
            assertFalse(Regex("""setAttribute\(\s*['"](on|style)""", RegexOption.IGNORE_CASE).containsMatchIn(js), js)
            assertFalse(js.contains("cssText"), js) // estilo solo por propiedad, nunca como cadena
            assertFalse(js.contains(".go("), js)
        }
    }

    // Ruling D: la tarjeta Solo copia del original una lista FIJA de propiedades calculadas, con 'important'.
    @Test fun `la tarjeta solo copia una lista fija de propiedades calculadas del original`() {
        assertEquals(
            listOf(
                "display", "font-family", "font-size", "font-weight", "font-style", "line-height", "text-indent", "text-align",
                "letter-spacing", "word-spacing", "text-transform", "font-variant", "margin-top", "margin-right", "margin-bottom", "margin-left", "padding-top", "padding-right",
                "padding-bottom", "padding-left", "list-style-type", "list-style-position",
            ),
            ParagraphScripts.COPIED_STYLE,
        )
        val js = ParagraphScripts.insert(3, Card.Solo("Hola \"</script>"), labels, CardAnchor.SELF)
        assertTrue(js.contains("\"kind\":\"solo\""), js)
        assertTrue(js.contains(JSONArray(ParagraphScripts.COPIED_STYLE).toString()), js)
        assertTrue(js.contains("getComputedStyle(p)"), js)
        assertTrue(js.contains("card.style.setProperty(k, cs.getPropertyValue(k), 'important')"), js)
        assertTrue(js.contains("p.classList.add('lector-oculto')"), js)
        // Texto como texto; la marca "Original" viene de strings.xml.
        assertTrue(js.contains("\"mark\":\"Original\""), js)
        assertTrue(js.contains("textContent = a.text"), js)
        assertTrue(js.contains("textContent = a.mark"), js)
        assertFalse(js.contains("</script>"), js)
    }

    @Test fun `una tarjeta que no es solo quita el estilo copiado y muestra el original`() {
        for (card in listOf(Card.Text("x"), Card.Skeleton, Card.Waiting("Traduciendo…"), Card.TooLong("largo"))) {
            val js = ParagraphScripts.insert(1, card, labels, CardAnchor.SELF)
            assertTrue(js.contains("card.style.removeProperty(k)"), js)
            assertTrue(js.contains("p.classList.remove('lector-oculto')"), js)
        }
    }

    @Test fun `la tarjeta en espera lleva su texto como nombre`() {
        val js = ParagraphScripts.insert(0, Card.Waiting("Traduciendo…"), labels, CardAnchor.SELF)
        assertTrue(js.contains("\"kind\":\"espera\""), js)
        // org.json escribe "…" escapado: se compara con su propia forma.
        assertTrue(js.contains("\"spoken\":" + JSONObject.quote("Traduciendo…")), js)
        assertTrue(js.contains("\"text\":" + JSONObject.quote("Traduciendo…")), js)
    }

    @Test fun `showOriginal alterna la clase del parrafo y la marca de la tarjeta`() {
        val on = ParagraphScripts.showOriginal(4, true, CardAnchor.SCREEN)
        assertTrue(on.contains("\"i\":4"), on); assertTrue(on.contains("\"on\":true"), on)
        assertTrue(ParagraphScripts.showOriginal(4, false, CardAnchor.SCREEN).contains("\"on\":false"))
        for (part in listOf(
            "p.classList.remove('lector-oculto')", "p.classList.add('lector-oculto')",
            "setAttribute('data-lector-original', '')", "removeAttribute('data-lector-original')",
            // Solo una tarjeta Solo (hermano siguiente con nuestra clase e índice).
            "card.dataset.lectorEstado !== 'solo'", "nextElementSibling",
        )) assertTrue(on.contains(part), "falta $part")
        assertFailsWith<IllegalArgumentException> { ParagraphScripts.showOriginal(-1, true, CardAnchor.SCREEN) }
    }

    // Ruling C: sin saltos. insert, remove, showOriginal y setMode miden el ancla antes y después del cambio y corrigen
    // con scrollBy en el mismo script. Nunca go(guardado).
    @Test fun `los scripts de tarjeta compensan con scrollBy en el mismo script`() {
        for (js in listOf(
            ParagraphScripts.insert(2, Card.Text("x"), labels, CardAnchor.SCREEN), ParagraphScripts.insert(2, Card.Solo("x"), labels, CardAnchor.SELF),
            ParagraphScripts.remove(2, CardAnchor.SCREEN), ParagraphScripts.showOriginal(2, true, CardAnchor.SCREEN), ParagraphScripts.setMode("tocar"),
        )) {
            assertTrue(js.contains("window.scrollBy(0, d)"), js)
            assertTrue(js.contains("anchor()"), js)
        }
    }

    // Revisión T3 (1): el ancla "pantalla" es el primer bloque que EMPIEZA dentro de la pantalla (0 ≤ top < alto de la
    // ventana, alto > 0); si no hay (un párrafo más alto que la pantalla), el que cruza el borde de arriba; si tampoco, no
    // se corrige. Nunca uno de más abajo: una tarjeta entre lo visible y ese bloque haría saltar el texto.
    @Test fun `el ancla de pantalla tiene tope abajo y cae al bloque que cruza el borde de arriba`() {
        val js = ParagraphScripts.insert(2, Card.Text("x"), labels, CardAnchor.SCREEN)
        assertTrue(js.contains("if (r.top >= 0 && r.top < h && r.height > 0) return k;"), js)
        assertTrue(js.contains("if (cross < 0 && r.top < 0 && r.bottom > 0) cross = k;"), js)
        assertTrue(js.contains("if (r.top >= h) break;"), js)
        assertTrue(js.contains("return cross;"), js)
        assertFalse(js.contains("r.top >= 0 && r.height > 0)"), js) // la forma vieja, sin tope abajo
    }

    // Ruling F: en TAP la tarjeta sale justo debajo del párrafo tocado (3b): se fija el propio párrafo ("self"). En
    // Intercalado y Solo, lo que se lee ("screen").
    @Test fun `la politica de ancla va como dato de una lista fija`() {
        assertEquals(listOf("self", "screen"), CardAnchor.entries.map { it.wire })
        for (anchor in CardAnchor.entries) for (js in listOf(
            ParagraphScripts.insert(1, Card.Text("x"), labels, anchor), ParagraphScripts.remove(1, anchor),
            ParagraphScripts.showOriginal(1, true, anchor),
        )) {
            assertTrue(js.contains("\"anchor\":\"${anchor.wire}\""), js)
            assertTrue(js.contains("var b = a.anchor === 'self' ? a.i : anchor()"), js)
        }
    }

    // Revisión T3 (3): la tarjeta Solo y showOriginal no hacen nada fuera del modo "solo" (no se oculta el original).
    @Test fun `solo y showOriginal solo actuan en el modo solo`() {
        val guard = "document.documentElement.getAttribute('data-lector-modo') !== 'solo'"
        assertTrue(ParagraphScripts.insert(0, Card.Solo("x"), labels, CardAnchor.SCREEN).contains("if (a.kind === 'solo' && $guard) return false;"))
        assertTrue(ParagraphScripts.showOriginal(0, true, CardAnchor.SCREEN).contains("if ($guard) return false;"))
    }

    // Si la página se vacía de tarjetas, ningún original puede quedar oculto sin su tarjeta.
    @Test fun `removeAll y remove vuelven a mostrar los originales ocultos`() {
        assertTrue(ParagraphScripts.removeAll("OEBPS/c1.xhtml").contains("classList.remove('lector-oculto')"))
        assertTrue(ParagraphScripts.remove(1, CardAnchor.SELF).contains("p.classList.remove('lector-oculto')"))
    }
}
