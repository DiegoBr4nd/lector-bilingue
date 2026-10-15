package io.github.diegobr4nd.lectorbilingue

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.data.PageTheme
import io.github.diegobr4nd.lectorbilingue.data.ReadingMode
import io.github.diegobr4nd.lectorbilingue.data.ReadingSettings
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
import kotlin.test.assertTrue

/**
 * Tiempos de los modos en el Pixel (3c-2, Tarea 6): motor y modelo inglés → español REALES (si no están, se salta),
 * el libro inventado en inglés de [EnglishBook] y el Lector de verdad, sin tocar la pantalla.
 *
 * - Solo traducción: cuánto tarda la primera pantalla en quedar traducida con el motor frío (al abrir: incluye cargar
 *   el modelo) y caliente (saltar a una parte aún sin traducir, con el motor ya cargado).
 * - Intercalado: tiempo por párrafo, con el motor ya cargado (la guía pide de a uno).
 *
 * La caché de traducciones es una base en memoria ([InMemoryTranslations]): no se toca la de Juan y cada prueba
 * empieza sin nada traducido. Los ajustes de Juan se devuelven al terminar. Los registros solo llevan números.
 */
@RunWith(AndroidJUnit4::class)
class ModesTimingOnDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    @get:Rule val translations = InMemoryTranslations()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LectorApp
    private val dir = File(app.cacheDir, "modes_timing").apply { deleteRecursively(); mkdirs() }
    private val created = mutableListOf<String>()
    private val saved = app.settings.readingSettings

    @After fun cleanUp() = runBlocking<Unit> {
        try {
            for (id in created) {
                app.openBooks.close(id)
                app.books.delete(id)
            }
            dir.deleteRecursively()
        } finally {
            app.settings.readingSettings = saved
        }
    }

    @Test fun soloPrimeraPantallaFrioYCaliente() = runBlocking<Unit> {
        val id = prepare(ReadingMode.TRANSLATION_ONLY)
        val t0 = SystemClock.elapsedRealtime()
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitFor("primera pantalla traducida (frío)", FILL_TIMEOUT) { filled(s, "solo") }
            val cold = SystemClock.elapsedRealtime() - t0
            val shown = js(s, "String(${onScreen("solo")}.n)")
            // Caliente: con el motor cargado y lo pedido ya hecho, se salta a una parte aún sin traducir.
            val warm = listOf(0.4, 0.7).map { p ->
                waitFor("sin párrafos en espera", FILL_TIMEOUT) { js(s, NONE_WAITING) == "true" }
                val t1 = SystemClock.elapsedRealtime()
                go(s, p)
                waitFor("pantalla a $p traducida (caliente)", FILL_TIMEOUT) {
                    (js(s, PROGRESSION)?.toDoubleOrNull() ?: 0.0) > p - 0.1 && filled(s, "solo")
                }
                SystemClock.elapsedRealtime() - t1
            }
            Log.i(TAG, "solo frio_ms=$cold parrafos_en_pantalla=$shown caliente_ms=${warm.joinToString(",")}")
        }
    }

    @Test fun intercaladoTiempoPorParrafo() = runBlocking<Unit> {
        val id = prepare(ReadingMode.INTERLEAVED)
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use { s ->
            waitFor("página lista") { js(s, PAGE_READY) == "true" }
            // En la página: el instante en que cada tarjeta llega a "texto" (por índice, solo la primera vez).
            js(s, RECORD)
            waitFor("pantalla traducida", FILL_TIMEOUT) { filled(s, "texto") }
            waitFor("también los siguientes", FILL_TIMEOUT) { js(s, NONE_WAITING) == "true" }
            val times = JSONArray(js(s, "JSON.stringify(Object.keys(window.__t).map(function (k) { return window.__t[k]; }))")!!)
            val sorted = (0 until times.length()).map { times.getDouble(it) }.sorted()
            assertTrue(sorted.size >= 5, "pocas tarjetas (${sorted.size})")
            // El primero incluye cargar el modelo; los demás, uno tras otro con el motor ya cargado.
            val gaps = sorted.zipWithNext { a, b -> Math.round(b - a) }
            val median = gaps.sorted()[gaps.size / 2]
            // Palabras de un párrafo del libro (todos tienen el mismo largo aproximado): solo el número.
            val words = (1..20).sumOf { EnglishBook.paragraph(it).split(' ').size } / 20.0
            Log.i(
                TAG,
                "intercalado tarjetas=${sorted.size} primera_ms=${Math.round(sorted.first())} por_parrafo_ms=${gaps.joinToString(",")} " +
                    "mediana_ms=$median palabras_por_parrafo=${"%.1f".format(words)} palabras_por_s=${"%.1f".format(words * 1000 / median)}",
            )
        }
    }

    // ---------- Ayudas ----------

    private suspend fun prepare(mode: ReadingMode): String {
        withTimeout(10_000) { app.hub.loaded.first { it } }
        assumeTrue("falta el modelo inglés → español", app.hub.pairs.value.any { it.pair == "en-es" && it.rows.any { r -> r.installed } })
        app.settings.readingSettings = ReadingSettings(theme = PageTheme.LIGHT, mode = mode)
        return EnglishBook.importAndOpen(app, dir).also { created += it }
    }

    /** A [progression] del capítulo 1 (sin esperar: el tiempo corre desde aquí). */
    private fun go(s: ActivityScenario<ReaderActivity>, progression: Double) {
        val publication = app.openBooks.get(created.last())!!
        val target = publication.locatorFromLink(publication.readingOrder[0])!!.copyWithLocations(progression = progression)
        s.onActivity { navigator(it)!!.go(target) }
    }

    /** ¿Cada párrafo en pantalla tiene ya su tarjeta en el estado [state]? (como ModesOnDeviceTest.filled) */
    private fun filled(s: ActivityScenario<ReaderActivity>, state: String): Boolean {
        val r = js(s, "(function () { var r = ${onScreen(state)}; return r.n + '/' + r.ok; })()")?.split('/') ?: return false
        return r.size == 2 && r[0].toInt() > 0 && r[0] == r[1]
    }

    private fun waitFor(what: String, timeout: Long = 10_000, done: () -> Boolean) = rule.waitUntil(what, timeout) { done() }

    private fun navigator(a: ReaderActivity): EpubNavigatorFragment? =
        a.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()

    private fun js(s: ActivityScenario<ReaderActivity>, script: String): String? {
        var nav: EpubNavigatorFragment? = null
        s.onActivity { nav = navigator(it) }
        val raw = nav?.let { n -> runBlocking(Dispatchers.Main) { n.evaluateJavascript(script) } } ?: return null
        return JSONArray("[$raw]").opt(0)?.takeIf { it != JSONObject.NULL }?.toString()
    }

    private companion object {
        const val TAG = "ModosTiempos"
        const val FILL_TIMEOUT = 180_000L
        const val PAGE_READY = "String(document.readyState === 'complete' && !!window.readium)"
        const val PROGRESSION = "String(window.scrollY / (document.scrollingElement || document.documentElement).scrollHeight)"

        /** Ninguna tarjeta en espera en todo el capítulo (ni "Traduciendo…", ni esqueleto, ni "Preparando…"). */
        const val NONE_WAITING = "String(document.querySelectorAll('aside.lector-tarjeta[data-lector-estado=\"espera\"], " +
            "aside.lector-tarjeta[data-lector-estado=\"esqueleto\"], aside.lector-tarjeta[data-lector-estado=\"preparando\"]').length === 0)"

        /** Anota en `window.__t[índice]` el `performance.now()` en que la tarjeta pasa a "texto" (solo la primera vez). */
        const val RECORD = "window.__t = {}; new MutationObserver(function (ms) { ms.forEach(function (m) { var c = m.target;" +
            " if (c.matches && c.matches('aside.lector-tarjeta') && c.dataset.lectorEstado === 'texto' && !(c.dataset.lectorI in window.__t))" +
            " window.__t[c.dataset.lectorI] = performance.now(); }); }).observe(document.body, {subtree: true, attributes: true," +
            " attributeFilter: ['data-lector-estado']}); 1"

        /** {n, ok}: párrafos en pantalla y cuántos tienen tarjeta en [state] (un original oculto se mide por su tarjeta). */
        fun onScreen(state: String) = "(function () { var sel = 'p, li, blockquote, h1, h2, h3, h4, h5, h6, dd';" +
            " var all = Array.prototype.filter.call(document.querySelectorAll(sel), function (e) { return !e.closest('.lector-tarjeta') && !e.querySelector(sel); });" +
            " var h = window.innerHeight, n = 0, ok = 0; for (var k = 0; k < all.length; k++) { var e = all[k], c = e.nextElementSibling;" +
            " var isCard = !!c && c.matches('aside.lector-tarjeta'); var r = (e.classList.contains('lector-oculto') && isCard ? c : e).getBoundingClientRect();" +
            " if (r.height <= 0 || r.bottom <= 0) continue; if (r.top >= h) break; n++; if (isCard && c.dataset.lectorEstado === '$state') ok++; }" +
            " return {n: n, ok: ok}; })()"
    }
}
