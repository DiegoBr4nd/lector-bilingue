package io.github.diegobr4nd.lectorbilingue

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.ui.reader.PageEdges
import io.github.diegobr4nd.lectorbilingue.ui.reader.ParagraphBridge
import io.github.diegobr4nd.lectorbilingue.ui.reader.ReaderActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deslizar para leer en el modo desplazamiento de Readium (fix/cambio-de-capitulo): un gesto casi vertical a mitad de
 * capítulo nunca cambia de capítulo. Gestos REALES inyectados en la pantalla (no `input swipe`) sobre el libro inventado
 * de 3 capítulos. Los registros solo llevan números y hrefs del libro inventado.
 */
@RunWith(AndroidJUnit4::class)
class ChapterSwipeOnDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LectorApp
    private val dir = File(app.cacheDir, "swipe_test").apply { deleteRecursively(); mkdirs() }
    private val created = mutableListOf<String>()

    @After fun cleanUp() = runBlocking<Unit> {
        for (id in created) {
            app.openBooks.close(id)
            app.books.delete(id)
        }
        dir.deleteRecursively()
    }

    /**
     * Un gesto: recorrido vertical y horizontal en px del aparato (dy negativo = dedo hacia arriba = leer hacia
     * adelante) y duración.
     */
    private data class Gesture(val name: String, val dy: Int, val dx: Int, val ms: Long)

    /** Gesto con un desvío de [degrees] respecto de la vertical: a la izquierda si sube, a la derecha si baja. */
    private fun angled(name: String, dy: Int, degrees: Double, ms: Long): Gesture {
        val dx = (abs(dy) * tan(Math.toRadians(degrees))).roundToInt()
        return Gesture(name, dy, if (dy < 0) -dx else dx, ms)
    }

    private val vc = ViewConfiguration.get(app)
    private val density = app.resources.displayMetrics.density

    /**
     * El gesto del fallo, calculado para CUALQUIER teléfono (no solo el Pixel 7): Readium cambiaba de capítulo si el
     * dedo se desviaba más que `scaledPagingTouchSlop` en horizontal y recorría menos de 200 px en vertical. Aquí: 1,5
     * veces ese desvío y como mucho el 70 % de esos 200 px.
     */
    private fun diagonal(name: String, down: Boolean): Gesture {
        val dy = min(150, (0.7 * READIUM_MAX_DY).toInt())
        val dx = (1.5 * vc.scaledPagingTouchSlop).roundToInt()
        return if (down) Gesture(name, dy, dx, 80) else Gesture(name, -dy, -dx, 80)
    }

    /**
     * Evidencia (solo con `-e evidencia true`): cada gesto desde la mitad del capítulo 2, y adónde fue a parar. El
     * desvío va hacia la izquierda si el dedo sube y hacia la derecha si baja (el pulgar derecho al leer).
     */
    @Test fun evidenciaDeGestos() = runBlocking<Unit> {
        assumeTrue("solo para recoger evidencia", InstrumentationRegistry.getArguments().getString("evidencia") == "true")
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        val gestures = listOf(
            angled("largo-recto", -900, 0.0, 300),
            angled("corto-recto", -150, 0.0, 80),
            angled("corto-5", -150, 5.0, 80),
            angled("corto-10", -150, 10.0, 80),
            angled("corto-15", -150, 15.0, 80),
            angled("corto-20", -150, 20.0, 80),
            angled("medio-15", -190, 15.0, 80),
            angled("largo-20", -900, 20.0, 300),
            angled("fling-10", -600, 10.0, 50),
            angled("fling-corto-20", -180, 20.0, 40),
            angled("abajo-corto-20", 150, 20.0, 80),
            angled("abajo-largo-20", 900, 20.0, 300),
        ) + (if (InstrumentationRegistry.getArguments().getString("barrido") == "true")
            listOf(100, 120, 140, 160, 170, 180, 190, 200, 220).map { angled("dy$it-20", -it, 20.0, 80) } +
                listOf(8.0, 12.0, 14.0, 16.0, 18.0, 25.0, 30.0, 40.0).map { angled("dy150-$it", -150, it, 80) } +
                listOf(80L, 120L, 200L, 400L).map { angled("dy150-20-${it}ms", -150, 20.0, it) }
        else emptyList())
        val vc = ViewConfiguration.get(app)
        Log.i(TAG, "densidad=${app.resources.displayMetrics.density} pagingSlop=${vc.scaledPagingTouchSlop} slop=${vc.scaledTouchSlop}")
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitForText("Capítulo uno")
            for (round in 1..(if (InstrumentationRegistry.getArguments().getString("barrido") == "true") 1 else 3)) for (g in gestures) {
                goToChapter2(s)
                val before = position(s)
                val scrollX = webScrollX(s)
                swipe(s, g)
                settle()
                val after = position(s)
                Log.i(TAG, "r$round ${g.name} dy=${g.dy} dx=${g.dx} ms=${g.ms} scrollX=$scrollX antes=$before despues=$after")
            }
            // Al final del capítulo: ¿qué hace Readium con un gesto recto y con uno diagonal corto?
            for (g in listOf(angled("final-largo-recto", -900, 0.0, 300), angled("final-corto-20", -150, 20.0, 80))) {
                goToChapter2(s, 1.0)
                val before = position(s)
                swipe(s, g)
                settle()
                Log.i(TAG, "${g.name} antes=$before despues=${position(s)}")
            }
            // Al principio del capítulo: gesto recto hacia abajo.
            goToChapter2(s, 0.0)
            val before = position(s)
            swipe(s, angled("inicio-largo-recto", 900, 0.0, 300))
            settle()
            Log.i(TAG, "inicio-largo-recto antes=$before despues=${position(s)}")
        }
    }

    /** El fallo de Juan: un gesto corto y algo diagonal a mitad de capítulo saltaba al capítulo vecino. */
    @Test fun gestoCasiVerticalAMitadDeCapituloNoCambiaDeCapitulo() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitForText("Capítulo uno")
            for (g in listOf(diagonal("arriba", down = false), diagonal("abajo", down = true))) {
                goToChapter2(s)
                swipe(s, g)
                settle()
                assertEquals("c2.xhtml", position(s).first.substringAfterLast('/'), "el gesto ${g.name} ($g) cambió de capítulo")
            }
        }
    }

    /** Un fling desde la mitad que llega al final del capítulo se queda en él: los bordes cuentan al EMPEZAR el gesto. */
    @Test fun flingQueLlegaAlFinalNoPasaDeCapitulo() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitForText("Capítulo uno")
            // Desde el 60 % (más de dos pantallas por encima del final): desde el 40 % este fling se quedaba a ~21 px
            // CSS del final (scrollY 5477 de 5498 en el Pixel 7), sin llegar al borde.
            goToChapter2(s, 0.6)
            swipe(s, Gesture("fling", -600, 0, 50))
            settle()
            assertEquals(true, edges(s)?.atBottom, "el fling no llegó al final: ${position(s)}")
            assertEquals("c2.xhtml", position(s).first.substringAfterLast('/'))
        }
    }

    /** En el borde, un tirón de ~40 dp no pasa de capítulo (umbral de 48 dp) y uno de ~60 dp sí. */
    @Test fun tironEnElBordeRespetaElUmbral() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitForText("Capítulo uno")
            goToChapter2(s, 1.0)
            swipe(s, Gesture("40dp", -(40 * density).roundToInt(), 0, 200))
            settle()
            assertEquals("c2.xhtml", position(s).first.substringAfterLast('/'), "40 dp ya pasó de capítulo")
            swipe(s, Gesture("60dp", -(60 * density).roundToInt(), 0, 200))
            rule.waitUntil("capítulo 3 con 60 dp", 5_000) { position(s).first.endsWith("c3.xhtml") }
        }
    }

    /** Al final del capítulo, seguir deslizando hacia arriba (recto) pasa al principio del siguiente. */
    @Test fun alFinalDelCapituloSeguirDeslizandoPasaAlSiguiente() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitForText("Capítulo uno")
            goToChapter2(s, 1.0)
            swipe(s, Gesture("final", -600, 0, 300))
            rule.waitUntil("capítulo 3", 5_000) { position(s).first.endsWith("c3.xhtml") }
            assertTrue((position(s).second ?: 1.0) < 0.05, "no empezó por el principio: ${position(s)}")
        }
    }

    /** Al principio del capítulo, deslizar hacia abajo (recto) vuelve al FINAL del anterior (se cruzó el borde de verdad). */
    @Test fun alPrincipioDelCapituloDeslizarHaciaAbajoVuelveAlFinalDelAnterior() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitForText("Capítulo uno")
            goToChapter2(s, 0.0)
            swipe(s, Gesture("inicio", 600, 0, 300))
            rule.waitUntil("capítulo 1", 5_000) { position(s).first.endsWith("c1.xhtml") }
            rule.waitUntil("final del capítulo 1", 5_000) { edges(s)?.atBottom == true }
        }
    }

    /** Un gesto largo a mitad de capítulo solo desplaza: ni cambia de capítulo ni salta. */
    @Test fun gestoLargoAMitadDeCapituloSoloDesplaza() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitForText("Capítulo uno")
            goToChapter2(s)
            swipe(s, Gesture("largo", -900, 0, 300))
            settle()
            val (href, progression) = position(s)
            assertEquals("c2.xhtml", href.substringAfterLast('/'))
            assertTrue(progression!! > 0.42, "no avanzó: $progression")
        }
    }

    /** Toque REAL en "Capítulo siguiente" desde el principio del capítulo 2: capítulo 3, al inicio. */
    @Test fun botonSiguienteLlevaAlInicioDelCapituloSiguiente() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitForText("Capítulo uno")
            goToChapter(s, 1)
            tapButton(s, "Capítulo siguiente")
            rule.waitUntil("capítulo 3", 5_000) { position(s).first.endsWith("c3.xhtml") }
            settle()
            assertTrue((position(s).second ?: 1.0) < 0.05, "no empezó por el principio: ${position(s)}")
        }
    }

    /** "Capítulo anterior" desde el capítulo 2 lleva al principio del 1; en los extremos el botón correspondiente está apagado. */
    @Test fun botonesApagadosEnLosExtremosYAnteriorVaAlInicio() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitForText("Capítulo uno")
            goToChapter(s, 2)
            rule.waitUntil("siguiente apagado en el último", 5_000) { enabledOf("Capítulo siguiente") == false }
            rule.onNodeWithContentDescription("Capítulo siguiente").assertIsNotEnabled()
            rule.onNodeWithContentDescription("Capítulo anterior").assertIsEnabled()
            goToChapter(s, 1)
            rule.waitUntil("los dos encendidos en el 2", 5_000) {
                enabledOf("Capítulo siguiente") == true && enabledOf("Capítulo anterior") == true
            }
            tapButton(s, "Capítulo anterior")
            rule.waitUntil("capítulo 1", 5_000) { position(s).first.endsWith("c1.xhtml") }
            settle()
            assertTrue((position(s).second ?: 1.0) < 0.05, "no empezó por el principio: ${position(s)}")
            rule.waitUntil("anterior apagado en el primero", 5_000) { enabledOf("Capítulo anterior") == false }
            rule.onNodeWithContentDescription("Capítulo anterior").assertIsNotEnabled()
        }
    }

    // ---------- Ayudas ----------

    /** Abre el capítulo [index] (0 = primero) desde su principio y espera a que sea el actual. */
    private fun goToChapter(s: ActivityScenario<ReaderActivity>, index: Int) {
        val publication = app.openBooks.get(created.last())!!
        val target = publication.locatorFromLink(publication.readingOrder[index])!!
        s.onActivity { navigator(it)!!.go(target) }
        rule.waitUntil("capítulo ${index + 1}", 10_000) { position(s).first.endsWith("c${index + 1}.xhtml") }
        Thread.sleep(800)
        rule.waitForIdle()
    }

    private fun enabledOf(description: String): Boolean? {
        val nodes = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(description)).fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        return !nodes[0].config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
    }

    /** Toque real (MotionEvent inyectado) en el centro del botón con esa descripción. */
    private fun tapButton(s: ActivityScenario<ReaderActivity>, description: String) {
        rule.waitUntil("el Lector al frente", 5_000) { var f = false; s.onActivity { f = it.hasWindowFocus() }; f }
        rule.waitUntil("botón $description", 5_000) { enabledOf(description) == true }
        val bounds = rule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2)
        s.onActivity { it.window.decorView.getLocationOnScreen(origin) }
        val x = origin[0] + bounds.center.x
        val y = origin[1] + bounds.center.y
        val down = SystemClock.uptimeMillis()
        inject(down, down, MotionEvent.ACTION_DOWN, x, y)
        inject(down, down + 60, MotionEvent.ACTION_UP, x, y)
        instrumentation.waitForIdleSync()
    }

    /**
     * Abre el capítulo 2 en [progression] y espera a que la página esté en su sitio: 1.0 = abajo del todo, 0.0 =
     * arriba del todo, otro valor = a mitad (lejos de los dos bordes).
     */
    private fun goToChapter2(s: ActivityScenario<ReaderActivity>, progression: Double = 0.4) {
        val publication = app.openBooks.get(created.last())!!
        val target = publication.locatorFromLink(publication.readingOrder[1])!!.copyWithLocations(progression = progression)
        s.onActivity { navigator(it)!!.go(target) }
        rule.waitUntil("capítulo 2 en $progression", 10_000) {
            val (href, p) = position(s)
            val e = edges(s)
            href.endsWith("c2.xhtml") && e != null && when (progression) {
                1.0 -> e.atBottom
                0.0 -> e.atTop
                else -> p != null && abs(p - progression) < 0.15 && !e.atTop && !e.atBottom
            }
        }
        Thread.sleep(800)
        // En la prueba, los efectos de Compose (la pantalla sabe en qué capítulo está) avanzan con la regla.
        rule.waitForIdle()
    }

    /** Bordes de la página visible, con el mismo script que usa el Lector. */
    private fun edges(s: ActivityScenario<ReaderActivity>): PageEdges? {
        var nav: EpubNavigatorFragment? = null
        s.onActivity { nav = navigator(it) }
        return runBlocking { ParagraphBridge { nav }.edges() }
    }

    /** Desplazamiento horizontal del WebView visible (en modo desplazamiento debería ser siempre 0). */
    private fun webScrollX(s: ActivityScenario<ReaderActivity>): Int {
        var x = -1
        s.onActivity { a -> visibleWebView(navigator(a)!!.requireView())?.let { x = it.scrollX } }
        return x
    }

    private fun visibleWebView(v: android.view.View): android.webkit.WebView? {
        if (v is android.webkit.WebView && v.isShown && v.getGlobalVisibleRect(android.graphics.Rect())) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) visibleWebView(v.getChildAt(i))?.let { return it }
        return null
    }

    /**
     * Deja que el gesto termine de surtir efecto. En la prueba, los efectos de Compose (como el paso de capítulo de
     * ReaderScreen) solo avanzan con la regla: un Thread.sleep solo no basta.
     */
    private fun settle() {
        Thread.sleep(1_000)
        rule.waitForIdle()
        Thread.sleep(1_000)
        rule.waitForIdle()
    }

    /** href y progresión dentro del capítulo (números y hrefs del libro inventado, nunca texto). */
    private fun position(s: ActivityScenario<ReaderActivity>): Pair<String, Double?> {
        var result = "" to (null as Double?)
        s.onActivity { a ->
            val locator = navigator(a)!!.currentLocator.value
            result = locator.href.toString() to locator.locations.progression
        }
        return result
    }

    /** Gesto real: abajo, movimientos cada ~10 ms en línea recta y arriba, en el centro de la pantalla. */
    private fun swipe(s: ActivityScenario<ReaderActivity>, g: Gesture) {
        // Si el Lector no está al frente (pantalla bloqueada, un diálogo), la prueba FALLA: no se salta en silencio.
        rule.waitUntil("el Lector al frente", 5_000) { var f = false; s.onActivity { f = it.hasWindowFocus() }; f }
        val m = app.resources.displayMetrics
        val x0 = m.widthPixels / 2f
        val y0 = if (g.dy < 0) m.heightPixels * 0.65f else m.heightPixels * 0.35f
        val dx = g.dx
        val steps = (g.ms / 10).toInt().coerceAtLeast(2)
        val down = SystemClock.uptimeMillis()
        inject(down, down, MotionEvent.ACTION_DOWN, x0, y0)
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            val t = down + g.ms * i / steps
            while (SystemClock.uptimeMillis() < t) Thread.sleep(1)
            inject(down, t, MotionEvent.ACTION_MOVE, x0 + dx * f, y0 + g.dy * f)
        }
        inject(down, down + g.ms, MotionEvent.ACTION_UP, x0 + dx, y0 + g.dy)
        instrumentation.waitForIdleSync()
    }

    private fun inject(down: Long, time: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        instrumentation.uiAutomation.injectInputEvent(e, true)
        e.recycle()
    }

    private fun navigator(a: ReaderActivity): EpubNavigatorFragment? =
        a.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()

    private fun waitForText(text: String) =
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private companion object {
        const val TAG = "ChapterSwipe"

        /** Recorrido vertical por debajo del cual Readium 3.4.0 (R2WebView.onTouchEvent) pasaba de capítulo. */
        const val READIUM_MAX_DY = 200
    }
}
