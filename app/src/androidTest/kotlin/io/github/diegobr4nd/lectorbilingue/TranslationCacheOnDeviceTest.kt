package io.github.diegobr4nd.lectorbilingue

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.books.db.LectorDatabase
import io.github.diegobr4nd.lectorbilingue.books.db.TranslationEntity
import io.github.diegobr4nd.lectorbilingue.data.EngineProvider
import io.github.diegobr4nd.lectorbilingue.data.Priority
import io.github.diegobr4nd.lectorbilingue.data.TranslateRequest
import io.github.diegobr4nd.lectorbilingue.data.TranslationService
import io.github.diegobr4nd.lectorbilingue.data.translationScope
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.api.TranslationEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Borrar las traducciones guardadas, con Room EN MEMORIA y un servicio real con un motor de mentira:
 * nunca toca la base real (lector.db) con las traducciones de Juan en el teléfono.
 */
@RunWith(AndroidJUnit4::class)
class TranslationCacheOnDeviceTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, LectorDatabase::class.java,
    ).build()
    private val dao = db.translations()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "motor-prueba") }.asCoroutineDispatcher()
    private val scope = translationScope(worker)
    private val pair = LanguagePair("en", "es")

    /** Motor de mentira: con [gate] cada `translate` espera a [open]. */
    private class GateEngine(private val gate: Boolean) : TranslationEngine {
        override val id: String = EngineId.OPUS.wire
        val opened = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        override suspend fun load(pair: LanguagePair, config: EngineConfig) {}
        override suspend fun translate(sentences: List<String>): List<String> {
            started.complete(Unit)
            if (gate) opened.await()
            return sentences.map { "T($it)" }
        }
        override fun unload() {}
    }

    private fun provider(engine: GateEngine) = object : EngineProvider {
        override fun installed(pair: LanguagePair) = mapOf(EngineId.OPUS to "opus:opus-en-es:1")
        override fun engine(id: EngineId): TranslationEngine = engine
        override fun config(id: EngineId) = EngineConfig()
        override fun totalRamBytes() = 8L * 1024 * 1024 * 1024
        override fun forced(): EngineId? = null
        override fun hasMemoryFor(id: EngineId) = true
    }

    private fun service(engine: GateEngine) = TranslationService(provider(engine), dao, worker = worker, scope = scope)

    @After fun close() {
        scope.cancel()
        db.close()
        worker.close()
    }

    @Test fun borrarDejaLaTablaVaciaYElTamanoEnCero() = runBlocking {
        val s = service(GateEngine(gate = false))
        listOf("prueba-a", "prueba-b", "prueba-c").forEach { dao.put(TranslationEntity(it, "T($it)", 1L)) }
        assertEquals(3, dao.count())
        assertTrue(s.cacheBytes() > 0)
        s.clearCache()
        assertEquals(0, dao.count())
        assertEquals(0L, s.cacheBytes())
        s.clearCache() // vacío: no falla
        Unit
    }

    @Test fun borrarConTraduccionEnCursoNoDejaFilas() = runBlocking {
        val engine = GateEngine(gate = true)
        val s = service(engine)
        dao.put(TranslationEntity("prueba-vieja", "T(vieja)", 1L))
        s.prefetch(listOf(TranslateRequest(pair, "Uno.", Priority.PREFETCH, "c1")))
        val tap = async(Dispatchers.Default) {
            runCatching { s.translate(TranslateRequest(pair, "Toque.", Priority.TAP, "c1")) }
        }
        withTimeout(10_000) { engine.started.await() } // ya hay una traducción en curso, detenida en la compuerta
        s.clearCache()
        engine.opened.complete(Unit) // soltar la compuerta tarde no debe guardar nada
        withTimeout(10_000) { tap.await() }
        assertEquals(0, dao.count())
        assertTrue(tap.await().isFailure, "el pedido en curso recibe cancelación")
    }
}
