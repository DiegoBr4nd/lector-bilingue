package io.github.diegobr4nd.lectorbilingue

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.opus.Ct2NativeBridge
import io.github.diegobr4nd.lectorbilingue.engine.opus.OpusEngine
import io.github.diegobr4nd.lectorbilingue.models.Models
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Corre en el Pixel con el modelo copiado (docs/build.md). Sin modelo, se salta. */
@RunWith(AndroidJUnit4::class)
class OpusOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pair = LanguagePair("en", "es")

    /** Motor sobre la carpeta instalada por el gestor; salta la prueba si no hay modelo. */
    private fun installedEngine(): OpusEngine {
        runBlocking { Models.recover(context) }
        val dir = Models.installedDir(context, "opus", "en-es")
        assumeTrue("modelo no instalado", dir != null)
        return OpusEngine({ dir })
    }

    @Test fun utf8IdaYVuelta() {
        val text = "Mañana 😀 café — “quote” 𝄞 ñ"
        assertEquals(text, Ct2NativeBridge.utf8RoundTrip(text))
    }

    @Test fun sustitutoSueltoNoRompe() {
        assertEquals("a�b", Ct2NativeBridge.utf8RoundTrip("a\uD800b"))
    }

    @Test fun traduceHolaMundo() = runBlocking<Unit> {
        val engine = installedEngine()
        engine.load(pair, EngineConfig())
        try {
            val out = engine.translate(listOf("Hello, world.", "The cat is on the table."))
            assertEquals(2, out.size)
            assertTrue(out.all { it.isNotBlank() })
            assertTrue(out[1].contains("gato", ignoreCase = true))
        } finally {
            engine.unload()
        }
    }

    @Test fun entradasRarasNoRompen() = runBlocking<Unit> {
        val engine = installedEngine()
        engine.load(pair, EngineConfig())
        try {
            assertEquals(1, engine.translate(listOf("")).size)
            assertEquals(1, engine.translate(listOf("😀😀😀")).size)
            assertEquals(1, engine.translate(listOf("x".repeat(1000))).size)
            assertFailsWith<IllegalArgumentException> { engine.translate(listOf("x".repeat(1001))) }
        } finally {
            engine.unload()
        }
    }

    @Test fun utf8EstrictoRechazaSobrelargos() {
        val replacement = "�"
        assertEquals(replacement + replacement, Ct2NativeBridge.utf8BytesToString(byteArrayOf(0xC0.toByte(), 0x80.toByte())))
        val overlong3 = Ct2NativeBridge.utf8BytesToString(byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte()))
        assertTrue(overlong3.isNotEmpty() && overlong3.all { it == '�' }, "E0 80 80 debe dar solo U+FFFD")
        assertEquals("😀", Ct2NativeBridge.utf8BytesToString(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte())))
        assertFailsWith<IllegalArgumentException> { Ct2NativeBridge.utf8BytesToString(ByteArray(4097)) }
    }

    @Test fun cienCiclosNoPierdenMemoria() = runBlocking<Unit> {
        val engine = installedEngine()
        assumeTrue(
            "otro motor ya cargado en el proceso (cierra la app: adb shell am force-stop io.github.diegobr4nd.lectorbilingue)",
            OpusEngine.loadedEngineCount() == 0,
        )
        fun cycle() = runBlocking {
            engine.load(pair, EngineConfig())
            try {
                assertEquals(
                    1,
                    OpusEngine.loadedEngineCount(),
                    "Otro motor se cargó durante la medición (¿se abrió la app?); la medición no es válida",
                )
                engine.translate(listOf("Hi."))
            } finally {
                engine.unload()
            }
        }
        repeat(5) { cycle() }
        System.gc()
        val before = Debug.getNativeHeapAllocatedSize()
        repeat(95) { cycle() }
        System.gc()
        val deltaMb = (Debug.getNativeHeapAllocatedSize() - before) / (1024.0 * 1024.0)
        assertTrue(deltaMb < 20.0, "Crecimiento de memoria nativa tras 95 ciclos: %.1f MB (límite 20 MB)".format(deltaMb))
    }

    @Test fun puenteRechazaHandleInvalidoYLoteGrande() {
        assertFailsWith<IllegalStateException> { Ct2NativeBridge.translate(12345L, arrayOf("Hi.")) }
        assertFailsWith<IllegalArgumentException> { Ct2NativeBridge.translate(12345L, Array(65) { "Hi." }) }
        Ct2NativeBridge.unload(12345L) // no-op, sin crash
    }
}
