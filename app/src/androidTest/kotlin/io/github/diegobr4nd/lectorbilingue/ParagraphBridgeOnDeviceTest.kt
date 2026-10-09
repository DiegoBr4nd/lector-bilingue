package io.github.diegobr4nd.lectorbilingue

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.ui.reader.Card
import io.github.diegobr4nd.lectorbilingue.ui.reader.CardLabels
import io.github.diegobr4nd.lectorbilingue.ui.reader.PageParagraph
import io.github.diegobr4nd.lectorbilingue.ui.reader.ParagraphBridge
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
    private val labels = CardLabels("Traducción", "Traduciendo…", "Preparando el traductor…")

    @After fun cleanUp() = runBlocking<Unit> {
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
            bridge.show(1, Card.Text("Hola"), labels)
            assertEquals("true", js(s, "var c = document.querySelectorAll('p')[0].nextElementSibling; String(!!c && c.matches('aside.lector-tarjeta[data-lector-i=\"1\"]'))"))
            assertEquals("Hola", js(s, CARD + ".textContent"))
            assertEquals("note", js(s, CARD + ".getAttribute('role')"))
            assertEquals("Traducción", js(s, CARD + ".getAttribute('aria-label')"))
            assertTrue(abs(topOfFirstP(s) - topBefore) <= 1.0, "el párrafo se movió")
            val border = js(s, "getComputedStyle($CARD).borderLeftWidth")!!.removeSuffix("px").toDouble()
            assertTrue(border in 3.0..5.0, "borde de la hoja: $border")
            assertEquals("1", js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)"))

            // 3. Esqueleto: reemplaza el contenido de la misma tarjeta, con su span.
            bridge.show(1, Card.Skeleton, labels)
            assertEquals("Traduciendo…", js(s, "$CARD.querySelector('span.lector-esqueleto').textContent"))
            assertEquals("1", js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)"))

            // 4. Texto hostil: entra como texto; nada se ejecuta ni se crea marcado.
            val evil = "\"); alert(1); (\" </script><img src=x onerror=alert(2)>   fin <img src=x onerror=\"window.__lectorXss=1\">"
            val imgs = js(s, "String(document.querySelectorAll('img').length)")
            bridge.show(1, Card.Text(evil), labels)
            Thread.sleep(500) // Por si un onerror llegara a cargarse.
            assertEquals(imgs, js(s, "String(document.querySelectorAll('img').length)"))
            assertEquals("undefined", js(s, "typeof window.__lectorXss"))
            assertEquals(evil, js(s, "$CARD.textContent"))
            assertEquals("0", js(s, "String($CARD.children.length)"))

            // 5. Quitar.
            bridge.hide(1)
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
            bridge.show(2, Card.Text("Hola"), labels)
            val real = "document.querySelectorAll('p')[1].nextElementSibling"
            assertEquals("true", js(s, "String($real.matches('aside.lector-tarjeta[data-lector-i=\"2\"]'))"))
            assertEquals("Hola", js(s, "$real.textContent"))
            assertEquals("block", js(s, "getComputedStyle($real).display"))
            assertEquals("visible", js(s, "getComputedStyle($real).visibility"))
            assertEquals("falsa", js(s, "document.getElementById('falsa').textContent"))
            val (rx, ry) = centerOf(s, real)
            assertEquals(2, bridge.indexAt((rx * density).toFloat(), (ry * density).toFloat(), density))

            // 3. Quitar la 2 quita la de verdad y deja la falsa; quitar otra vez no encuentra nada.
            bridge.hide(2)
            assertEquals("false", js(s, "String(!!$real && $real.matches('aside'))"))
            assertEquals("falsa", js(s, "document.getElementById('falsa').textContent"))

            // 4. Un punto que no es finito no llega a la página.
            assertEquals(emptyList(), bridge.paragraphsAt(Float.NaN, 10f, density))
            assertEquals(null, bridge.indexAt(10f, 10f, 0f))
        }
    }

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
    }
}
