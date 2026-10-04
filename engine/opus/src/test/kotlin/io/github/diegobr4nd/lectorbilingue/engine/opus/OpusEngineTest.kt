package io.github.diegobr4nd.lectorbilingue.engine.opus

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.api.ModelNotInstalledException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
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
    private val created = mutableListOf<OpusEngine>()
    private val baseCount = OpusEngine.loadedEngineCount()

    @After fun descargarTodo() = created.forEach { it.unload() }

    private fun engineWithModel(bridge: FakeNativeBridge = FakeNativeBridge()): Pair<OpusEngine, FakeNativeBridge> {
        val dir = File(tmp.root, "en-es").also { it.mkdirs() }
        return OpusEngine({ dir }, bridge, Dispatchers.Default).also { created += it } to bridge
    }

    @Test fun `id es opus`() = assertEquals("opus", OpusEngine({ null }, FakeNativeBridge()).id)

    @Test fun `load sin modelo instalado lanza ModelNotInstalledException sin tocar el puente`() = runTest {
        val bridge = FakeNativeBridge()
        val e = assertFailsWith<ModelNotInstalledException> { OpusEngine({ null }, bridge).load(enEs, EngineConfig()) }
        assertEquals(EngineId.OPUS, e.engine)
        assertEquals(enEs, e.pair)
        assertTrue(bridge.loads.isEmpty())
        val msg = e.message.orEmpty()
        assertFalse(msg.contains("/") || msg.contains("\\"), "el mensaje no debe filtrar rutas")
    }

    @Test fun `carpeta que no es directorio falla sin tocar el puente`() = runTest {
        val bridge = FakeNativeBridge()
        val file = tmp.newFile("no-es-carpeta")
        val e = assertFailsWith<IllegalStateException> { OpusEngine({ file }, bridge).load(enEs, EngineConfig()) }
        assertEquals("carpeta de modelo no válida", e.message)
        assertTrue(bridge.loads.isEmpty())
    }

    @Test fun `el proveedor se invoca dentro de load y en el despachador del motor`() = runBlocking<Unit> {
        val threads = mutableListOf<String>()
        val dir = tmp.newFolder("m")
        val engine = OpusEngine(
            { threads += Thread.currentThread().name; dir },
            FakeNativeBridge(),
            Dispatchers.Default,
        ).also { created += it }
        assertTrue(threads.isEmpty(), "no se invoca al construir")
        engine.load(enEs, EngineConfig())
        assertEquals(1, threads.size)
        assertTrue(threads.single().startsWith("DefaultDispatcher"), "hilo: ${threads.single()}")
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

    private fun delta() = OpusEngine.loadedEngineCount() - baseCount

    @Test fun `contador sube con load, no cambia al recargar y baja con unload`() = runTest {
        val (engine, _) = engineWithModel()
        assertEquals(0, delta())
        engine.load(enEs, EngineConfig())
        assertEquals(1, delta())
        engine.load(enEs, EngineConfig(beamSize = 4))
        assertEquals(1, delta())
        engine.unload()
        assertEquals(0, delta())
        engine.unload()
        assertEquals(0, delta())
    }

    @Test fun `dos motores cargados cuentan dos`() = runTest {
        val (a, _) = engineWithModel()
        val (b, _) = engineWithModel()
        a.load(enEs, EngineConfig())
        b.load(enEs, EngineConfig())
        assertEquals(2, delta())
        a.unload()
        assertEquals(1, delta())
    }

    @Test fun `fallo al recargar deja contador en cero y translate falla`() = runTest {
        val (engine, bridge) = engineWithModel()
        engine.load(enEs, EngineConfig())
        assertEquals(1, delta())
        bridge.failOnLoad = true
        assertFailsWith<IllegalStateException> { engine.load(enEs, EngineConfig()) }
        assertEquals(0, delta())
        assertFailsWith<IllegalStateException> { engine.translate(listOf("Hi.")) }
    }
}
