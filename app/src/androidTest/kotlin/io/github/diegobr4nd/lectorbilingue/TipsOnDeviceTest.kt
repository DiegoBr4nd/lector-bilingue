package io.github.diegobr4nd.lectorbilingue

import android.content.Intent
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.diegobr4nd.lectorbilingue.ui.howto.HowToScreen
import io.github.diegobr4nd.lectorbilingue.ui.library.LibraryContent
import io.github.diegobr4nd.lectorbilingue.ui.library.LibraryUiState
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
import io.github.diegobr4nd.lectorbilingue.ui.tips.GUTENBERG_URL
import io.github.diegobr4nd.lectorbilingue.ui.tips.STANDARD_EBOOKS_URL
import io.github.diegobr4nd.lectorbilingue.ui.tips.TipCard
import io.github.diegobr4nd.lectorbilingue.ui.tips.browserIntent
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

    // Los consejos que Juan ya vio: la regla guarda el valor exacto y lo devuelve al terminar (Ruling C). Aquí no los
    // marca todos: cada prueba empieza como un usuario nuevo (resetTips en [newUser]).
    @get:Rule val keepTips = KeepTips(allSeen = false)

    // Servicio de traducción de la prueba: motor real, caché en memoria (lo pone y lo devuelve la regla).
    @get:Rule val translations = InMemoryTranslations()

    @Before fun newUser() = app.settings.resetTips()

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
        }
    }

    private fun saveShot(name: String) {
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        shotsDir.mkdirs()
        File(shotsDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * La tarjeta: región viva con el texto DICHO (aparte del que se ve), "Entendido" ≥ 48 dp; a letra normal en una
     * fila; al 200 % botón debajo. Sin anunciar (consejo que vuelve tras una hoja): el mismo texto, sin región viva.
     */
    @Test fun tarjetaSeOyeYSuBotonMide48() {
        var config by mutableStateOf(Triple(false, 1f, text)) // oscuro, letra, texto
        var announce by mutableStateOf(true)
        val spoken = "$text (dicho)"
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
                                Column(Modifier.wrapContentHeight().testTag("shot")) {
                                    TipCard(shown, onDismiss = { dismissed++ }, spoken = spoken, announce = announce)
                                }
                            }
                        }
                    }
                }
            }
            for (dark in listOf(false, true)) for (f in listOf(1f, 2f)) {
                config = Triple(dark, f, text)
                rule.waitForIdle()
                val where = "${if (dark) "oscuro" else "claro"} letra ${(f * 100).toInt()}"
                val message = rule.onNode(hasContentDescription(spoken)).fetchSemanticsNode()
                assertEquals(LiveRegionMode.Polite, message.config[SemanticsProperties.LiveRegion], "$where: región viva")
                val button = rule.onNode(hasText(gotIt) and hasClickAction())
                button.assertHeightIsAtLeast(48.dp)
                val b = button.fetchSemanticsNode().boundsInRoot
                if (f == 1f) assertTrue(b.top < message.boundsInRoot.bottom, "$where: el botón no está al lado")
                else assertTrue(b.top >= message.boundsInRoot.bottom, "$where: el botón no está debajo")
                if (shots) saveShot("tarjeta-${if (dark) "oscuro" else "claro"}${if (f == 2f) "-letra200" else ""}.png")
            }
            announce = false
            rule.waitForIdle()
            val quiet = rule.onNode(hasContentDescription(spoken)).fetchSemanticsNode()
            assertFalse(SemanticsProperties.LiveRegion in quiet.config, "sin anunciar no debe ser región viva")
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
            val nth = p.getInt("p") // índice entre los <p> de la página
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

            // 5. Leer hacia adelante hasta el final del capítulo 1 (no es el último) con gestos reales: las barras se
            //    ocultan y aun así sale "Sigue deslizando…" (Ruling D). Seguir deslizando en el borde pasa al capítulo 2
            //    y eso lo cierra y lo marca.
            // Cerca del final (a una pantalla y media) por script; el último tramo, con el dedo.
            js(s, "var e = document.scrollingElement || document.documentElement; window.scrollTo(0, e.scrollHeight - Math.round(window.innerHeight * 1.5)); 'ok'")
            settle()
            var swipes = 0
            while (js(s, AT_BOTTOM) != "true") {
                assertTrue(swipes++ < 10, "no se llegó al final del capítulo con $swipes gestos")
                swipeUp(s)
                settle()
            }
            assertEquals("/OEBPS/c1.xhtml", js(s, "location.pathname"), "un gesto pasó de capítulo antes de llegar al final")
            waitForTip(tipEdge)
            val next = app.getString(R.string.reader_chapter_next)
            assertFalse(rule.onAllNodes(hasContentDescription(next)).fetchSemanticsNodes().isNotEmpty(), "las barras siguen a la vista")
            screenshot("recorrido-borde")
            swipeUp(s)
            waitFor("en el capítulo 2") { js(s, "location.pathname") == "/OEBPS/c2.xhtml" }
            waitFor("consejo del borde visto") { TipId.CHAPTER_EDGE in app.settings.seenTipsFlow.value }
            waitFor("sin consejo del borde") { !hasTip(tipEdge) }
        }
    }

    /** Libro en español: primero "Este libro se traduce al inglés…"; abrir la hoja de dirección lo cierra. */
    @Test fun libroEnEspanolMuestraDireccion() = runBlocking<Unit> {
        // Sin traducir nada: no hace falta el modelo.
        openReader(ReaderTestBook.importAndOpen(app, dir).also { created += it }) { _ ->
            // Lo que dice TalkBack: el botón con el nombre que TalkBack le da (I1), no "ES → EN".
            val buttonName = app.getString(R.string.reader_direction_desc, app.getString(R.string.language_es), app.getString(R.string.language_en))
            val tipDirection = app.getString(R.string.tip_direction_spoken, app.getString(R.string.language_en), buttonName)
            assertFalse("→" in tipDirection, "el consejo dicho lleva la flecha")
            waitForTip(tipDirection)
            assertFalse(hasTip(tipTap), "dos consejos a la vez")
            screenshot("direccion")

            rule.onNodeWithContentDescription(buttonName).performClick()
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
            // Vuelve el mismo, sin región viva: ya se dijo una vez (spec §4, m1).
            waitForTip(tipTap, announced = false)
            assertFalse(TipId.TAP in app.settings.seenTipsFlow.value, "esconderlo no lo marca visto")
        }
    }

    // ---------- Cómo se usa y Biblioteca vacía ----------

    /** Biblioteca vacía: "Toca «Añadir libro»…", los dos botones de libros gratis (≥ 48 dp); "Cómo se usa" desde ⋮. */
    @Test fun bibliotecaVaciaYComoSeUsa() {
        val emptyHow = app.getString(R.string.library_empty_how, app.getString(R.string.library_add))
        var howTo by mutableStateOf(false)
        ActivityScenario.launch(ComponentActivity::class.java).use { s ->
            s.onActivity { a ->
                a.setContent {
                    LectorTheme {
                        Box(Modifier.testTag("shot")) {
                            if (howTo) {
                                HowToScreen(onBack = { howTo = false }, onResetTips = { app.settings.resetTips() })
                            } else {
                                // Base vacía de prueba: nada de la biblioteca de Juan.
                                LibraryContent(
                                    state = LibraryUiState(loaded = true),
                                    onAdd = {}, onOpen = {}, onDelete = {}, onLanguages = {},
                                    onHowTo = { howTo = true }, onDeveloper = null,
                                    snackbar = SnackbarHostState(),
                                )
                            }
                        }
                    }
                }
            }
            rule.waitForIdle()
            rule.onNodeWithText(emptyHow).assertIsDisplayed()
            if (shots) saveShot("biblioteca-vacia.png")
            val gutenberg = app.getString(R.string.free_books_open_desc, app.getString(R.string.free_books_gutenberg))
            val standard = app.getString(R.string.free_books_open_desc, app.getString(R.string.free_books_standard))
            for (desc in listOf(gutenberg, standard)) {
                rule.onNode(hasContentDescription(desc) and hasClickAction()).assertHeightIsAtLeast(48.dp)
            }

            // ⋮ → "Cómo se usa".
            rule.onNodeWithContentDescription(app.getString(R.string.library_menu)).performClick()
            rule.onNode(hasText(app.getString(R.string.library_menu_howto)) and hasClickAction()).performClick()
            rule.waitForIdle()
            val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
            rule.onNode(hasText(app.getString(R.string.howto_title)) and heading).assertIsDisplayed()
            val sections = listOf(
                app.getString(R.string.howto_add_title), app.getString(R.string.howto_tap_title),
                app.getString(R.string.howto_modes_title, app.getString(R.string.reading_aa)),
                app.getString(R.string.howto_chapters_title), app.getString(R.string.howto_languages_title),
                app.getString(R.string.howto_privacy_title),
            )
            for (title in sections) {
                rule.onNode(hasText(title) and heading).assertExists("falta el título $title")
            }
            if (shots) saveShot("como-se-usa-arriba.png")

            // Abajo: libros gratis y "Volver a ver los consejos".
            rule.onNode(hasText(app.getString(R.string.howto_free_books_title)) and heading).performScrollTo()
            rule.onNode(hasContentDescription(gutenberg) and hasClickAction()).assertHeightIsAtLeast(48.dp)
            val reset = rule.onNode(hasText(app.getString(R.string.howto_reset)) and hasClickAction())
            reset.performScrollTo()
            reset.assertHeightIsAtLeast(48.dp)
            if (shots) saveShot("como-se-usa-abajo.png")
            app.settings.markTipSeen(TipId.TAP) // algo que borrar (KeepTips lo devuelve todo al terminar)
            reset.performClick()
            rule.waitForIdle()
            assertTrue(app.settings.seenTipsFlow.value.isEmpty(), "los consejos no se reiniciaron")
            rule.onNodeWithText(app.getString(R.string.howto_reset_done)).assertIsDisplayed()

            // Atrás vuelve a la Biblioteca.
            rule.onNodeWithContentDescription(app.getString(R.string.languages_back)).performClick()
            rule.waitForIdle()
            rule.onNodeWithText(emptyHow).assertIsDisplayed()
        }
    }

    /** Desde la hoja Aa del Lector: "¿Cómo se usa la app?" abre la capa; atrás vuelve al mismo sitio del libro. */
    @Test fun comoSeUsaDesdeAa() = runBlocking<Unit> {
        // Sin traducir nada: no hace falta el modelo.
        openReader(EnglishBook.importAndOpen(app, dir).also { created += it }) { s ->
            fun locator() = runBlocking(Dispatchers.Main) {
                var nav: EpubNavigatorFragment? = null
                s.onActivity { nav = navigator(it) }
                nav!!.currentLocator.value
            }
            val before = locator()
            val aa = app.getString(R.string.reading_title)
            waitFor("botón Aa") { rule.onAllNodes(hasContentDescription(aa)).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription(aa).performClick()
            val link = rule.onNode(hasText(app.getString(R.string.reading_howto_link)) and hasClickAction())
            waitFor("enlace a Cómo se usa") { rule.onAllNodes(hasText(app.getString(R.string.reading_howto_link))).fetchSemanticsNodes().isNotEmpty() }
            link.performScrollTo()
            link.assertHeightIsAtLeast(48.dp)
            link.performClick()

            val title = hasText(app.getString(R.string.howto_title)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
            waitFor("capa Cómo se usa") { rule.onAllNodes(title).fetchSemanticsNodes().isNotEmpty() }
            assertFalse(hasTip(tipTap), "un consejo con la capa abierta")
            screenshot("como-se-usa-capa")

            Espresso.pressBack()
            waitFor("capa cerrada") { rule.onAllNodes(title).fetchSemanticsNodes().isEmpty() }
            waitFor("el Lector otra vez") { rule.onAllNodes(hasContentDescription(aa)).fetchSemanticsNodes().isNotEmpty() }
            val after = locator()
            assertEquals(before.href, after.href, "cambió el capítulo")
            assertEquals(before.locations.progression, after.locations.progression, "cambió la posición")
        }
    }

    /** Los enlaces de libros gratis: ver la dirección fija con una app de enlaces; desde una Activity, sin tarea nueva. */
    @Test fun intencionDelNavegador() {
        ActivityScenario.launch(ComponentActivity::class.java).use { s ->
            s.onActivity { a ->
                val intent = browserIntent(a, GUTENBERG_URL)
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals(GUTENBERG_URL, intent.dataString)
                assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE), "falta CATEGORY_BROWSABLE")
                assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK, "desde una Activity no hace falta tarea nueva")
            }
        }
        val fromApp = browserIntent(app, STANDARD_EBOOKS_URL)
        assertEquals(STANDARD_EBOOKS_URL, fromApp.dataString)
        assertTrue(fromApp.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0, "fuera de una Activity Android exige tarea nueva")
    }

    // ---------- Ayudas del Lector ----------

    private val tipTap get() = app.getString(R.string.tip_tap)
    private val tipHide get() = app.getString(R.string.tip_hide_modes_spoken, app.getString(R.string.reading_title))
    private val tipEdge get() = app.getString(R.string.tip_chapter_edge, app.getString(R.string.reader_toc))

    /**
     * El consejo que TalkBack dice [text]. [announced]: región viva Polite (primera vez que sale en la sesión) o sin
     * región viva (vuelve tras una hoja: ya se dijo).
     */
    private fun tipMatcher(text: String, announced: Boolean) = hasContentDescription(text) and
        if (announced) SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        else SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)

    private fun tipNode(text: String): SemanticsNodeInteraction = rule.onNode(hasContentDescription(text))

    private fun hasTip(text: String): Boolean = rule.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForTip(text: String, announced: Boolean = true) =
        waitFor("consejo a la vista", 15_000) { rule.onAllNodes(tipMatcher(text, announced)).fetchSemanticsNodes().isNotEmpty() }

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
        waitForFront(s)
        val down = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 60)) {
            val e = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            instrumentation.uiAutomation.injectInputEvent(e, true)
            e.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    /**
     * Gesto real hacia arriba (leer hacia adelante): abajo, movimientos cada ~10 ms en línea recta y arriba, en el centro
     * de la pantalla (como en ChapterSwipeOnDeviceTest).
     */
    private fun swipeUp(s: ActivityScenario<ReaderActivity>) {
        waitForFront(s)
        val m = app.resources.displayMetrics
        val x = m.widthPixels / 2f
        val y0 = m.heightPixels * 0.65f
        val dy = -SWIPE_PX
        val steps = (SWIPE_MS / 10).toInt()
        val down = SystemClock.uptimeMillis()
        inject(down, down, MotionEvent.ACTION_DOWN, x, y0)
        for (i in 1..steps) {
            val t = down + SWIPE_MS * i / steps
            while (SystemClock.uptimeMillis() < t) Thread.sleep(1)
            inject(down, t, MotionEvent.ACTION_MOVE, x, y0 + dy * i / steps)
        }
        inject(down, down + SWIPE_MS, MotionEvent.ACTION_UP, x, y0 + dy)
        instrumentation.waitForIdleSync()
    }

    private fun inject(down: Long, time: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        instrumentation.uiAutomation.injectInputEvent(e, true)
        e.recycle()
    }

    /** Si el Lector no está al frente (pantalla bloqueada, un diálogo), la prueba FALLA: no se salta en silencio. */
    private fun waitForFront(s: ActivityScenario<ReaderActivity>) =
        waitFor("el Lector al frente", 5_000) { var f = false; s.onActivity { f = it.hasWindowFocus() }; f }

    /** Deja que el gesto y los efectos de Compose se asienten (la regla avanza los efectos). */
    private fun settle() {
        Thread.sleep(1_000)
        rule.waitForIdle()
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

        /** ¿La página está abajo del todo? (como ParagraphScripts.edges). */
        const val AT_BOTTOM = "var e = document.scrollingElement || document.documentElement; String(window.scrollY + window.innerHeight >= e.scrollHeight - 2)"

        /** Gesto hacia arriba: recorrido (px) y duración (ms), sin fling largo. */
        const val SWIPE_PX = 600f
        const val SWIPE_MS = 300L

        /** Lo que hay entre el borde de arriba de TipCard y su texto o su botón (márgenes internos, con holgura). */
        const val TIP_INNER_TOP_DP = 8f

        /** El primer <p> cuyo centro cae entre el 40 % y el 60 % del alto visible: su índice entre los <p> y su centro en px CSS. */
        const val MIDDLE_PARAGRAPH = "(function () { var ps = document.querySelectorAll('p'); var h = window.innerHeight;" +
            " for (var k = 0; k < ps.length; k++) { var r = ps[k].getBoundingClientRect(); var cy = r.top + r.height / 2;" +
            " if (cy > h * 0.4 && cy < h * 0.6) return JSON.stringify({p: k, x: r.left + r.width / 2, y: cy}); }" +
            " return null; })()"
    }
}
