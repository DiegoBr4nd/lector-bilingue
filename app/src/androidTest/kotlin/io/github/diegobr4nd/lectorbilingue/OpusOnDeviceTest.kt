package io.github.diegobr4nd.lectorbilingue

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.opus.Ct2NativeBridge
import io.github.diegobr4nd.lectorbilingue.engine.opus.OpusEngine
import java.io.File
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
    private val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private val pair = LanguagePair("en", "es")

    @Test fun utf8IdaYVuelta() {
        val text = "Mañana 😀 café — “quote” 𝄞 ñ"
        assertEquals(text, Ct2NativeBridge.utf8RoundTrip(text))
    }

    @Test fun sustitutoSueltoNoRompe() {
        assertEquals("a�b", Ct2NativeBridge.utf8RoundTrip("a\uD800b"))
    }

    @Test fun traduceHolaMundo() = runBlocking<Unit> {
        val engine = OpusEngine(File(filesDir, "models"))
        assumeTrue("modelo no copiado", engine.isModelPresent(pair))
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
        val engine = OpusEngine(File(filesDir, "models"))
        assumeTrue("modelo no copiado", engine.isModelPresent(pair))
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

    @Test fun puenteRechazaHandleInvalidoYLoteGrande() {
        assertFailsWith<IllegalStateException> { Ct2NativeBridge.translate(12345L, arrayOf("Hi.")) }
        assertFailsWith<IllegalArgumentException> { Ct2NativeBridge.translate(12345L, Array(65) { "Hi." }) }
        Ct2NativeBridge.unload(12345L) // no-op, sin crash
    }
}
