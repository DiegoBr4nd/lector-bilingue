package io.github.diegobr4nd.lectorbilingue

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.books.ImportResult
import io.github.diegobr4nd.lectorbilingue.books.db.LectorDatabase
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.data.AndroidEngineProvider
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.PageTheme
import io.github.diegobr4nd.lectorbilingue.data.ReadingMode
import io.github.diegobr4nd.lectorbilingue.data.ReadingSettings
import io.github.diegobr4nd.lectorbilingue.data.TranslationService
import io.github.diegobr4nd.lectorbilingue.data.translationScope
import io.github.diegobr4nd.lectorbilingue.ui.reader.ChapterNoticeBand
import io.github.diegobr4nd.lectorbilingue.ui.reader.NoticeTexts
import io.github.diegobr4nd.lectorbilingue.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Modos Intercalado y Solo traducción en el Lector de verdad (3c-2, Tarea 5): motor y modelo inglés → español REALES
 * (si no está instalado, la prueba se salta), libro inventado en inglés y toques reales inyectados en la pantalla.
 *
 * - La caché de traducciones es una base de Room EN MEMORIA, puesta en lugar de la de la app solo durante la prueba:
 *   nunca se toca la caché de Juan.
 * - Los ajustes de lectura de Juan se guardan al empezar y se devuelven al terminar.
 * - Con `-e shots true` guarda capturas en la caché de la app (`modes_shots`); sin esa opción las borra.
 * - Los registros solo llevan números.
 */
@RunWith(AndroidJUnit4::class)
class ModesOnDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LectorApp
    private val dir = File(app.cacheDir, "modes_test").apply { deleteRecursively(); mkdirs() }
    private val shotsDir = File(app.cacheDir, "modes_shots")
    private val shots = InstrumentationRegistry.getArguments().getString("shots") == "true"
    private val created = mutableListOf<String>()

    /** Los ajustes de Juan: se devuelven tal cual en [cleanUp]. */
    private val saved = app.settings.readingSettings

    // Servicio de traducción de la prueba: motor real, caché en memoria. Reemplaza al de la app (el `by lazy` de
    // LectorApp) hasta [cleanUp]; el Lector lo toma de `app.translations` al crear su ViewModel.
    private val db = Room.inMemoryDatabaseBuilder(app, LectorDatabase::class.java).build()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "motor-prueba-modos") }.asCoroutineDispatcher()
    private val service = TranslationService(AndroidEngineProvider(app, worker), db.translations(), worker = worker, scope = translationScope(worker))
    private val delegate = LectorApp::class.java.getDeclaredField("translations\$delegate").apply { isAccessible = true }
    private val appDelegate = (delegate.get(app) as Lazy<*>).also { original ->
        // Si el servicio de la app ya existía (otra prueba del mismo proceso), se suelta su motor antes de cambiarlo:
        // así no quedan dos motores cargados. Si no existía, no se crea.
        if (original.isInitialized()) (original.value as TranslationService).release()
        delegate.set(app, lazyOf(service))
    }

    @After fun cleanUp() = runBlocking<Unit> {
        // Primero el servicio de la app (pase lo que pase después) y luego los ajustes de Juan.
        try {
            delegate.set(app, appDelegate)
        } finally {
            app.settings.readingSettings = saved
        }
        service.release()
        for (id in created) {
            app.openBooks.close(id)
            app.books.delete(id)
        }
        dir.deleteRecursively()
        if (!shots) shotsDir.deleteRecursively()
        db.close()
        worker.close()
    }

    // ---------- Intercalado ----------

    /** Intercalado a mitad de capítulo: mientras las tarjetas se llenan, lo que se lee no se mueve más de 1 px. */
    @Test fun intercaladoSinSaltos() = runBlocking<Unit> {
        open(ReadingMode.INTERLEAVED) { s ->
            goTo(s, 0.3)
            val anchor = js(s, startRecording())!!
            assertTrue(anchor != "none", "no hay párrafo que empiece en pantalla")
            waitFor("pantalla traducida", FILL_TIMEOUT) { filled(s, "texto") }
            settle()
            val (jump, frames) = stopRecording(s)
            Log.i(TAG, "intercalado: ancla $anchor, salto máximo $jump px en $frames cuadros")
            assertTrue(frames > 10, "no se midió ($frames cuadros)")
            assertTrue(jump <= 1.0, "lo que se lee se movió $jump px")
            // Cada tarjeta, con una traducción de verdad (no la del motor falso).
            val sample = js(s, "document.querySelector('aside.lector-tarjeta[data-lector-estado=\"texto\"]').textContent")!!
            assertTrue(sample.isNotBlank() && !sample.startsWith("T("))
            screenshot("intercalado")
        }
    }

    /**
     * Review Focus 2: ya traducida una pantalla, se vuelve hacia arriba; las tarjetas que se llenan arriba y en
     * pantalla no mueven el párrafo que se está leyendo.
     */
    @Test fun volverArribaNoSaltaElParrafoLeido() = runBlocking<Unit> {
        open(ReadingMode.INTERLEAVED) { s ->
            goTo(s, 0.5)
            waitFor("pantalla traducida", FILL_TIMEOUT) { filled(s, "texto") }
            // Una pantalla y media hacia arriba (como un dedo) y, en el mismo script, empieza a medir.
            val anchor = js(s, "window.scrollBy(0, -Math.round(window.innerHeight * 1.5)); " + startRecording())!!
            assertTrue(anchor != "none", "no hay párrafo que empiece en pantalla")
            waitFor("pantalla de arriba traducida", FILL_TIMEOUT) { filled(s, "texto") }
            settle()
            val (jump, frames) = stopRecording(s)
            Log.i(TAG, "volver arriba: ancla $anchor, salto máximo $jump px en $frames cuadros")
            assertTrue(frames > 10, "no se midió ($frames cuadros)")
            assertTrue(jump <= 1.0, "el párrafo leído se movió $jump px")
        }
    }

    // ---------- Solo traducción ----------

    /**
     * Solo traducción: en espera, el original en gris con "Traduciendo…" debajo; traducido, la traducción con la letra
     * del libro y sin fondo; un toque real muestra el original con la marca "Original"; otro vuelve a la traducción.
     */
    @Test fun soloTraduccionAlternaOriginal() = runBlocking<Unit> {
        open(ReadingMode.TRANSLATION_ONLY) { s ->
            // En espera (los siguientes tardan más que los de arriba): gris AA de tarjeta.css (#6b7280 en claro).
            waitFor("un párrafo en espera", 60_000) { js(s, "String(!!$WAITING)") == "true" }
            assertEquals("Traduciendo…", js(s, "$WAITING.textContent"))
            assertEquals(GREY, js(s, "getComputedStyle($WAITING.previousElementSibling).color"))
            assertEquals(GREY, js(s, "getComputedStyle($WAITING).color"))
            screenshot("solo-en-espera")

            waitFor("pantalla traducida", FILL_TIMEOUT) { filled(s, "solo") }
            settle()
            // Un párrafo traducido en la mitad de la pantalla (lejos de las barras).
            val target = JSONObject(js(s, MIDDLE_SOLO_CARD) ?: error("no hay tarjeta Solo a mitad de pantalla"))
            val i = target.getInt("i")
            val card = "document.querySelector('aside.lector-tarjeta[data-lector-i=\"$i\"]')"
            val original = "$card.previousElementSibling"
            // Con la letra del libro, sin fondo ni línea, y el original oculto.
            assertEquals("rgba(0, 0, 0, 0)", js(s, "getComputedStyle($card).backgroundColor"))
            assertEquals("0px", js(s, "getComputedStyle($card).borderLeftWidth"))
            assertEquals("true", js(s, "String($original.classList.contains('lector-oculto'))"))
            val translation = js(s, "$card.querySelector('.lector-traduccion').textContent")!!
            assertTrue(translation.isNotBlank() && !translation.startsWith("T("))
            screenshot("solo")

            // 1. Toque real en la traducción → el original con la marca "Original" (gris, pequeña) debajo.
            val (cx, cy) = screenPoint(s, target.getDouble("x"), target.getDouble("y"))
            tap(s, cx, cy)
            waitFor("original a la vista") { js(s, "String($card.hasAttribute('data-lector-original'))") == "true" }
            assertEquals("false", js(s, "String($original.classList.contains('lector-oculto'))"))
            assertEquals("none", js(s, "getComputedStyle($card.querySelector('.lector-traduccion')).display"))
            assertEquals("inline-block", js(s, "getComputedStyle($card.querySelector('.lector-marca')).display"))
            assertEquals("Original", js(s, "$card.querySelector('.lector-marca').textContent"))
            assertEquals(GREY, js(s, "getComputedStyle($card.querySelector('.lector-marca')).color"))
            val originalSize = js(s, "getComputedStyle($original).fontSize")!!
            // La marca va justo debajo de su párrafo (no a un margen de párrafo de distancia).
            val gap = js(s, "String($card.querySelector('.lector-marca').getBoundingClientRect().top - $original.getBoundingClientRect().bottom)")!!.toDouble()
            Log.i(TAG, "marca Original: $gap px bajo su párrafo")
            assertTrue(gap in -1.0..6.0, "la marca queda a $gap px de su párrafo")
            screenshot("solo-original")

            // 2. Otro toque, ahora en el original → vuelve la traducción, con el tamaño de letra del original.
            val back = JSONObject(js(s, "var r = $original.getBoundingClientRect(); JSON.stringify({x: r.left + r.width / 2, y: r.top + r.height / 2})")!!)
            val (ox, oy) = screenPoint(s, back.getDouble("x"), back.getDouble("y"))
            tap(s, ox, oy)
            waitFor("traducción otra vez") { js(s, "String($card.hasAttribute('data-lector-original'))") == "false" }
            assertEquals("true", js(s, "String($original.classList.contains('lector-oculto'))"))
            assertEquals(originalSize, js(s, "getComputedStyle($card).fontSize"))
            assertEquals(translation, js(s, "$card.querySelector('.lector-traduccion').textContent"))
        }
    }

    /**
     * Orden de la Tarea 4: un ajuste nuevo llega a la página ANTES de que el ViewModel reponga las tarjetas Solo, así
     * la tarjeta copia el estilo nuevo del original (letra al 150 % → 24 px) y no el viejo (16 px).
     */
    @Test fun cambiarLaLetraEnSoloCopiaElEstiloNuevo() = runBlocking<Unit> {
        open(ReadingMode.TRANSLATION_ONLY) { s ->
            waitFor("pantalla traducida", FILL_TIMEOUT) { filled(s, "solo") }
            settle()
            assertEquals("16px", js(s, "getComputedStyle($FIRST_SOLO).fontSize"))
            js(s, "window.__marca = 7; 'ok'")
            app.settings.updateReadingSettings { it.copy(fontScale = 1.5) }
            // El último párrafo del capítulo (sin tarjeta) dice la letra del libro.
            waitFor("letra del libro al 150 %") { js(s, "getComputedStyle(document.querySelectorAll('p')[69]).fontSize") == "24px" }
            waitFor("tarjetas Solo con la letra nueva") {
                js(s, "var cs = document.querySelectorAll('p + aside.lector-tarjeta[data-lector-estado=\"solo\"]'); String(cs.length > 0 && Array.prototype.every.call(cs, function (c) { return getComputedStyle(c).fontSize === '24px'; }))") == "true"
            }
            assertEquals("7", js(s, "String(window.__marca)"), "la página se recargó")
        }
    }

    // ---------- Cambiar de modo desde la hoja ----------

    /**
     * Desde la hoja "Aa": Tocar → Intercalado → Solo traducción → Tocar sin recargar la página (`window.__marca`
     * sigue) y sin cambiar lo que se lee. Cada modo con ✓, "Elegido" para TalkBack, 48 dp y su ayuda.
     */
    @Test fun cambiarDeModoNoRecarga() = runBlocking<Unit> {
        open(ReadingMode.TAP) { s ->
            goTo(s, 0.4)
            js(s, "window.__marca = 7; 'ok'")
            val reading = js(s, FIRST_START)!!
            openSheet()

            chooseMode("Intercalado", "Cada párrafo con su traducción debajo")
            waitFor("modo intercalado en la página") { js(s, MODE_ATTR) == "intercalado" }
            waitFor("tarjetas intercaladas", FILL_TIMEOUT) { js(s, "String(document.querySelectorAll('aside.lector-tarjeta[data-lector-estado=\"texto\"]').length > 0)") == "true" }
            assertEquals("7", js(s, "String(window.__marca)"), "se recargó al pasar a Intercalado")
            screenshot("hoja-intercalado")

            chooseMode("Solo traducción", "Toca un párrafo para ver el original")
            waitFor("modo solo en la página") { js(s, MODE_ATTR) == "solo" }
            waitFor("tarjetas Solo") { js(s, "String(document.querySelectorAll('aside.lector-tarjeta[data-lector-estado=\"solo\"]').length > 0)") == "true" }
            assertEquals("0", js(s, "String(document.querySelectorAll('aside.lector-tarjeta[data-lector-estado=\"texto\"]').length)"))
            assertEquals("7", js(s, "String(window.__marca)"), "se recargó al pasar a Solo traducción")

            chooseMode("Tocar", "Toca un párrafo para ver su traducción")
            waitFor("modo tocar en la página") { js(s, MODE_ATTR) == "tocar" }
            // Las tarjetas de la guía se van (las traducciones quedan en caché) y no queda ningún original oculto.
            waitFor("sin tarjetas automáticas") { js(s, "String(document.querySelectorAll('aside.lector-tarjeta').length)") == "0" }
            assertEquals("0", js(s, "String(document.querySelectorAll('.lector-oculto').length)"))
            assertEquals("7", js(s, "String(window.__marca)"), "se recargó al volver a Tocar")
            settle()
            assertEquals(reading, js(s, FIRST_START), "cambió lo que se lee")
        }
    }

    /** El modo elegido en la hoja se guarda (otra lectura de los ajustes lo ve) y vale al volver a abrir el Lector. */
    @Test fun elModoSeGuarda() = runBlocking<Unit> {
        val id = prepare(ReadingMode.TAP)
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitFor("página lista") { js(s, PAGE_READY) == "70" }
            openSheet()
            chooseMode("Solo traducción", "Toca un párrafo para ver el original")
            rule.waitForIdle()
            assertEquals(ReadingMode.TRANSLATION_ONLY, app.settings.readingSettings.mode)
            assertEquals(PageTheme.LIGHT, app.settings.readingSettings.theme, "cambió otro ajuste")
        }
        val fresh = AppSettings.of(app).also { it.loadReadingSettings() }
        assertEquals(ReadingMode.TRANSLATION_ONLY, fresh.readingSettings.mode)
        // Al cerrar, el Lector suelta el libro (como al volver a la Biblioteca): se vuelve a abrir como lo haría ella.
        app.openBooks.put(id, app.readium.open(app.books.epubFile(id)).getOrNull()!!, null)
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitFor("página lista") { js(s, PAGE_READY) == "70" }
            // La página nueva recibe el modo al quedar lista (antes de reponer tarjetas).
            waitFor("modo solo al reabrir") { js(s, MODE_ATTR) == "solo" }
        }
    }

    // ---------- Aviso único del capítulo ----------

    /**
     * La banda del aviso: texto + ícono + botón de 48 dp; región viva con lo que oye TalkBack; con letra al 200 % el
     * botón baja debajo del texto. Con `-e shots true`, capturas en claro y oscuro, letra 1 y 2.
     */
    @Test fun avisoDelCapituloSeOyeYSuBotonMide48() {
        var config by mutableStateOf(BandShot(dark = false, fontScale = 1f, texts = MISSING))
        ActivityScenario.launch(ComponentActivity::class.java).use { s ->
            var px = 0
            s.onActivity { a -> px = a.resources.displayMetrics.widthPixels }
            s.onActivity { a ->
                a.setContent {
                    val c = config
                    CompositionLocalProvider(LocalDensity provides Density(px / 360f, fontScale = c.fontScale)) {
                        LectorTheme(darkTheme = c.dark) {
                            Box(Modifier.size(360.dp, 400.dp), contentAlignment = Alignment.TopCenter) {
                                Column(Modifier.wrapContentHeight().testTag("shot")) { ChapterNoticeBand(c.texts, onAction = { clicks++ }) }
                            }
                        }
                    }
                }
            }
            for (texts in listOf(MISSING, FAILED)) for (dark in listOf(false, true)) for (f in listOf(1f, 2f)) {
                config = BandShot(dark, f, texts)
                rule.waitForIdle()
                val where = "${texts.action} ${if (dark) "oscuro" else "claro"} letra ${(f * 100).toInt()}"
                val message = rule.onNode(hasContentDescription(texts.spoken)).fetchSemanticsNode()
                assertEquals(LiveRegionMode.Polite, message.config[SemanticsProperties.LiveRegion], "$where: región viva")
                val button = rule.onNode(hasText(texts.action!!) and hasClickAction())
                button.assertHeightIsAtLeast(48.dp)
                val b = button.fetchSemanticsNode().boundsInRoot
                if (f == 2f) assertTrue(b.top >= message.boundsInRoot.bottom - 1, "$where: el botón no bajó")
                else assertTrue(b.top < message.boundsInRoot.bottom, "$where: el botón no está al lado")
                if (shots) {
                    val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
                    shotsDir.mkdirs()
                    val name = "aviso-${if (texts.error) "fallo" else "falta-idioma"}-${if (dark) "oscuro" else "claro"}${if (f == 2f) "-letra200" else ""}.png"
                    File(shotsDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            val before = clicks
            rule.onNode(hasText(FAILED.action!!) and hasClickAction()).performClick()
            assertEquals(before + 1, clicks)
        }
    }

    private var clicks = 0

    private data class BandShot(val dark: Boolean, val fontScale: Float, val texts: NoticeTexts)

    // ---------- Ayudas ----------

    private suspend fun enEsInstalled(): Boolean {
        withTimeout(10_000) { app.hub.loaded.first { it } }
        return app.hub.pairs.value.any { pair -> pair.pair == "en-es" && pair.rows.any { it.installed } }
    }

    /** Ajustes de la prueba (tema claro: los colores medidos son los de claro) y el libro inventado, ya abierto. */
    private suspend fun prepare(mode: ReadingMode): String {
        assumeTrue("falta el modelo inglés → español en el teléfono", enEsInstalled())
        app.settings.readingSettings = ReadingSettings(theme = PageTheme.LIGHT, mode = mode)
        return EnglishBook.importAndOpen(app, dir).also { created += it }
    }

    private suspend fun open(mode: ReadingMode, block: suspend (ActivityScenario<ReaderActivity>) -> Unit) {
        val id = prepare(mode)
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitFor("página lista") { js(s, PAGE_READY) == "70" }
            waitFor("modo en la página") { js(s, MODE_ATTR) == ModeAttr.getValue(mode) }
            block(s)
        }
    }

    /** A [progression] del capítulo 1 y quieta. */
    private fun goTo(s: ActivityScenario<ReaderActivity>, progression: Double) {
        val publication = app.openBooks.get(created.last())!!
        val target = publication.locatorFromLink(publication.readingOrder[0])!!.copyWithLocations(progression = progression)
        s.onActivity { navigator(it)!!.go(target) }
        waitFor("a $progression del capítulo") { (js(s, PROGRESSION)?.toDoubleOrNull() ?: 0.0) > progression - 0.1 }
        settle()
    }

    private fun openSheet() {
        waitFor("botón Aa") { rule.onAllNodes(hasContentDescription("Ajustes de lectura")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Ajustes de lectura").performClick()
        waitFor("hoja abierta") { rule.onAllNodes(hasText("Modo de lectura")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Toca el modo en la hoja y comprueba ✓ (seleccionado), "Elegido" con su ayuda para TalkBack, 48 dp y la ayuda. */
    private fun chooseMode(name: String, help: String) {
        val option = rule.onNode(hasContentDescription("Modo de lectura $name", substring = true) and hasClickAction())
        option.performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.waitForIdle()
        // Un solo canal para la ayuda (TalkBack la oía dos veces): la opción dice "Modo de lectura X, elegido" y luego
        // la región viva, la ayuda.
        rule.onNode(
            hasContentDescription("Modo de lectura $name") and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Elegido") and
                SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        ).assertExists()
        assertTrue(rule.onAllNodes(hasContentDescription(help, substring = true)).fetchSemanticsNodes().isEmpty(), "la ayuda va en la descripción")
        rule.onNode(hasText(help) and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertExists()
    }

    /** ¿Cada párrafo en pantalla tiene ya su tarjeta en el estado [state] ("texto" o "solo")? */
    private fun filled(s: ActivityScenario<ReaderActivity>, state: String): Boolean {
        val r = js(s, filledScript(state))?.split('/') ?: return false
        return r.size == 2 && r[0].toInt() > 0 && r[0] == r[1]
    }

    private fun stopRecording(s: ActivityScenario<ReaderActivity>): Pair<Double, Int> {
        val r = JSONObject(js(s, "window.__grabando = false; JSON.stringify({salto: window.__salto, cuadros: window.__cuadros})")!!)
        return r.getDouble("salto") to r.getInt("cuadros")
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

    private fun visibleWebView(v: View): WebView? {
        if (v is WebView && v.isShown && v.getGlobalVisibleRect(android.graphics.Rect())) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) visibleWebView(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Deja que la página y los efectos de Compose se asienten. */
    private fun settle() {
        Thread.sleep(800)
        rule.waitForIdle()
        Thread.sleep(400)
        rule.waitForIdle()
    }

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
        const val TAG = "ModosTest"
        const val FILL_TIMEOUT = 150_000L
        const val GREY = "rgb(107, 114, 128)" // --lector-espera en claro (#6b7280)
        const val PAGE_READY = "document.readyState === 'complete' && !!window.readium && document.querySelectorAll('p').length"
        const val MODE_ATTR = "document.documentElement.getAttribute('data-lector-modo')"
        const val PROGRESSION = "String(window.scrollY / (document.scrollingElement || document.documentElement).scrollHeight)"
        val ModeAttr = mapOf(ReadingMode.TAP to "tocar", ReadingMode.INTERLEAVED to "intercalado", ReadingMode.TRANSLATION_ONLY to "solo")
        val MISSING = NoticeTexts("Falta el idioma inglés → español (45 MB)", "Falta el idioma inglés a español, 45 megabytes. Descargar", "Descargar", error = false)
        val FAILED = NoticeTexts("No se pudo preparar el traductor", "No se pudo preparar el traductor", "Reintentar", error = true)
        const val WAITING = "document.querySelector('aside.lector-tarjeta[data-lector-estado=\"espera\"]')"
        /** La primera tarjeta Solo de un `<p>` (la del h1 copia su letra de título, 2 em). */
        const val FIRST_SOLO = "document.querySelector('p + aside.lector-tarjeta[data-lector-estado=\"solo\"]')"

        /** Hojas del capítulo (como ParagraphScripts: sin las de nuestras tarjetas ni las que contienen otra). */
        const val LEAVES = "var sel = 'p, li, blockquote, h1, h2, h3, h4, h5, h6, dd'; var all = Array.prototype.filter.call(" +
            "document.querySelectorAll(sel), function (e) { return !e.closest('.lector-tarjeta') && !e.querySelector(sel); });"

        /** Índice del primer párrafo que EMPIEZA en pantalla (lo que se lee; ancla de los scripts). */
        const val FIRST_START = "(function () { $LEAVES var h = window.innerHeight; for (var k = 0; k < all.length; k++) {" +
            " var r = all[k].getBoundingClientRect(); if (r.height > 0 && r.top >= 0 && r.top < h) return String(k); } return 'none'; })()"

        /** Fija el párrafo que se lee y mide en cada cuadro cuánto se aleja de su lugar (máximo y número de cuadros). */
        fun startRecording() = "(function () { $LEAVES var h = window.innerHeight, b = null, k = 0;" +
            " for (; k < all.length; k++) { var r = all[k].getBoundingClientRect(); if (r.height > 0 && r.top >= 0 && r.top < h) { b = all[k]; break; } }" +
            " if (!b) return 'none'; window.__b = b; window.__top0 = b.getBoundingClientRect().top; window.__salto = 0;" +
            " window.__cuadros = 0; window.__grabando = true;" +
            " function f() { if (!window.__grabando) return; var d = Math.abs(window.__b.getBoundingClientRect().top - window.__top0);" +
            " if (d > window.__salto) window.__salto = d; window.__cuadros++; requestAnimationFrame(f); } requestAnimationFrame(f);" +
            " return String(k); })()"

        /** "en pantalla/con tarjeta en [state]": un original oculto (Solo) se mide por su tarjeta. */
        fun filledScript(state: String) = "(function () { $LEAVES var h = window.innerHeight, n = 0, ok = 0;" +
            " for (var k = 0; k < all.length; k++) { var e = all[k], c = e.nextElementSibling;" +
            " var isCard = !!c && c.matches('aside.lector-tarjeta');" +
            " var r = (e.classList.contains('lector-oculto') && isCard ? c : e).getBoundingClientRect();" +
            " if (r.height <= 0 || r.bottom <= 0) continue; if (r.top >= h) break; n++;" +
            " if (isCard && c.dataset.lectorEstado === '$state') ok++; } return n + '/' + ok; })()"

        /** Una tarjeta Solo cuyo centro cae entre el 40 % y el 60 % del alto visible: su índice y su centro (px CSS). */
        const val MIDDLE_SOLO_CARD = "(function () { var cs = document.querySelectorAll('aside.lector-tarjeta[data-lector-estado=\"solo\"]');" +
            " var h = window.innerHeight; for (var k = 0; k < cs.length; k++) { var r = cs[k].getBoundingClientRect(); var cy = r.top + r.height / 2;" +
            " if (cy > h * 0.4 && cy < h * 0.6) return JSON.stringify({i: parseInt(cs[k].dataset.lectorI, 10), x: r.left + r.width / 2, y: cy}); }" +
            " return null; })()"
    }
}

/** Libro inventado en INGLÉS (la dirección automática es inglés → español): 3 capítulos de 70 párrafos. */
internal object EnglishBook {
    suspend fun importAndOpen(app: LectorApp, dir: File): String {
        val epub = TestEpub.build(dir, "modes.epub") {
            title = "Invented test book"
            replace("OEBPS/content.opf", opf().toByteArray())
            replace("OEBPS/nav.xhtml", nav().toByteArray())
            replace("OEBPS/c1.xhtml", chapter("Chapter one").toByteArray())
            entry("OEBPS/c2.xhtml", chapter("Chapter two").toByteArray())
            entry("OEBPS/c3.xhtml", chapter("Chapter three").toByteArray())
        }
        val id = (app.books.import({ epub.inputStream() }, "modes.epub") as ImportResult.Ok).bookId
        app.openBooks.put(id, app.readium.open(app.books.epubFile(id)).getOrNull()!!, null)
        return id
    }

    private val sentences = listOf(
        "The keeper climbed the lighthouse stairs and lit the lamp before night fell.",
        "Far below, the boats came home one by one, their lanterns swaying on the dark water.",
        "Her brother counted the nets on the pier and laughed when the wind stole his hat.",
        "Nobody in the village remembered a winter as long as that one.",
        "The baker left warm bread by the door of every house that had lost a sailor.",
        "At dawn the gulls returned, and the children ran to the beach to look for shells.",
        "An old map hung in the school, with islands that no one had ever visited.",
    )

    /** Párrafo [i] (1…70): dos frases inventadas, distintas en cada párrafo. */
    fun paragraph(i: Int) = "Paragraph $i. ${sentences[i % sentences.size]} ${sentences[(i * 3 + 1) % sentences.size]}"

    private fun opf() = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">urn:uuid:00000000-0000-0000-0000-000000000032</dc:identifier>
    <dc:title>Invented test book</dc:title>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
    <item id="c3" href="c3.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine>
</package>"""

    private fun nav() = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>""" +
        """<li><a href="c1.xhtml">Chapter one</a></li><li><a href="c2.xhtml">Chapter two</a></li><li><a href="c3.xhtml">Chapter three</a></li>""" +
        """</ol></nav></body></html>"""

    private fun chapter(title: String) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en"><head><title>$title</title></head><body><h1>$title</h1>""")
        for (i in 1..70) append("<p>${paragraph(i)}</p>")
        append("</body></html>")
    }
}
