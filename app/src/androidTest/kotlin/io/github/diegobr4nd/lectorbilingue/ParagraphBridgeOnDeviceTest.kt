package io.github.diegobr4nd.lectorbilingue

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.data.PageTheme
import io.github.diegobr4nd.lectorbilingue.data.ReadingSettings
import io.github.diegobr4nd.lectorbilingue.ui.reader.Card
import io.github.diegobr4nd.lectorbilingue.ui.reader.CardAnchor
import io.github.diegobr4nd.lectorbilingue.ui.reader.CardLabels
import io.github.diegobr4nd.lectorbilingue.ui.reader.PageParagraph
import io.github.diegobr4nd.lectorbilingue.ui.reader.ParagraphBridge
import io.github.diegobr4nd.lectorbilingue.ui.reader.ParagraphScripts
import io.github.diegobr4nd.lectorbilingue.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ParagraphBridge] contra la página real (Readium + WebView), con el libro inventado de [ReaderTestBook]:
 * leer el párrafo tocado, poner la tarjeta como texto (también con texto hostil) con la hoja de la app, y quitarla.
 * Los registros de la prueba solo llevan números y booleanos.
 */
@RunWith(AndroidJUnit4::class)
class ParagraphBridgeOnDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LectorApp
    private val dir = File(app.cacheDir, "bridge_test").apply { deleteRecursively(); mkdirs() }
    private val created = mutableListOf<String>()
    private val labels = CardLabels("Traducción", "Traduciendo…", "Preparando el traductor…", "Original")

    // Los colores que se miden son los del tema claro: se fija y al final se devuelven los ajustes de la persona.
    private val saved = app.settings.readingSettings.also { app.settings.readingSettings = ReadingSettings(theme = PageTheme.LIGHT) }

    @After fun cleanUp() = runBlocking<Unit> {
        app.settings.readingSettings = saved
        for (id in created) {
            app.openBooks.close(id)
            app.books.delete(id)
        }
        dir.deleteRecursively()
    }

    @Test fun leeElParrafoTocadoPoneLaTarjetaComoTextoYLaQuita() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            val bridge = ParagraphBridge { s.navigator() }
            waitFor("página lista") {
                js(s, "document.readyState === 'complete' && !!window.readium && document.querySelectorAll('p').length") == "70"
            }
            val density = app.resources.displayMetrics.density

            // 1. Toque en el centro del primer párrafo (índice 1: el h1 es el 0), en px del aparato como en onTap.
            val center = JSONObject(js(s, "var r = document.querySelectorAll('p')[0].getBoundingClientRect(); JSON.stringify({x: r.left + r.width / 2, y: r.top + r.height / 2})")!!)
            val found = bridge.paragraphsAt((center.getDouble("x") * density).toFloat(), (center.getDouble("y") * density).toFloat(), density)
            assertEquals((1..6).map { PageParagraph(it, ReaderTestBook.paragraph(it)) }, found)
            assertEquals(1, bridge.indexAt((center.getDouble("x") * density).toFloat(), (center.getDouble("y") * density).toFloat(), density))
            // Readium devuelve el valor como lo da WebView: el JSON.stringify llega como cadena citada (parseFind la desenvuelve).
            assertTrue(rawJs(s, "JSON.stringify([1])")!!.startsWith("\""))

            // 2. Tarjeta con texto: debajo del párrafo, el párrafo no se mueve y la hoja de la app se aplica.
            val topBefore = topOfFirstP(s)
            bridge.show(1, Card.Text("Hola"), labels, CardAnchor.SELF)
            assertEquals("true", js(s, "var c = document.querySelectorAll('p')[0].nextElementSibling; String(!!c && c.matches('aside.lector-tarjeta[data-lector-i=\"1\"]'))"))
            assertEquals("Hola", js(s, CARD + ".textContent"))
            assertEquals("note", js(s, CARD + ".getAttribute('role')"))
            // Con texto, sin nombre propio (diseño M1): TalkBack, al deslizar, lee la traducción.
            assertEquals("false", js(s, "String($CARD.hasAttribute('aria-label'))"))
            assertTrue(abs(topOfFirstP(s) - topBefore) <= 1.0, "el párrafo se movió")
            val border = js(s, "getComputedStyle($CARD).borderLeftWidth")!!.removeSuffix("px").toDouble()
            assertTrue(border in 3.0..5.0, "borde de la hoja: $border")
            assertEquals("1", js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)"))

            // 3. Esqueleto: reemplaza el contenido de la misma tarjeta, con su span.
            bridge.show(1, Card.Skeleton, labels, CardAnchor.SELF)
            assertEquals("Traduciendo…", js(s, "$CARD.querySelector('span.lector-esqueleto').textContent"))
            assertEquals("1", js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)"))
            // "Preparando…" late igual (mismo span); sin modelo, TalkBack oye "a" en vez de la flecha y vuelve "Traducción" después.
            bridge.show(1, Card.Preparing, labels, CardAnchor.SELF)
            assertEquals("Preparando el traductor…", js(s, "$CARD.querySelector('span.lector-esqueleto').textContent"))
            bridge.show(1, Card.MissingModel("Falta el idioma inglés → español (227 MB)", "Descargar", "Falta el idioma inglés a español"), labels, CardAnchor.SELF)
            assertEquals("Falta el idioma inglés a español", js(s, "$CARD.getAttribute('aria-label')"))
            assertEquals("Descargar", js(s, "$CARD.querySelector('span.lector-reintentar').textContent"))
            assertTrue(js(s, "String($CARD.querySelector('span.lector-reintentar').getBoundingClientRect().height)")!!.toDouble() >= 47.0)
            // Demasiado largo: estilo de error, sin enlace, con su texto como nombre.
            bridge.show(1, Card.TooLong("Este párrafo es demasiado largo para traducirlo"), labels, CardAnchor.SELF)
            assertEquals("error", js(s, "$CARD.getAttribute('data-lector-estado')"))
            assertEquals("Este párrafo es demasiado largo para traducirlo", js(s, "$CARD.getAttribute('aria-label')"))
            assertEquals("0", js(s, "String($CARD.querySelectorAll('span.lector-reintentar').length)"))
            assertEquals("rgb(254, 243, 242)", js(s, "getComputedStyle($CARD).backgroundColor"))
            bridge.show(1, Card.Text("Hola"), labels, CardAnchor.SELF)
            assertEquals("false", js(s, "String($CARD.hasAttribute('aria-label'))"))

            // 4. Texto hostil: entra como texto; nada se ejecuta ni se crea marcado.
            val evil = "\"); alert(1); (\" </script><img src=x onerror=alert(2)>   fin <img src=x onerror=\"window.__lectorXss=1\">"
            val imgs = js(s, "String(document.querySelectorAll('img').length)")
            bridge.show(1, Card.Text(evil), labels, CardAnchor.SELF)
            Thread.sleep(500) // Por si un onerror llegara a cargarse.
            assertEquals(imgs, js(s, "String(document.querySelectorAll('img').length)"))
            assertEquals("undefined", js(s, "typeof window.__lectorXss"))
            assertEquals(evil, js(s, "$CARD.textContent"))
            assertEquals("0", js(s, "String($CARD.children.length)"))

            // 5. Quitar.
            bridge.hide(1, CardAnchor.SELF)
            assertEquals("0", js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)"))

            // 6. Quitar todas (Ruling K): solo si la página lista es el recurso esperado (el href del localizador).
            bridge.show(1, Card.Text("Uno"), labels, CardAnchor.SELF)
            bridge.show(3, Card.Text("Tres"), labels, CardAnchor.SELF)
            val href = s.navigator()!!.currentLocator.value.href.toString()
            assertEquals(false, bridge.hideAll("OEBPS/c2.xhtml"))
            assertEquals("2", js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)"))
            assertEquals(true, bridge.hideAll("$href#algo"))
            assertEquals("0", js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)"))
        }
    }

    /**
     * Libros raros u hostiles (Ruling G y H). La prueba monta en la página, con su propio script: un `blockquote` con un
     * `p` dentro antes del primer párrafo, un `aside` falso con nuestras marcas al final y una regla que oculta los `aside`
     * (como hacen muchos EPUB3). Así se ve el JS de verdad, que en la JVM solo se puede leer como texto.
     */
    @Test fun soloHojasLaTarjetaEsElHermanoSiguienteYNoSeOculta() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            val bridge = ParagraphBridge { s.navigator() }
            waitFor("página lista") {
                js(s, "document.readyState === 'complete' && !!window.readium && document.querySelectorAll('p').length") == "70"
            }
            val density = app.resources.displayMetrics.density
            assertEquals(
                "ok",
                js(
                    s,
                    "(function () { var first = document.querySelectorAll('p')[0];" +
                        " var bq = document.createElement('blockquote'); var ip = document.createElement('p'); ip.id = 'cita';" +
                        " ip.textContent = 'Cita.'; bq.appendChild(ip); first.before(bq);" +
                        " var fake = document.createElement('aside'); fake.id = 'falsa'; fake.className = 'lector-tarjeta';" +
                        " fake.dataset.lectorI = '2'; fake.textContent = 'falsa'; document.body.appendChild(fake);" +
                        " var st = document.createElement('style'); st.textContent = 'aside { display: none !important; visibility: hidden !important; }';" +
                        " document.head.appendChild(st); return 'ok'; })()",
                ),
            )

            // 1. Tocar el p de dentro de la cita da ese p (índice 1: h1 = 0, el blockquote no cuenta); el primer p pasa a 2.
            val (cx, cy) = centerOf(s, "document.getElementById('cita')")
            val found = bridge.paragraphsAt((cx * density).toFloat(), (cy * density).toFloat(), density)
            assertEquals(PageParagraph(1, "Cita."), found.first())
            assertEquals((1..5).map { PageParagraph(it + 1, ReaderTestBook.paragraph(it)) }, found.drop(1))

            // 2. La tarjeta del 2 va debajo del primer p del libro, se ve pese a la regla del libro y la falsa no se toca.
            bridge.show(2, Card.Text("Hola"), labels, CardAnchor.SELF)
            val real = "document.querySelectorAll('p')[1].nextElementSibling"
            assertEquals("true", js(s, "String($real.matches('aside.lector-tarjeta[data-lector-i=\"2\"]'))"))
            assertEquals("Hola", js(s, "$real.textContent"))
            assertEquals("block", js(s, "getComputedStyle($real).display"))
            assertEquals("visible", js(s, "getComputedStyle($real).visibility"))
            assertEquals("falsa", js(s, "document.getElementById('falsa').textContent"))
            val (rx, ry) = centerOf(s, real)
            assertEquals(2, bridge.indexAt((rx * density).toFloat(), (ry * density).toFloat(), density))

            // 3. Quitar la 2 quita la de verdad y deja la falsa; quitar otra vez no encuentra nada.
            bridge.hide(2, CardAnchor.SELF)
            assertEquals("false", js(s, "String(!!$real && $real.matches('aside'))"))
            assertEquals("falsa", js(s, "document.getElementById('falsa').textContent"))

            // 4. Un punto que no es finito no llega a la página.
            assertEquals(emptyList(), bridge.paragraphsAt(Float.NaN, 10f, density))
            assertEquals(null, bridge.indexAt(10f, 10f, 0f))
        }
    }

    /**
     * Modos de traducción (3c-2, Task 3): `visible`, `setMode`, la tarjeta Solo (oculta el original y toma su letra con
     * `getComputedStyle`), `showOriginal`, la tarjeta en espera, sin saltos (Ruling C) y texto hostil como texto.
     */
    @Test fun modosVisibleSoloOriginalYSinSaltos() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            val bridge = ParagraphBridge { s.navigator() }
            waitFor("página lista") {
                js(s, "document.readyState === 'complete' && !!window.readium && document.querySelectorAll('p').length") == "70"
            }
            Thread.sleep(500) // que la pantalla termine de quitar tarjetas viejas y poner el tema (al quedar lista)

            // 1. visible(): los que tocan la pantalla (medidos aquí por separado) y los 10 siguientes, de arriba abajo.
            val onScreen = JSONArray(js(s, "(function () { var all = $LEAVES; var h = window.innerHeight, on = [];" +
                " for (var k = 0; k < all.length; k++) { var r = all[k].getBoundingClientRect(); if (r.bottom > 0 && r.top < h) on.push(k); }" +
                " return JSON.stringify(on); })()")!!).let { a -> (0 until a.length()).map { a.getInt(it) } }
            assertTrue(onScreen.isNotEmpty() && onScreen.first() == 0, "en pantalla: $onScreen")
            val seen = bridge.visible()
            assertEquals(onScreen + (onScreen.last() + 1..onScreen.last() + 10), seen.map { it.index })
            // Marca `v`: justo los que tocan la pantalla (revisión Tarea 4, importante 2).
            assertEquals(onScreen, seen.filter { it.onScreen }.map { it.index })
            for (p in seen.filter { it.index >= 1 }) assertEquals(ReaderTestBook.paragraph(p.index), p.text)
            assertEquals(listOf(onScreen.last() + 1, onScreen.last() + 2), bridge.visible(2).map { it.index } - onScreen.toSet())

            // 2. setMode("solo") + Solo en el h1 (índice 0, una hoja que no es p): el h1 se oculta y la tarjeta toma su letra.
            bridge.setMode("solo")
            assertEquals("solo", js(s, "document.documentElement.getAttribute('data-lector-modo')"))
            val h1Size = js(s, "getComputedStyle(document.querySelector('h1')).fontSize")
            val h1Top = rectTop(s, 0)
            bridge.show(0, Card.Solo("Título traducido"), labels, CardAnchor.SCREEN)
            assertEquals("none", js(s, "getComputedStyle(document.querySelector('h1')).display"))
            assertEquals("true", js(s, "String(document.querySelector('h1').classList.contains('lector-oculto'))"))
            assertEquals(h1Size, js(s, "getComputedStyle(${card(0)}).fontSize"))
            assertTrue(abs(rectTop(s, 0) - h1Top) <= 1.0, "la tarjeta no quedó en el sitio del h1")
            // visible() mide el h1 oculto con su tarjeta: sigue en la lista.
            assertEquals(0, bridge.visible().first().index)

            // 3. Solo en el primer p: oculto; la tarjeta con su letra, sin fondo ni línea; el ancla (B) no se mueve.
            val p1 = "document.querySelectorAll('p')[0]"
            val style = listOf("fontSize", "fontFamily", "lineHeight", "marginBottom", "textIndent", "display")
                .associateWith { js(s, "getComputedStyle($p1).$it") }
            val b = anchorIndex(s)
            val bTop = rectTop(s, b)
            bridge.show(1, Card.Solo("Hola solo"), labels, CardAnchor.SCREEN)
            assertEquals("none", js(s, "getComputedStyle($p1).display"))
            for ((k, v) in style) assertEquals(v, js(s, "getComputedStyle(${card(1)}).$k"), k)
            assertEquals("rgba(0, 0, 0, 0)", js(s, "getComputedStyle(${card(1)}).backgroundColor"))
            assertEquals("0px", js(s, "getComputedStyle(${card(1)}).borderLeftWidth"))
            assertEquals("Hola solo", js(s, "${card(1)}.querySelector('.lector-traduccion').textContent"))
            assertEquals("none", js(s, "getComputedStyle(${card(1)}.querySelector('.lector-marca')).display"))
            assertTrue(abs(rectTop(s, b) - bTop) <= 1.0, "el ancla se movió: ${rectTop(s, b) - bTop}")

            // 4. showOriginal: vuelve el original con la marca "Original"; la tarjeta pierde el estilo copiado; y vuelta.
            bridge.showOriginal(1, true, CardAnchor.SCREEN)
            assertEquals(style["display"], js(s, "getComputedStyle($p1).display"))
            assertEquals("true", js(s, "String(${card(1)}.hasAttribute('data-lector-original'))"))
            assertEquals("none", js(s, "getComputedStyle(${card(1)}.querySelector('.lector-traduccion')).display"))
            assertEquals("inline-block", js(s, "getComputedStyle(${card(1)}.querySelector('.lector-marca')).display"))
            assertEquals("Original", js(s, "${card(1)}.querySelector('.lector-marca').textContent"))
            assertEquals("", js(s, "${card(1)}.style.getPropertyValue('font-size')"))
            assertTrue(abs(rectTop(s, b) - bTop) <= 1.0, "el ancla se movió al mostrar el original")
            bridge.showOriginal(1, false, CardAnchor.SCREEN)
            assertEquals("none", js(s, "getComputedStyle($p1).display"))
            assertEquals(style["fontSize"], js(s, "getComputedStyle(${card(1)}).fontSize"))
            assertTrue(abs(rectTop(s, b) - bTop) <= 1.0, "el ancla se movió al volver a la traducción")

            // 5. Texto hostil en Solo: entra como texto, nada se ejecuta ni se crea marcado.
            val evil = "\"); alert(1); (\" </script><img src=x onerror=alert(2)> <img src=x onerror=\"window.__lectorXss=1\">"
            val imgs = js(s, "String(document.querySelectorAll('img').length)")
            bridge.show(1, Card.Solo(evil), labels, CardAnchor.SCREEN)
            Thread.sleep(500)
            assertEquals(imgs, js(s, "String(document.querySelectorAll('img').length)"))
            assertEquals("undefined", js(s, "typeof window.__lectorXss"))
            assertEquals(evil, js(s, "${card(1)}.querySelector('.lector-traduccion').textContent"))
            assertEquals("2", js(s, "String(${card(1)}.children.length)"))
            assertEquals("0", js(s, "String(${card(1)}.querySelector('.lector-traduccion').children.length)"))

            // 6. En espera: el original se ve, en gris (AA del tema claro), y debajo "Traduciendo…".
            bridge.show(3, Card.Waiting("Traduciendo…"), labels, CardAnchor.SCREEN)
            val p3 = "document.querySelectorAll('p')[2]"
            assertEquals("false", js(s, "String($p3.classList.contains('lector-oculto'))"))
            assertEquals("rgb(107, 114, 128)", js(s, "getComputedStyle($p3).color"))
            assertEquals("Traduciendo…", js(s, "${card(3)}.textContent"))
            assertEquals("Traduciendo…", js(s, "${card(3)}.getAttribute('aria-label')"))
            // Al llegar la traducción, pasa a Solo y el original se oculta.
            bridge.show(3, Card.Solo("Tres"), labels, CardAnchor.SCREEN)
            assertEquals("none", js(s, "getComputedStyle($p3).display"))

            // 7. Salir de "solo": vuelven los originales y las tarjetas pierden el estilo copiado.
            bridge.setMode("intercalado")
            assertEquals("0", js(s, "String(document.querySelectorAll('.lector-oculto').length)"))
            assertEquals("0", js(s, "String(Array.prototype.filter.call(document.querySelectorAll('aside.lector-tarjeta'), function (c) { return c.style.length > 0 || c.hasAttribute('data-lector-original'); }).length)"))
            assertEquals(style["display"], js(s, "getComputedStyle($p1).display"))
            // Fuera de "solo", la tarjeta Solo y showOriginal no hacen nada (no se oculta ningún original).
            assertEquals("false", js(s, ParagraphScripts.insert(5, Card.Solo("x"), labels, CardAnchor.SCREEN)))
            assertEquals("false", js(s, ParagraphScripts.showOriginal(1, true, CardAnchor.SCREEN)))
            assertEquals("0", js(s, "String(document.querySelectorAll('.lector-oculto').length)"))

            // 8. Modos ("screen"): con el párrafo cortado arriba (A), su tarjeta no empuja lo que se lee (B).
            js(s, "var r = document.querySelectorAll('p')[9].getBoundingClientRect(); window.scrollBy(0, r.top + r.height / 2); 'ok'")
            Thread.sleep(300)
            val cutA = 10 // el p[9] (índice 10) queda cortado arriba
            val b2 = anchorIndex(s)
            assertEquals(cutA + 1, b2)
            val b2Top = rectTop(s, b2)
            bridge.show(cutA, Card.Text("Una traducción bastante larga para que la tarjeta ocupe varias líneas en la pantalla del teléfono."), labels, CardAnchor.SCREEN)
            assertTrue(abs(rectTop(s, b2) - b2Top) <= 1.0, "B se movió al insertar: ${rectTop(s, b2) - b2Top}")
            bridge.hide(cutA, CardAnchor.SCREEN)
            assertTrue(abs(rectTop(s, b2) - b2Top) <= 1.0, "B se movió al quitar: ${rectTop(s, b2) - b2Top}")

            // 8b. TAP ("self", Ruling F): el párrafo tocado (A, cortado arriba) se queda quieto y su tarjeta sale justo debajo.
            val aTop = rectTop(s, cutA)
            assertTrue(aTop < 0, "A no está cortado arriba: $aTop")
            bridge.show(cutA, Card.Text("Hola"), labels, CardAnchor.SELF)
            assertTrue(abs(rectTop(s, cutA) - aTop) <= 1.0, "A se movió: ${rectTop(s, cutA) - aTop}")
            val gap = js(s, "String(${card(cutA)}.getBoundingClientRect().top - $LEAVES[$cutA].getBoundingClientRect().bottom)")!!.toDouble()
            assertTrue(gap in 0.0..40.0, "la tarjeta no quedó bajo A: $gap")
            bridge.hide(cutA, CardAnchor.SELF)
            assertTrue(abs(rectTop(s, cutA) - aTop) <= 1.0, "A se movió al quitar: ${rectTop(s, cutA) - aTop}")

            // 8c. Un párrafo más alto que la pantalla (nadie empieza en pantalla): el ancla es el que cruza el borde de
            // arriba; una tarjeta bajo él (fuera de la pantalla) no mueve el texto que se ve.
            val tall = 30
            js(s, "(function () { var e = $LEAVES[$tall]; var t = e.textContent; for (var k = 0; k < 40; k++) e.textContent += ' ' + t; return 'ok'; })()")
            js(s, "var r = $LEAVES[$tall].getBoundingClientRect(); window.scrollBy(0, r.top + 100); 'ok'")
            Thread.sleep(300)
            val tallTop = rectTop(s, tall)
            assertTrue(tallTop < 0 && js(s, "String($LEAVES[$tall].getBoundingClientRect().bottom > window.innerHeight)") == "true", "no cruza la pantalla")
            assertEquals(tall, anchorIndex(s))
            bridge.show(tall, Card.Text("Una traducción bajo el párrafo largo."), labels, CardAnchor.SCREEN)
            assertTrue(abs(rectTop(s, tall) - tallTop) <= 1.0, "el texto visible se movió: ${rectTop(s, tall) - tallTop}")
            bridge.hide(tall, CardAnchor.SCREEN)
            assertTrue(abs(rectTop(s, tall) - tallTop) <= 1.0, "el texto visible se movió al quitar: ${rectTop(s, tall) - tallTop}")

            // 9. Quitar todas también muestra cualquier original oculto.
            bridge.setMode("solo")
            bridge.show(12, Card.Solo("Doce"), labels, CardAnchor.SCREEN)
            assertEquals("1", js(s, "String(document.querySelectorAll('.lector-oculto').length)"))
            val href = s.navigator()!!.currentLocator.value.href.toString()
            assertEquals(true, bridge.hideAll(href))
            assertEquals("0", js(s, "String(document.querySelectorAll('.lector-oculto').length + document.querySelectorAll('aside.lector-tarjeta').length)"))
            bridge.setMode("tocar")
        }
    }

    /** `top` de la hoja [k] (si está oculta, el de su tarjeta), como lo mide ParagraphScripts. */
    private fun rectTop(s: ActivityScenario<ReaderActivity>, k: Int): Double =
        js(s, "(function () { var e = $LEAVES[$k]; if (e.classList.contains('lector-oculto')) e = e.nextElementSibling;" +
            " return String(e.getBoundingClientRect().top); })()")!!.toDouble()

    /**
     * El ancla "screen" de Ruling C, medida aparte: el primer bloque hoja que empieza en pantalla (0 ≤ top < alto,
     * alto > 0); si no hay, el que cruza el borde de arriba; si no, -1.
     */
    private fun anchorIndex(s: ActivityScenario<ReaderActivity>): Int =
        js(s, "(function () { var all = $LEAVES, h = window.innerHeight, cross = -1; for (var k = 0; k < all.length; k++) { var e = all[k];" +
            " if (e.classList.contains('lector-oculto')) e = e.nextElementSibling; var r = e.getBoundingClientRect();" +
            " if (r.height <= 0) continue; if (r.top >= 0 && r.top < h) return String(k);" +
            " if (cross < 0 && r.top < 0 && r.bottom > 0) cross = k; if (r.top >= h) break; } return String(cross); })()")!!.toInt()

    private fun card(i: Int) = "document.querySelector('aside.lector-tarjeta[data-lector-i=\"$i\"]')"

    private fun centerOf(s: ActivityScenario<ReaderActivity>, element: String): Pair<Double, Double> {
        val c = JSONObject(js(s, "var r = $element.getBoundingClientRect(); JSON.stringify({x: r.left + r.width / 2, y: r.top + r.height / 2})")!!)
        return c.getDouble("x") to c.getDouble("y")
    }

    private fun topOfFirstP(s: ActivityScenario<ReaderActivity>): Double =
        js(s, "String(document.querySelectorAll('p')[0].getBoundingClientRect().top)")!!.toDouble()

    private fun waitFor(what: String, done: () -> Boolean) = rule.waitUntil(what, 10_000) { done() }

    private fun ActivityScenario<ReaderActivity>.navigator(): EpubNavigatorFragment? {
        var nav: EpubNavigatorFragment? = null
        onActivity { a -> nav = a.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull() }
        return nav
    }

    private fun rawJs(s: ActivityScenario<ReaderActivity>, script: String): String? {
        val nav = s.navigator() ?: return null
        return runBlocking(Dispatchers.Main) { nav.evaluateJavascript(script) }
    }

    /** Como `js` de MaliciousEpubOnDeviceTest: el valor ya sin comillas JSON (o null). */
    private fun js(s: ActivityScenario<ReaderActivity>, script: String): String? {
        val raw = rawJs(s, script) ?: return null
        return JSONArray("[$raw]").opt(0)?.takeIf { it != JSONObject.NULL }?.toString()
    }

    private companion object {
        const val CARD = "document.querySelector('aside.lector-tarjeta[data-lector-i=\"1\"]')"

        /** Las hojas, como las cuenta ParagraphScripts (sin tarjetas, sin contenedores). */
        const val LEAVES = "Array.prototype.filter.call(document.querySelectorAll('${ParagraphScripts.SELECTOR}'), " +
            "function (e) { return !e.closest('.lector-tarjeta') && !e.querySelector('${ParagraphScripts.SELECTOR}'); })"
    }
}
