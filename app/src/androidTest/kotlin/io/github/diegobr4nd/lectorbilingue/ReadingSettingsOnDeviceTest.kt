package io.github.diegobr4nd.lectorbilingue

import android.util.Log
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.data.LineHeightLevel
import io.github.diegobr4nd.lectorbilingue.data.MarginLevel
import io.github.diegobr4nd.lectorbilingue.data.PageTheme
import io.github.diegobr4nd.lectorbilingue.data.ReadingFont
import io.github.diegobr4nd.lectorbilingue.data.ReadingSettings
import io.github.diegobr4nd.lectorbilingue.ui.reader.Card
import io.github.diegobr4nd.lectorbilingue.ui.reader.CardLabels
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ajustes de lectura aplicados al Lector de verdad (spec 3c-1 §5.2, §5.3 y §11), con el libro inventado de
 * [ReaderTestBook]: posición y tarjetas al cambiar un ajuste, paletas de la tarjeta por tema, fuente propia y negro puro.
 * Los ajustes de la persona se guardan al empezar y se devuelven al terminar. Los registros solo llevan números.
 */
@RunWith(AndroidJUnit4::class)
class ReadingSettingsOnDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LectorApp
    private val dir = File(app.cacheDir, "reading_settings_test").apply { deleteRecursively(); mkdirs() }
    private val created = mutableListOf<String>()
    private val labels = CardLabels("Traducción", "Traduciendo…", "Preparando el traductor…")

    /** Los ajustes de Juan: se devuelven tal cual en [cleanUp]. */
    private val saved = app.settings.readingSettings

    @After fun cleanUp() = runBlocking<Unit> {
        app.settings.readingSettings = saved
        for (id in created) {
            app.openBooks.close(id)
            app.books.delete(id)
        }
        dir.deleteRecursively()
    }

    @Test fun cambiarAjusteMantieneLaPosicionYLasTarjetas() = runBlocking<Unit> {
        app.settings.readingSettings = ReadingSettings(theme = PageTheme.LIGHT)
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            val bridge = ParagraphBridge { s.navigator() }
            waitFor("página lista") { js(s, PAGE_READY) == "70" }
            goToChapter2(s)
            waitFor("tema claro en el capítulo 2") { js(s, THEME_ATTR) == "claro" }

            // Una tarjeta abierta bajo un párrafo a la vista, y una marca en la página para saber si se recarga.
            val index = js(s, FIRST_VISIBLE)!!.toInt() + 3
            bridge.show(index, Card.Text("Hola"), labels)
            assertEquals("Hola", js(s, "${card(index)}.textContent"))
            js(s, "window.__marca = 7; 'ok'")
            val firstBefore = js(s, FIRST_VISIBLE)!!.toInt()
            val progressionBefore = js(s, PROGRESSION)!!.toDouble()
            assertTrue(progressionBefore in 0.2..0.8, "no está a mitad: $progressionBefore")

            // Tamaño 1,0 → 1,5 y tema sepia a la vez, como si la persona los cambiara en la hoja.
            app.settings.readingSettings = ReadingSettings(theme = PageTheme.SEPIA, fontScale = 1.5)
            waitFor("letra al 150 %") { js(s, "getComputedStyle(document.querySelectorAll('p')[0]).fontSize") == "24px" }
            waitFor("tarjeta en sepia") { js(s, "getComputedStyle(${card(index)}).getPropertyValue('--lector-fondo').trim()") == "#f0e4cc" }
            settle()

            // La página no se recargó, la tarjeta sigue y el lugar es el mismo (Ruling D: primer párrafo visible).
            assertEquals("7", js(s, "String(window.__marca)"))
            assertEquals("Hola", js(s, "${card(index)}.textContent"))
            assertEquals("sepia", js(s, THEME_ATTR))
            assertEquals("rgb(240, 228, 204)", js(s, "getComputedStyle(${card(index)}).backgroundColor"))
            val firstAfter = js(s, FIRST_VISIBLE)!!.toInt()
            val progressionAfter = js(s, PROGRESSION)!!.toDouble()
            Log.i(TAG, "primer párrafo $firstBefore → $firstAfter; progresión $progressionBefore → $progressionAfter")
            assertEquals(firstBefore, firstAfter, "cambió el primer párrafo visible")
            assertTrue(abs(progressionAfter - progressionBefore) <= 0.02, "progresión $progressionBefore → $progressionAfter")
            val locator = runBlocking(Dispatchers.Main) { s.navigator()!!.currentLocator.value }
            assertTrue(locator.href.toString().endsWith("c2.xhtml"))

            // Y de vuelta a 1,0: otra vez el mismo párrafo.
            app.settings.readingSettings = ReadingSettings(theme = PageTheme.SEPIA)
            waitFor("letra al 100 %") { js(s, "getComputedStyle(document.querySelectorAll('p')[0]).fontSize") == "16px" }
            settle()
            assertEquals(firstBefore, js(s, FIRST_VISIBLE)!!.toInt(), "cambió el primer párrafo visible al volver")
            assertEquals("7", js(s, "String(window.__marca)"))
        }
    }

    /** Cada tema pinta la tarjeta con su paleta en todos los estados (Ruling E): fondo, texto, línea, enlace y rayas. */
    @Test fun cadaTemaPintaLaTarjetaEnTodosLosEstados() = runBlocking<Unit> {
        app.settings.readingSettings = ReadingSettings(theme = PageTheme.LIGHT)
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            val bridge = ParagraphBridge { s.navigator() }
            waitFor("página lista") { js(s, PAGE_READY) == "70" }
            waitFor("tema claro") { js(s, THEME_ATTR) == "claro" }
            for ((theme, p) in PALETTES) {
                app.settings.readingSettings = ReadingSettings(theme = theme)
                waitFor("tema ${p.attr}") { js(s, THEME_ATTR) == p.attr }
                rule.waitForIdle()
                val c = card(1)

                bridge.show(1, Card.Text("Hola"), labels)
                assertEquals(p.fondo, js(s, "getComputedStyle($c).backgroundColor"), "${p.attr}: fondo")
                assertEquals(p.texto, js(s, "getComputedStyle($c).color"), "${p.attr}: texto")
                assertEquals(p.linea, js(s, "getComputedStyle($c).borderLeftColor"), "${p.attr}: línea")

                bridge.show(1, Card.Skeleton, labels)
                assertEquals(p.texto, js(s, "getComputedStyle($c.querySelector('.lector-esqueleto')).color"), "${p.attr}: esqueleto")
                val stripes = js(s, "getComputedStyle($c, '::after').backgroundImage")!!
                assertTrue(stripes.contains(p.esqueleto), "${p.attr}: rayas $stripes")

                bridge.show(1, Card.MissingModel("Falta el idioma", "Descargar", "Falta el idioma. Descargar"), labels)
                assertEquals(p.errorFondo, js(s, "getComputedStyle($c).backgroundColor"), "${p.attr}: fondo de error")
                assertEquals(p.errorLinea, js(s, "getComputedStyle($c).borderLeftColor"), "${p.attr}: línea de error")
                assertEquals(p.errorEnlace, js(s, "getComputedStyle($c.querySelector('.lector-reintentar')).color"), "${p.attr}: enlace")
                assertEquals(p.texto, js(s, "getComputedStyle($c.querySelector('.lector-reintentar'), '::before').color"), "${p.attr}: punto")
                bridge.hide(1)
            }
        }
    }

    @Test fun fuenteAtkinsonSeAplica() = runBlocking<Unit> {
        app.settings.readingSettings = ReadingSettings(theme = PageTheme.LIGHT)
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitFor("página lista") { js(s, PAGE_READY) == "70" }
            js(s, "window.__marca = 7; 'ok'")
            app.settings.readingSettings = ReadingSettings(theme = PageTheme.LIGHT, font = ReadingFont.ATKINSON)
            waitFor("familia Atkinson") {
                js(s, "getComputedStyle(document.querySelectorAll('p')[0]).fontFamily")!!.contains("Atkinson Hyperlegible")
            }
            // La fuente de verdad (servida por readium_assets), no una de reserva.
            js(s, "document.fonts.load('16px \"Atkinson Hyperlegible\"'); 'ok'")
            waitFor("Atkinson cargada") { js(s, "String(document.fonts.check('16px \"Atkinson Hyperlegible\"'))") == "true" }
            waitFor("cara cargada") {
                js(s, "String(Array.from(document.fonts).some(function (f) { return f.family.indexOf('Atkinson') >= 0 && f.status === 'loaded'; }))") == "true"
            }
            assertEquals("7", js(s, "String(window.__marca)"))
        }
    }

    /**
     * Ruling K: tema, tamaño, fuente y márgenes se aplican con publisherStyles = true (el libro conserva su
     * interlineado); el interlineado propio sí lo pasa a false ("readium-advanced-on").
     */
    @Test fun conElEstiloDelLibroSeAplicanTemaTamanoFuenteYMargenes() = runBlocking<Unit> {
        app.settings.readingSettings = ReadingSettings(theme = PageTheme.LIGHT)
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitFor("página lista") { js(s, PAGE_READY) == "70" }
            settle()
            js(s, "window.__marca = 7; 'ok'")
            val padBefore = px(js(s, BODY_PAD))
            val lineBefore = js(s, P_LINE)
            val elegidos = ReadingSettings(
                theme = PageTheme.SEPIA, fontScale = 1.5, font = ReadingFont.LITERATA, margins = MarginLevel.WIDE,
            )
            app.settings.readingSettings = elegidos
            waitFor("letra al 150 %") { js(s, P_SIZE) == "24px" }
            waitFor("Literata") { js(s, "getComputedStyle(document.querySelectorAll('p')[0]).fontFamily")!!.contains("Literata") }
            waitFor("fondo sepia") { js(s, "getComputedStyle(document.documentElement).backgroundColor") == "rgb(250, 244, 232)" }
            waitFor("márgenes anchos") { px(js(s, BODY_PAD)) > padBefore * 1.4 }
            assertFalse(js(s, ROOT_STYLE).orEmpty().contains("readium-advanced-on"), "publisherStyles debería seguir en true")
            val lineWithBook = js(s, P_LINE)
            Log.i(TAG, "relleno $padBefore → ${px(js(s, BODY_PAD))}; interlineado $lineBefore → $lineWithBook (libro)")

            // Interlineado "Amplio": ahora sí se sobrescribe el estilo del libro (1,8 × 24 px).
            app.settings.readingSettings = elegidos.copy(lineHeight = LineHeightLevel.WIDE)
            waitFor("interlineado 1,8") { abs(px(js(s, P_LINE)) - 43.2) < 0.5 }
            assertTrue(js(s, ROOT_STYLE).orEmpty().contains("readium-advanced-on"))
            // Lo demás sigue.
            assertEquals("24px", js(s, P_SIZE))
            assertEquals("rgb(250, 244, 232)", js(s, "getComputedStyle(document.documentElement).backgroundColor"))
            assertEquals("7", js(s, "String(window.__marca)"), "la página no se recargó")
        }
    }

    private fun px(v: String?): Double = v?.removeSuffix("px")?.toDoubleOrNull() ?: 0.0

    @Test fun negroEsNegro() = runBlocking<Unit> {
        app.settings.readingSettings = ReadingSettings(theme = PageTheme.BLACK)
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitFor("página lista") { js(s, PAGE_READY) == "70" }
            waitFor("tema negro") { js(s, THEME_ATTR) == "negro" }
            assertEquals("rgb(0, 0, 0)", js(s, "getComputedStyle(document.documentElement).backgroundColor"))
            // Y Oscuro es un gris, no negro.
            app.settings.readingSettings = ReadingSettings(theme = PageTheme.DARK)
            waitFor("tema oscuro") { js(s, THEME_ATTR) == "oscuro" }
            waitFor("página gris oscuro") { js(s, "getComputedStyle(document.documentElement).backgroundColor") == "rgb(30, 30, 30)" }
        }
    }

    // ---------- Ayudas ----------

    private data class Palette(
        val attr: String, val fondo: String, val texto: String, val linea: String, val esqueleto: String,
        val errorFondo: String, val errorLinea: String, val errorEnlace: String,
    )

    /** Las paletas de tarjeta.css, como las da getComputedStyle. */
    private val PALETTES = listOf(
        PageTheme.SEPIA to Palette("sepia", "rgb(240, 228, 204)", "rgb(43, 33, 24)", "rgb(138, 90, 31)", "rgb(216, 199, 163)", "rgb(247, 223, 213)", "rgb(168, 50, 30)", "rgb(132, 32, 21)"),
        PageTheme.DARK to Palette("oscuro", "rgb(42, 50, 71)", "rgb(230, 230, 230)", "rgb(142, 162, 255)", "rgb(74, 84, 112)", "rgb(58, 37, 35)", "rgb(255, 138, 128)", "rgb(255, 180, 169)"),
        PageTheme.BLACK to Palette("negro", "rgb(21, 27, 44)", "rgb(230, 230, 230)", "rgb(142, 162, 255)", "rgb(52, 61, 87)", "rgb(42, 23, 21)", "rgb(255, 138, 128)", "rgb(255, 180, 169)"),
        PageTheme.LIGHT to Palette("claro", "rgb(238, 242, 255)", "rgb(31, 41, 55)", "rgb(59, 91, 219)", "rgb(199, 208, 234)", "rgb(254, 243, 242)", "rgb(180, 35, 24)", "rgb(145, 32, 24)"),
    )

    private fun goToChapter2(s: ActivityScenario<ReaderActivity>) {
        val publication = app.openBooks.get(created.last())!!
        val target = publication.locatorFromLink(publication.readingOrder[1])!!.copyWithLocations(progression = 0.45)
        s.onActivity { it.navigator()!!.go(target) }
        waitFor("capítulo 2 a mitad") {
            js(s, "location.pathname") == "/OEBPS/c2.xhtml" && js(s, PAGE_READY) == "70" &&
                (js(s, PROGRESSION)?.toDoubleOrNull() ?: 0.0) > 0.2
        }
        settle()
    }

    /** Deja que la página y los efectos de Compose (que en la prueba avanzan con la regla) se asienten. */
    private fun settle() {
        Thread.sleep(800)
        rule.waitForIdle()
        Thread.sleep(400)
        rule.waitForIdle()
    }

    private fun card(index: Int) = "document.querySelector('aside.lector-tarjeta[data-lector-i=\"$index\"]')"

    private fun waitFor(what: String, timeout: Long = 10_000, done: () -> Boolean) = rule.waitUntil(what, timeout) { done() }

    private fun ReaderActivity.navigator(): EpubNavigatorFragment? =
        supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()

    private fun ActivityScenario<ReaderActivity>.navigator(): EpubNavigatorFragment? {
        var nav: EpubNavigatorFragment? = null
        onActivity { nav = it.navigator() }
        return nav
    }

    /** El valor del script ya sin comillas JSON (o null), como en ParagraphBridgeOnDeviceTest. */
    private fun js(s: ActivityScenario<ReaderActivity>, script: String): String? {
        val nav = s.navigator()
        val raw = nav?.let { n -> runBlocking(Dispatchers.Main) { n.evaluateJavascript(script) } } ?: return null
        return JSONArray("[$raw]").opt(0)?.takeIf { it != JSONObject.NULL }?.toString()
    }

    private companion object {
        const val TAG = "AjustesLecturaTest"
        const val PAGE_READY = "document.readyState === 'complete' && !!window.readium && document.querySelectorAll('p').length"
        const val THEME_ATTR = "document.documentElement.getAttribute('data-lector-tema')"
        const val ROOT_STYLE = "document.documentElement.getAttribute('style')"
        const val BODY_PAD = "getComputedStyle(document.body).paddingLeft"
        const val P_SIZE = "getComputedStyle(document.querySelectorAll('p')[0]).fontSize"
        const val P_LINE = "getComputedStyle(document.querySelectorAll('p')[0]).lineHeight"

        /** Posición del primer <p> que asoma arriba (su borde de abajo ya pasó el tope de la página visible). */
        const val FIRST_VISIBLE = "(function () { var ps = document.querySelectorAll('p');" +
            " for (var k = 0; k < ps.length; k++) if (ps[k].getBoundingClientRect().bottom > 0) return String(k);" +
            " return '-1'; })()"

        /** Progresión dentro del capítulo según el desplazamiento (no el localizador, que queda viejo tras un cambio). */
        const val PROGRESSION = "String(window.scrollY / (document.scrollingElement || document.documentElement).scrollHeight)"
    }
}
