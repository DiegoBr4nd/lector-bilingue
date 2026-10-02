package io.github.diegobr4nd.lectorbilingue.engine.opus

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpusEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val enEs = LanguagePair("en", "es")

    private fun engineWithModel(bridge: FakeNativeBridge = FakeNativeBridge()): Pair<OpusEngine, FakeNativeBridge> {
        File(tmp.root, "en-es").mkdirs()
        return OpusEngine(tmp.root, bridge, Dispatchers.Default) to bridge
    }

    @Test fun `id es opus`() = assertEquals("opus", OpusEngine(tmp.root, FakeNativeBridge()).id)

    @Test fun `detecta si el modelo esta presente`() {
        val engine = OpusEngine(tmp.root, FakeNativeBridge())
        assertFalse(engine.isModelPresent(enEs))
        File(tmp.root, "en-es").mkdirs()
        assertTrue(engine.isModelPresent(enEs))
    }

    @Test fun `load sin modelo falla con mensaje claro`() = runTest {
        val e = assertFailsWith<IllegalStateException> { OpusEngine(tmp.root, FakeNativeBridge()).load(enEs, EngineConfig()) }
        assertTrue(e.message!!.contains("en-es"))
    }

    @Test fun `load pasa carpeta hilos y beam al puente`() = runTest {
        val (engine, bridge) = engineWithModel()
        engine.load(enEs, EngineConfig(beamSize = 2, threads = 4))
        assertEquals(listOf(Triple(File(tmp.root, "en-es").path, 4, 2)), bridge.loads)
    }

    @Test fun `load rechaza mas de 8 hilos antes del puente`() = runTest {
        val (engine, bridge) = engineWithModel()
        assertFailsWith<IllegalArgumentException> { engine.load(enEs, EngineConfig(threads = 9)) }
        assertTrue(bridge.loads.isEmpty())
    }

    @Test fun `segundo load libera el primero`() = runTest {
        val (engine, bridge) = engineWithModel()
        engine.load(enEs, EngineConfig())
        engine.load(enEs, EngineConfig(beamSize = 4))
        assertEquals(listOf(1L), bridge.unloaded)
    }

    @Test fun `translate antes de load falla`() = runTest {
        val (engine, _) = engineWithModel()
        assertFailsWith<IllegalStateException> { engine.translate(listOf("Hi.")) }
    }

    @Test fun `lista vacia no cruza al puente`() = runTest {
        val (engine, bridge) = engineWithModel()
        engine.load(enEs, EngineConfig())
        assertEquals(emptyList(), engine.translate(emptyList()))
        assertTrue(bridge.batchSizes.isEmpty())
    }

    @Test fun `traduce en orden`() = runTest {
        val (engine, _) = engineWithModel()
        engine.load(enEs, EngineConfig())
        assertEquals(listOf("ES:A.", "ES:B."), engine.translate(listOf("A.", "B.")))
    }

    @Test fun `parte en lotes de 64`() = runTest {
        val (engine, bridge) = engineWithModel()
        engine.load(enEs, EngineConfig())
        val input = (1..130).map { "S$it." }
        val output = engine.translate(input)
        assertEquals(listOf(64, 64, 2), bridge.batchSizes)
        assertEquals(input.map { "ES:$it" }, output)
    }

    @Test fun `rechaza oracion de 1001 caracteres sin cruzar al puente`() = runTest {
        val (engine, bridge) = engineWithModel()
        engine.load(enEs, EngineConfig())
        val e = assertFailsWith<IllegalArgumentException> { engine.translate(listOf("a".repeat(1001))) }
        assertFalse(e.message!!.contains("aaaa"), "el mensaje no debe incluir el texto")
        assertTrue(bridge.batchSizes.isEmpty())
    }

    @Test fun `acepta oracion de 1000 caracteres`() = runTest {
        val (engine, _) = engineWithModel()
        engine.load(enEs, EngineConfig())
        assertEquals(1, engine.translate(listOf("a".repeat(1000))).size)
    }

    @Test fun `unload libera y despues translate falla`() = runTest {
        val (engine, bridge) = engineWithModel()
        engine.load(enEs, EngineConfig())
        engine.unload()
        engine.unload()
        assertEquals(listOf(1L), bridge.unloaded)
        assertFailsWith<IllegalStateException> { engine.translate(listOf("Hi.")) }
    }

    @Test fun `nunca dos llamadas nativas a la vez`() = runBlocking {
        val (engine, bridge) = engineWithModel(FakeNativeBridge(delayMillis = 5))
        engine.load(enEs, EngineConfig())
        (1..20).map { async(Dispatchers.Default) { engine.translate(listOf("S$it.")) } }.awaitAll()
        assertEquals(1, bridge.maxConcurrent)
    }
}
