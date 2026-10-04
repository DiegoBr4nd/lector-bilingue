package io.github.diegobr4nd.lectorbilingue

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.firefox.FirefoxEngine
import io.github.diegobr4nd.lectorbilingue.models.Models
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Corre en el Pixel con el modelo Firefox instalado por el gestor. Sin modelo, se salta. */
@RunWith(AndroidJUnit4::class)
class FirefoxOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pair = LanguagePair("en", "es")

    @Test fun traduceEnEsDeVerdad() = runBlocking<Unit> {
        Models.recover(context)
        val dir = Models.installedDir(context, "firefox", "en-es")
        assumeTrue("modelo no instalado", dir != null)
        val engine = FirefoxEngine({ dir })
        // Un hilo: es lo que usará la app.
        engine.load(pair, EngineConfig(threads = 1))
        try {
            val input = "The old library was quiet."
            val out = engine.translate(listOf(input))
            assertTrue(out.size == 1 && out[0].isNotBlank(), "salida vacía")
            // No se imprime el texto: solo se compara.
            assertNotEquals(input, out[0], "la traducción es igual a la entrada")
        } finally {
            engine.unload()
        }
    }

    /** Entradas raras: no debe romperse nada y siempre sale una traducción por entrada. No se imprime texto. */
    @Test fun entradasRarasNoRompen() = runBlocking<Unit> {
        Models.recover(context)
        var ran = 0
        for ((src, dst) in listOf("en" to "es", "es" to "en")) {
            val dir = Models.installedDir(context, "firefox", "$src-$dst")
            if (dir == null) continue
            ran++
            val engine = FirefoxEngine({ dir })
            engine.load(LanguagePair(src, dst), EngineConfig(threads = 1))
            try {
                val cases = listOf(
                    "emoji" to listOf("😀😀😀"),
                    "maxima" to listOf("x".repeat(1000)),
                    "vacia" to listOf(""),
                    "puntuacion" to listOf("...?!;:,-"),
                    "mezcla" to listOf("Hello мир 世界 مرحبا 😀 hola"),
                    "lote" to List(64) { "Sentence number $it." },
                )
                for ((name, input) in cases) {
                    assertEquals(input.size, engine.translate(input).size, "$src-$dst caso $name")
                }
            } finally {
                engine.unload()
            }
        }
        assumeTrue("ningun modelo Firefox instalado", ran > 0)
    }
}
