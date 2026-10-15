package io.github.diegobr4nd.lectorbilingue

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.data.PageTheme
import io.github.diegobr4nd.lectorbilingue.data.ReadingMode
import io.github.diegobr4nd.lectorbilingue.data.ReadingSettings
import io.github.diegobr4nd.lectorbilingue.data.TipId
import io.github.diegobr4nd.lectorbilingue.ui.reader.ReaderActivity
import io.github.diegobr4nd.lectorbilingue.ui.tips.TipCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pruebas en el teléfono de los consejos (3c-3). Con `-e shots true` guardan capturas en la caché de la app
 * (`tips_shots`); sin esa opción las borran.
 *
 * En el Lector: libros inventados, caché de traducciones EN MEMORIA ([InMemoryTranslations]: nunca la de Juan), y los
 * ajustes de lectura y los consejos vistos de Juan se guardan al empezar y se devuelven al terminar. Los registros no
 * llevan texto del libro.
 */
@RunWith(AndroidJUnit4::class)
class TipsOnDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LectorApp
    private val shotsDir = File(app.cacheDir, "tips_shots")
    private val shots = InstrumentationRegistry.getArguments().getString("shots") == "true"

    private val text = app.getString(R.string.tip_tap)
    private val gotIt = app.getString(R.string.tip_got_it)

    private val dir = File(app.cacheDir, "tips_test").apply { deleteRecursively(); mkdirs() }
    private val created = mutableListOf<String>()

    /** Los ajustes de lectura de Juan: se devuelven tal cual en [cleanUp]. */
    private val savedReading = app.settings.readingSettings

    /** Los consejos que Juan ya vio: se devuelven en [cleanUp] (cada prueba empieza como un usuario nuevo). */
    private var savedTips: Set<TipId>? = null

    // Servicio de traducción de la prueba: motor real, caché en memoria (lo pone y lo devuelve la regla).
    @get:Rule val translations = InMemoryTranslations()

    @Before fun saveTips() = runBlocking {
        app.settings.loadSeenTips()
        savedTips = app.settings.seenTipsFlow.value
        app.settings.resetTips()
    }

    @After fun cleanUp() = runBlocking<Unit> {
        try {
            for (id in created) {
                app.openBooks.close(id)
                app.books.delete(id)
            }
            dir.deleteRecursively()
            if (!shots) shotsDir.deleteRecursively()
        } finally {
            app.settings.readingSettings = savedReading
            savedTips?.let { tips ->
                app.settings.resetTips()
                tips.forEach(app.settings::markTipSeen)
            }
        }
    }

    private fun saveShot(name: String) {
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        shotsDir.mkdirs()
        File(shotsDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** La tarjeta: región viva con el texto, "Entendido" ≥ 48 dp; a letra normal en una fila; al 200 % botón debajo. */
    @Test fun tarjetaSeOyeYSuBotonMide48() {
        var config by mutableStateOf(Triple(false, 1f, text)) // oscuro, letra, texto
        var dismissed = 0
        ActivityScenario.launch(ComponentActivity::class.java).use { s ->
            var px = 0
            s.onActivity { a -> px = a.resources.displayMetrics.widthPixels }
            s.onActivity { a ->
                a.setContent {
                    val (dark, f, shown) = config
                    CompositionLocalProvider(LocalDensity provides Density(px / 360f, fontScale = f)) {
                        LectorTheme(darkTheme = dark) {
                            Box(Modifier.size(360.dp, 500.dp), contentAlignment = Alignment.BottomCenter) {
                                Column(Modifier.wrapContentHeight().testTag("shot")) { TipCard(shown, onDismiss = { dismissed++ }) }
                            }
                        }
                    }
                }
            }
            for (dark in listOf(false, true)) for (f in listOf(1f, 2f)) {
                config = Triple(dark, f, text)
                rule.waitForIdle()
                val where = "${if (dark) "oscuro" else "claro"} letra ${(f * 100).toInt()}"
                val message = rule.onNode(hasContentDescription(text)).fetchSemanticsNode()
                assertEquals(LiveRegionMode.Polite, message.config[SemanticsProperties.LiveRegion], "$where: región viva")
                val button = rule.onNode(hasText(gotIt) and hasClickAction())
                button.assertHeightIsAtLeast(48.dp)
                val b = button.fetchSemanticsNode().boundsInRoot
                if (f == 1f) assertTrue(b.top < message.boundsInRoot.bottom, "$where: el botón no está al lado")
                else assertTrue(b.top >= message.boundsInRoot.bottom, "$where: el botón no está debajo")
                if (shots) saveShot("tarjeta-${if (dark) "oscuro" else "claro"}${if (f == 2f) "-letra200" else ""}.png")
            }
            rule.onNode(hasText(gotIt) and hasClickAction()).performClick()
            assertEquals(1, dismissed)
        }
    }

    // ---------- En el Lector ----------

    /** Usuario nuevo: tocar → (toque) → ocultar y modos → (ocultar) → nada; borde → consejo del capítulo. */
    @Test fun recorridoNuevoEnTocar() = runBlocking<Unit> {
        assumeTrue("falta el modelo inglés → español en el teléfono", enEsInstalled())
        openReader(EnglishBook.importAndOpen(app, dir).also { created += it }) { s ->
            // 1. Al abrir: "Toca un párrafo…", con su región viva.
            waitForTip(tipTap)

            // 2. Toque real en un párrafo de la mitad: el consejo pasa a "Toca otra vez…" cuando llega la traducción.
            val p = JSONObject(js(s, MIDDLE_PARAGRAPH) ?: error("no hay párrafo a mitad de pantalla"))
            val nth = p.getInt("i") - 1 // posición entre los <p> (el h1 es el 0)
            val (sx, sy) = screenPoint(s, p.getDouble("x"), p.getDouble("y"))
            tap(s, sx, sy)
            waitFor("traducción", 60_000) { js(s, "${card(nth)}.dataset.lectorEstado") == "texto" }
            waitForTip(tipHide)
            assertTrue(TipId.TAP in app.settings.seenTipsFlow.value, "tocar no marcó el consejo de tocar")
            assertFalse(hasTip(tipTap), "el consejo de tocar sigue a la vista")
            screenshot("recorrido-ocultar-y-modos")

            // 3. La tarjeta del consejo no tapa el párrafo tocado ni su traducción (la tarjeta de la 3b, debajo).
            val density = app.resources.displayMetrics.density
            val webAt = IntArray(2)
            val windowAt = IntArray(2)
            s.onActivity { a ->
                visibleWebView(navigator(a)!!.requireView())!!.getLocationOnScreen(webAt)
                a.window.decorView.getLocationOnScreen(windowAt)
            }
            val cardBottomCss = js(s, "String(${card(nth)}.getBoundingClientRect().bottom)")!!.toDouble()
            val cardBottom = webAt[1] + cardBottomCss * density
            val message = tipNode(tipHide).fetchSemanticsNode().boundsInWindow
            val button = rule.onNode(hasText(gotIt) and hasClickAction()).fetchSemanticsNode().boundsInWindow
            // El borde de la tarjeta queda unos dp por encima de su texto y su botón (sus márgenes internos).
            val tipTop = windowAt[1] + minOf(message.top, button.top) - TIP_INNER_TOP_DP * density
            assertTrue(tipTop > cardBottom, "el consejo ($tipTop px) tapa el párrafo tocado (hasta $cardBottom px)")

            // 4. Otro toque en el mismo párrafo (ocultar): sin consejo y "ocultar y modos" visto.
            tap(s, sx, sy)
            waitFor("tarjeta cerrada") { js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)") == "0" }
            waitFor("consejo de ocultar visto") { TipId.HIDE_AND_MODES in app.settings.seenTipsFlow.value }
            waitFor("sin consejo") { !hasTip(tipHide) }

            // 5. Al final del capítulo 1 (no es el último): "Sigue deslizando…". "Capítulo siguiente" lo cierra.
            js(s, "window.scrollTo(0, (document.scrollingElement || document.documentElement).scrollHeight); 'ok'")
            Thread.sleep(VISIBLE_SETTLE_MS + 1_000)
            waitForTip(tipEdge)
            screenshot("recorrido-borde")
            val next = app.getString(R.string.reader_chapter_next)
            waitFor("botón del capítulo siguiente") { rule.onAllNodes(hasContentDescription(next)).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription(next).performClick()
            waitFor("en el capítulo 2") { js(s, "location.pathname") == "/OEBPS/c2.xhtml" }
            waitFor("consejo del borde visto") { TipId.CHAPTER_EDGE in app.settings.seenTipsFlow.value }
            waitFor("sin consejo del borde") { !hasTip(tipEdge) }
        }
    }

    /** Libro en español: primero "Este libro se traduce al inglés…"; abrir la hoja de dirección lo cierra. */
    @Test fun libroEnEspanolMuestraDireccion() = runBlocking<Unit> {
        // Sin traducir nada: no hace falta el modelo.
        openReader(ReaderTestBook.importAndOpen(app, dir).also { created += it }) { _ ->
            val tipDirection = app.getString(
                R.string.tip_direction,
                app.getString(R.string.language_en),
                app.getString(R.string.reader_direction, "ES", "EN"),
            )
            waitForTip(tipDirection)
            assertFalse(hasTip(tipTap), "dos consejos a la vez")
            screenshot("direccion")

            val button = app.getString(R.string.reader_direction_desc, app.getString(R.string.language_es), app.getString(R.string.language_en))
            rule.onNodeWithContentDescription(button).performClick()
            waitFor("consejo de dirección visto") { TipId.DIRECTION in app.settings.seenTipsFlow.value }
            // Con la hoja abierta no hay consejo; al cerrarla sale el siguiente.
            waitFor("hoja de dirección abierta") { rule.onAllNodes(hasText("Español → inglés")).fetchSemanticsNodes().isNotEmpty() }
            assertFalse(hasTip(tipDirection), "el consejo de dirección sigue a la vista")
            assertFalse(hasTip(tipTap), "un consejo con la hoja abierta")
            Espresso.pressBack()
            waitForTip(tipTap)
            assertFalse(hasTip(tipDirection))
        }
    }

    /** Con la hoja Aa abierta la tarjeta no está; al cerrarla vuelve el mismo consejo. */
    @Test fun hojaAbiertaEsconde() = runBlocking<Unit> {
        // Sin traducir nada: no hace falta el modelo.
        openReader(EnglishBook.importAndOpen(app, dir).also { created += it }) { _ ->
            waitForTip(tipTap)
            val aa = app.getString(R.string.reading_title)
            waitFor("botón Aa") { rule.onAllNodes(hasContentDescription(aa)).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription(aa).performClick()
            waitFor("hoja Aa abierta") { rule.onAllNodes(hasText("Modo de lectura")).fetchSemanticsNodes().isNotEmpty() }
            rule.waitForIdle()
            assertFalse(hasTip(tipTap), "el consejo se ve con la hoja Aa abierta")
            Espresso.pressBack()
            waitForTip(tipTap)
            assertFalse(TipId.TAP in app.settings.seenTipsFlow.value, "esconderlo no lo marca visto")
        }
    }

    // ---------- Ayudas del Lector ----------

    private val tipTap get() = app.getString(R.string.tip_tap)
    private val tipHide get() = app.getString(R.string.tip_hide_modes)
    private val tipEdge get() = app.getString(R.string.tip_chapter_edge, app.getString(R.string.reader_toc))

    /** El texto del consejo [text]: región viva Polite, como lo dibuja TipCard. */
    private fun tipMatcher(text: String) =
        hasContentDescription(text) and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    private fun tipNode(text: String): SemanticsNodeInteraction = rule.onNode(tipMatcher(text))

    private fun hasTip(text: String): Boolean = rule.onAllNodes(tipMatcher(text)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForTip(text: String) = waitFor("consejo a la vista", 15_000) { hasTip(text) }

    private suspend fun enEsInstalled(): Boolean {
        withTimeout(10_000) { app.hub.loaded.first { it } }
        return app.hub.pairs.value.any { pair -> pair.pair == "en-es" && pair.rows.any { it.installed } }
    }

    /** Abre el Lector en modo Tocar (tema claro) con el libro [id] ya importado y espera la página. */
    private suspend fun openReader(id: String, block: suspend (ActivityScenario<ReaderActivity>) -> Unit) {
        app.settings.readingSettings = ReadingSettings(theme = PageTheme.LIGHT, mode = ReadingMode.TAP)
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitFor("página lista") { js(s, PAGE_READY) == "70" }
            block(s)
        }
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
        s.onActivity { a -> visibleWebView(navigator(a)!!.requireView())!!.getLocationOnScreen(at) }
        return (at[0] + xCss * density).toFloat() to (at[1] + yCss * density).toFloat()
    }

    /** El WebView del capítulo visible (el ViewPager tiene también el vecino, fuera de pantalla). */
    private fun visibleWebView(v: View): WebView? {
        if (v is WebView && v.isShown && v.getGlobalVisibleRect(android.graphics.Rect())) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) visibleWebView(v.getChildAt(i))?.let { return it }
        return null
    }

    /** La tarjeta de la 3b del párrafo [nth] (entre los <p>): va justo después de él. */
    private fun card(nth: Int) = "document.querySelectorAll('p')[$nth].nextElementSibling"

    private fun waitFor(what: String, timeout: Long = 10_000, done: () -> Boolean) = rule.waitUntil(what, timeout) { done() }

    private fun screenshot(name: String) {
        if (!shots) return
        rule.waitForIdle()
        Thread.sleep(800) // El WebView pinta después: sin esto la captura puede salir con el cuadro anterior.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        shotsDir.mkdirs()
        File(shotsDir, "lector-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        const val PAGE_READY = "document.readyState === 'complete' && !!window.readium && document.querySelectorAll('p').length"

        /** El de ReaderScreen (VisibleSettleMs): la página quieta antes de medir el borde. */
        const val VISIBLE_SETTLE_MS = 150L

        /** Lo que hay entre el borde de arriba de TipCard y su texto o su botón (márgenes internos, con holgura). */
        const val TIP_INNER_TOP_DP = 8f

        /** El primer <p> cuyo centro cae entre el 40 % y el 60 % del alto visible: índice (h1 = 0) y centro en px CSS. */
        const val MIDDLE_PARAGRAPH = "(function () { var ps = document.querySelectorAll('p'); var h = window.innerHeight;" +
            " for (var k = 0; k < ps.length; k++) { var r = ps[k].getBoundingClientRect(); var cy = r.top + r.height / 2;" +
            " if (cy > h * 0.4 && cy < h * 0.6) return JSON.stringify({i: k + 1, x: r.left + r.width / 2, y: cy}); }" +
            " return null; })()"
    }
}
