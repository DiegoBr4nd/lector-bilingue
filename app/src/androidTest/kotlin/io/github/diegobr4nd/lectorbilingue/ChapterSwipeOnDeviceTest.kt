package io.github.diegobr4nd.lectorbilingue

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    /** Un gesto: recorrido vertical en px (negativo = dedo hacia arriba = leer hacia adelante), desvío y duración. */
    private data class Gesture(val name: String, val dy: Int, val degrees: Double, val ms: Long)

    /**
     * Evidencia (solo con `-e evidencia true`): cada gesto desde la mitad del capítulo 2, y adónde fue a parar. El
     * desvío va hacia la izquierda si el dedo sube y hacia la derecha si baja (el pulgar derecho al leer).
     */
    @Test fun evidenciaDeGestos() = runBlocking<Unit> {
        assumeTrue("solo para recoger evidencia", InstrumentationRegistry.getArguments().getString("evidencia") == "true")
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        val gestures = listOf(
            Gesture("largo-recto", -900, 0.0, 300),
            Gesture("corto-recto", -150, 0.0, 80),
            Gesture("corto-5", -150, 5.0, 80),
            Gesture("corto-10", -150, 10.0, 80),
            Gesture("corto-15", -150, 15.0, 80),
            Gesture("corto-20", -150, 20.0, 80),
            Gesture("medio-15", -190, 15.0, 80),
            Gesture("largo-20", -900, 20.0, 300),
            Gesture("fling-10", -600, 10.0, 50),
            Gesture("fling-corto-20", -180, 20.0, 40),
            Gesture("abajo-corto-20", 150, 20.0, 80),
            Gesture("abajo-largo-20", 900, 20.0, 300),
        ) + (if (InstrumentationRegistry.getArguments().getString("barrido") == "true")
            listOf(100, 120, 140, 160, 170, 180, 190, 200, 220).map { Gesture("dy$it-20", -it, 20.0, 80) } +
                listOf(8.0, 12.0, 14.0, 16.0, 18.0, 25.0, 30.0, 40.0).map { Gesture("dy150-$it", -150, it, 80) } +
                listOf(80L, 120L, 200L, 400L).map { Gesture("dy150-20-${it}ms", -150, 20.0, it) }
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
                Log.i(TAG, "r$round ${g.name} dy=${g.dy} grados=${g.degrees} ms=${g.ms} scrollX=$scrollX antes=$before despues=$after")
            }
            // Al final del capítulo: ¿qué hace Readium con un gesto recto y con uno diagonal corto?
            for (g in listOf(Gesture("final-largo-recto", -900, 0.0, 300), Gesture("final-corto-20", -150, 20.0, 80))) {
                goToChapter2(s, 1.0)
                val before = position(s)
                swipe(s, g)
                settle()
                Log.i(TAG, "${g.name} antes=$before despues=${position(s)}")
            }
            // Al principio del capítulo: gesto recto hacia abajo.
            goToChapter2(s, 0.0)
            val before = position(s)
            swipe(s, Gesture("inicio-largo-recto", 900, 0.0, 300))
            settle()
            Log.i(TAG, "inicio-largo-recto antes=$before despues=${position(s)}")
        }
    }

    /** El fallo de Juan: un gesto corto y algo diagonal a mitad de capítulo saltaba al capítulo vecino. */
    @Test fun gestoCasiVerticalAMitadDeCapituloNoCambiaDeCapitulo() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitForText("Capítulo uno")
            for (g in listOf(Gesture("arriba", -150, 20.0, 80), Gesture("abajo", 150, 20.0, 80))) {
                goToChapter2(s)
                swipe(s, g)
                settle()
                assertEquals("c2.xhtml", position(s).first.substringAfterLast('/'), "el gesto ${g.name} cambió de capítulo")
            }
        }
    }

    /** Al final del capítulo, seguir deslizando hacia arriba (recto) pasa al principio del siguiente. */
    @Test fun alFinalDelCapituloSeguirDeslizandoPasaAlSiguiente() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitForText("Capítulo uno")
            goToChapter2(s, 1.0)
            swipe(s, Gesture("final", -600, 0.0, 300))
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
            swipe(s, Gesture("inicio", 600, 0.0, 300))
            rule.waitUntil("capítulo 1", 5_000) { position(s).first.endsWith("c1.xhtml") }
            rule.waitUntil("final del capítulo 1", 5_000) { (position(s).second ?: 0.0) > 0.8 }
        }
    }

    /** Un gesto largo a mitad de capítulo solo desplaza: ni cambia de capítulo ni salta. */
    @Test fun gestoLargoAMitadDeCapituloSoloDesplaza() = runBlocking<Unit> {
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitForText("Capítulo uno")
            goToChapter2(s)
            swipe(s, Gesture("largo", -900, 0.0, 300))
            settle()
            val (href, progression) = position(s)
            assertEquals("c2.xhtml", href.substringAfterLast('/'))
            assertTrue(progression!! > 0.42, "no avanzó: $progression")
        }
    }

    // ---------- Ayudas ----------

    /** Abre el capítulo 2 a un 40 % (lejos del principio y del final) y espera a que la posición se asiente. */
    private fun goToChapter2(s: ActivityScenario<ReaderActivity>, progression: Double = 0.4) {
        val publication = app.openBooks.get(created.last())!!
        val link = publication.readingOrder[1]
        val target = publication.locatorFromLink(link)!!.copyWithLocations(progression = progression)
        s.onActivity { navigator(it)!!.go(target) }
        Log.i(TAG, "ir a c2 $progression desde ${position(s)}")
        rule.waitUntil("capítulo 2 en $progression (posición: ${position(s)})", 10_000) {
            val (href, progression) = position(s)
            href.endsWith("c2.xhtml") && progression != null && abs(progression - (if (target.locations.progression!! > 0.9) 0.87 else target.locations.progression!!)) < 0.15
        }
        Thread.sleep(800)
        // En la prueba, los efectos de Compose (la pantalla sabe en qué capítulo está) avanzan con la regla.
        rule.waitForIdle()
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
        var focused = false
        s.onActivity { focused = it.hasWindowFocus() }
        assumeTrue("el Lector no está al frente", focused)
        val m = app.resources.displayMetrics
        val x0 = m.widthPixels / 2f
        val y0 = if (g.dy < 0) m.heightPixels * 0.65f else m.heightPixels * 0.35f
        // Subir con desvío a la izquierda; bajar con desvío a la derecha.
        val dx = (abs(g.dy) * tan(Math.toRadians(g.degrees))).roundToInt().let { if (g.dy < 0) -it else it }
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
    }
}
