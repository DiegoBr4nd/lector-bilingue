package io.github.diegobr4nd.lectorbilingue

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.data.Priority
import io.github.diegobr4nd.lectorbilingue.data.TranslateRequest
import io.github.diegobr4nd.lectorbilingue.data.TranslateResult
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.assertTrue

/**
 * Tiempos de tocar y traducir con el motor REAL en el Pixel (modelo en-es ya instalado; si no está, se salta).
 * Textos inventados en inglés con una marca al azar por corrida, para que el caché de corridas anteriores no
 * falsee los números "en frío". Solo registra números (nunca texto ni traducciones). Las filas que crea quedan
 * en el caché (inofensivas: la marca es única y nadie más las pide).
 */
@RunWith(AndroidJUnit4::class)
class TapTranslateTimingOnDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LectorApp
    private val pair = LanguagePair("en", "es")
    private val token = UUID.randomUUID().toString().take(8)

    private val bank = ("the old lighthouse keeper walked slowly along the narrow path while the sea wind carried the smell of salt " +
        "and wet stone across the quiet hills and he thought about the long winter that was coming and the letters he had " +
        "never answered because every morning there was something more urgent to do near the harbor").split(" ")

    /** Un párrafo de [words] palabras, distinto en cada [n] y en cada corrida. */
    private fun paragraph(n: Int, words: Int): String {
        val body = (0 until words - 2).joinToString(" ") { bank[(n * 7 + it * 3) % bank.size] }
        return "Chapter $token item $n. $body."
    }

    private fun req(text: String, p: Priority = Priority.TAP) = TranslateRequest(pair, text, p, "timing-$token")

    private suspend fun timed(text: String): Pair<Long, TranslateResult> {
        val t0 = SystemClock.elapsedRealtime()
        val r = app.translations.translate(req(text))
        return (SystemClock.elapsedRealtime() - t0) to r
    }

    /** Con `-e visible true` la app está al frente (la medida real: el sistema da los núcleos rápidos); sin eso, de fondo. */
    @Test fun tiempos() {
        if (InstrumentationRegistry.getArguments().getString("visible") == "true") {
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { medir() }
        } else {
            medir()
        }
    }

    private fun medir() = runBlocking<Unit> {
        withTimeout(10_000) { app.hub.loaded.first { it } }
        assumeTrue("falta el modelo inglés → español", app.hub.pairs.value.any { it.pair == "en-es" && it.rows.any { r -> r.installed } })

        // 1. Motor frío (incluye la carga).
        app.translations.release()
        delay(1_000)
        val (cold, rc) = timed(paragraph(0, 50))
        assertTrue(rc is TranslateResult.Done, "frío no terminó")

        // 2. Motor cargado, párrafo típico (50 palabras), 5 muestras.
        val warm = (1..5).map { n -> timed(paragraph(n, 50)).also { assertTrue(it.second is TranslateResult.Done) }.first }

        // 3. Pretraducido: se pretraduce y, cuando termina (el caché responde), se mide el toque.
        val pre = (100..104).map { paragraph(it, 50) }
        app.translations.prefetch(pre.map { req(it, Priority.PREFETCH) })
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (app.translations.cached(pair, pre).size < pre.size && SystemClock.elapsedRealtime() < deadline) delay(200)
        assertTrue(app.translations.cached(pair, pre).size == pre.size, "la pretraducción no terminó")
        val cachedMs = pre.map { timed(it).also { r -> assertTrue(r.second is TranslateResult.Done) }.first }

        // 4. Palabras por segundo en 20 párrafos (mezcla de 30 a 70 palabras), uno tras otro.
        val sizes = (0 until 20).map { 30 + (it * 2) % 41 }
        val texts = sizes.mapIndexed { i, w -> paragraph(200 + i, w) }
        val words = texts.sumOf { it.split(" ").size }
        val t0 = SystemClock.elapsedRealtime()
        for (t in texts) assertTrue(app.translations.translate(req(t)) is TranslateResult.Done)
        val total = SystemClock.elapsedRealtime() - t0

        Log.i(TAG, "visible=${InstrumentationRegistry.getArguments().getString("visible") == "true"}")
        Log.i(TAG, "frio_ms=$cold")
        Log.i(TAG, "cargado_ms=${warm.joinToString(",")}")
        Log.i(TAG, "pretraducido_ms=${cachedMs.joinToString(",")}")
        Log.i(TAG, "lote20 palabras=$words total_ms=$total palabras_por_s=${"%.1f".format(words * 1000.0 / total)}")
        app.translations.release()
    }

    private companion object { const val TAG = "TimingTap" }
}
