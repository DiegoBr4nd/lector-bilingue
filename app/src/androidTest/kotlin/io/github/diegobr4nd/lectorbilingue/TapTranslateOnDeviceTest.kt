package io.github.diegobr4nd.lectorbilingue

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tocar y traducir de punta a punta en el teléfono (Ruling D e I): el Lector de verdad, el [LectorApp.translations]
 * de verdad con el modelo inglés → español ya instalado (si no está, la prueba se salta) y toques REALES inyectados en
 * la pantalla (no `input tap`), para comprobar que el punto de `onTap` de Readium coincide con el del puente.
 * Con `-e shots true` guarda capturas en la caché de la app; sin esa opción las borra. Los registros solo llevan
 * números y booleanos.
 */
@RunWith(AndroidJUnit4::class)
class TapTranslateOnDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LectorApp
    private val dir = File(app.cacheDir, "tap_test").apply { deleteRecursively(); mkdirs() }
    private val shotsDir = File(app.cacheDir, "tap_shots")
    private val shots = InstrumentationRegistry.getArguments().getString("shots") == "true"
    private val created = mutableListOf<String>()

    @After fun cleanUp() = runBlocking<Unit> {
        for (id in created) {
            app.openBooks.close(id)
            app.books.delete(id)
        }
        dir.deleteRecursively()
        if (!shots) shotsDir.deleteRecursively()
    }

    @Test fun tocarTraduceDebajoOtraVezCierraYVuelveTrasCambiarDeCapitulo() = runBlocking<Unit> {
        assumeTrue("falta el modelo inglés → español en el teléfono", enEsInstalled())
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        app.books.setDirection(id, "en-es") // el libro inventado está en español: se elige la dirección del modelo
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitFor("página lista") { js(s, PAGE_READY) == "70" }
            waitFor("botón de dirección") { rule.onAllNodes(hasContentDescription(EN_ES_DESC)).fetchSemanticsNodes().isNotEmpty() }

            // Un párrafo en la mitad de la pantalla (lejos de las barras): su índice entre los párrafos (el h1 es el 0).
            val p = JSONObject(js(s, MIDDLE_PARAGRAPH)!!)
            val index = p.getInt("i")
            val nth = index - 1 // posición entre los <p>
            val (sx, sy) = screenPoint(s, p.getDouble("x"), p.getDouble("y"))
            val topBefore = topOf(s, nth)

            // 1. Toque real → la tarjeta aparece debajo de ese párrafo con una traducción de verdad.
            tap(s, sx, sy)
            waitFor("tarjeta bajo el párrafo $index", 15_000) { js(s, cardMatches(nth, index)) == "true" }
            waitFor("traducción", 60_000) { js(s, "${card(nth)}.dataset.lectorEstado") == "texto" }
            val text = js(s, "${card(nth)}.textContent")!!
            assertTrue(text.isNotBlank(), "tarjeta vacía")
            assertTrue(!text.startsWith("T("), "no es el motor falso")
            assertTrue(abs(topOf(s, nth) - topBefore) <= 1.0, "el párrafo se movió")
            assertEquals("1", js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)"))
            screenshot("tarjeta-texto")

            // 2. Otro toque en el mismo párrafo → se cierra.
            tap(s, sx, sy)
            waitFor("tarjeta cerrada") { js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)") == "0" }

            // 3. Abrir otra vez (ya en caché), ir al capítulo 3 por el Índice y volver: la tarjeta abierta reaparece.
            tap(s, sx, sy)
            waitFor("tarjeta otra vez", 15_000) { js(s, "${card(nth)}.dataset.lectorEstado") == "texto" }
            goToChapter(s, "Capítulo tres")
            waitFor("en el capítulo 3") { js(s, "location.pathname") == "/OEBPS/c3.xhtml" && js(s, PAGE_READY) == "70" }
            assertEquals("0", js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)"))
            goToChapter(s, "Capítulo uno")
            waitFor("tarjeta repuesta en el capítulo 1", 15_000) {
                js(s, "location.pathname") == "/OEBPS/c1.xhtml" && js(s, "${card(nth)}.dataset.lectorEstado") == "texto"
            }
            assertEquals(text, js(s, "${card(nth)}.textContent"))
            assertEquals("1", js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)"))
        }
    }

    @Test fun elBotonDeDireccionGuardaEspanolInglesYCierraLasTarjetas() = runBlocking<Unit> {
        assumeTrue("falta el modelo inglés → español en el teléfono", enEsInstalled())
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        app.books.setDirection(id, "en-es")
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitFor("página lista") { js(s, PAGE_READY) == "70" }
            waitFor("botón de dirección") { rule.onAllNodes(hasContentDescription(EN_ES_DESC)).fetchSemanticsNodes().isNotEmpty() }
            val p = JSONObject(js(s, MIDDLE_PARAGRAPH)!!)
            val (sx, sy) = screenPoint(s, p.getDouble("x"), p.getDouble("y"))
            tap(s, sx, sy)
            waitFor("tarjeta", 60_000) { js(s, "${card(p.getInt("i") - 1)}.dataset.lectorEstado") == "texto" }

            // El botón: 48 dp, con nombre para TalkBack y su acción "cambiar el idioma".
            val button = rule.onNodeWithContentDescription(EN_ES_DESC)
            button.assertHeightIsAtLeast(48.dp)
            assertEquals("cambiar el idioma", button.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
            rule.onNodeWithText("EN → ES", useUnmergedTree = true).assertExists()
            button.performClick()

            // La hoja marca la actual con texto para TalkBack (no solo color).
            rule.onNode(hasText("Inglés → español") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Dirección actual"))
                .assertExists()
            screenshot("hoja-direccion")
            rule.onNodeWithText("Español → inglés").assertHeightIsAtLeast(48.dp).performClick()

            waitFor("dirección guardada") { runBlocking { app.books.get(id)?.direction } == "es-en" }
            waitFor("botón ES → EN") {
                rule.onAllNodes(hasContentDescription("Idioma de traducción: español a inglés")).fetchSemanticsNodes().isNotEmpty()
            }
            waitFor("tarjetas cerradas") { js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)") == "0" }
        }
    }

    private suspend fun enEsInstalled(): Boolean {
        withTimeout(10_000) { app.hub.loaded.first { it } }
        return app.hub.pairs.value.any { pair -> pair.pair == "en-es" && pair.rows.any { it.installed } }
    }

    private fun goToChapter(s: ActivityScenario<ReaderActivity>, chapter: String) {
        // Las barras pueden estar ocultas: se espera a que el Índice esté a la vista.
        waitFor("Índice a la vista") { rule.onAllNodes(hasContentDescription("Índice")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Índice").performClick()
        rule.onNodeWithText(chapter).performClick()
        rule.waitForIdle()
    }

    /** Toque real (abajo y arriba) en px de pantalla, como lo haría un dedo. Solo con el Lector al frente. */
    private fun tap(s: ActivityScenario<ReaderActivity>, x: Float, y: Float) {
        var focused = false
        s.onActivity { focused = it.hasWindowFocus() }
        assumeTrue("el Lector no está al frente", focused)
        val down = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 60)) {
            val e = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            instrumentation.uiAutomation.injectInputEvent(e, true)
            e.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    /** Punto CSS de la página → px de pantalla: la esquina del WebView visible + CSS × densidad. */
    private fun screenPoint(s: ActivityScenario<ReaderActivity>, xCss: Double, yCss: Double): Pair<Float, Float> {
        val density = app.resources.displayMetrics.density
        val at = IntArray(2)
        s.onActivity { a ->
            val root = navigator(a)!!.requireView()
            visibleWebView(root)!!.getLocationOnScreen(at)
        }
        return (at[0] + xCss * density).toFloat() to (at[1] + yCss * density).toFloat()
    }

    /** El WebView del capítulo visible (el ViewPager tiene también el vecino, fuera de pantalla). */
    private fun visibleWebView(v: View): WebView? {
        if (v is WebView && v.isShown && v.getGlobalVisibleRect(android.graphics.Rect())) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) visibleWebView(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun topOf(s: ActivityScenario<ReaderActivity>, nth: Int): Double =
        js(s, "String(document.querySelectorAll('p')[$nth].getBoundingClientRect().top)")!!.toDouble()

    private fun card(nth: Int) = "document.querySelectorAll('p')[$nth].nextElementSibling"

    private fun cardMatches(nth: Int, index: Int) =
        "var c = ${card(nth)}; String(!!c && c.matches('aside.lector-tarjeta[data-lector-i=\"$index\"]'))"

    private fun waitFor(what: String, timeout: Long = 10_000, done: () -> Boolean) = rule.waitUntil(what, timeout) { done() }

    private fun screenshot(name: String) {
        if (!shots) return
        rule.waitForIdle()
        Thread.sleep(800) // El WebView pinta después: sin esto la captura puede salir con el cuadro anterior.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        shotsDir.mkdirs()
        File(shotsDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun navigator(a: ReaderActivity): EpubNavigatorFragment? =
        a.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()

    /** El valor del script ya sin comillas JSON (o null), como en ParagraphBridgeOnDeviceTest. */
    private fun js(s: ActivityScenario<ReaderActivity>, script: String): String? {
        var nav: EpubNavigatorFragment? = null
        s.onActivity { nav = navigator(it) }
        val raw = nav?.let { n -> runBlocking(Dispatchers.Main) { n.evaluateJavascript(script) } } ?: return null
        return JSONArray("[$raw]").opt(0)?.takeIf { it != JSONObject.NULL }?.toString()
    }

    private companion object {
        const val EN_ES_DESC = "Idioma de traducción: inglés a español"
        const val PAGE_READY = "document.readyState === 'complete' && !!window.readium && document.querySelectorAll('p').length"

        /** El primer <p> cuyo centro cae entre el 40 % y el 60 % del alto visible: índice (h1 = 0) y centro en px CSS. */
        const val MIDDLE_PARAGRAPH = "(function () { var ps = document.querySelectorAll('p'); var h = window.innerHeight;" +
            " for (var k = 0; k < ps.length; k++) { var r = ps[k].getBoundingClientRect(); var cy = r.top + r.height / 2;" +
            " if (cy > h * 0.4 && cy < h * 0.6) return JSON.stringify({i: k + 1, x: r.left + r.width / 2, y: cy}); }" +
            " return null; })()"
    }
}
