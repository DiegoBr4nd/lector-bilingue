package io.github.diegobr4nd.lectorbilingue

import android.app.ActivityManager
import android.os.SharedMemory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.books.db.LectorDatabase
import io.github.diegobr4nd.lectorbilingue.data.AndroidEngineProvider
import io.github.diegobr4nd.lectorbilingue.data.EngineProvider
import io.github.diegobr4nd.lectorbilingue.data.Priority
import io.github.diegobr4nd.lectorbilingue.data.TranslateRequest
import io.github.diegobr4nd.lectorbilingue.data.TranslateResult
import io.github.diegobr4nd.lectorbilingue.data.TranslationService
import io.github.diegobr4nd.lectorbilingue.data.translationScope
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El fallo que vio Juan: traducir, borrar las traducciones guardadas en Idiomas y volver a tocar un párrafo daba
 * "No se pudo preparar el traductor" para siempre. Borrar descarga el motor; volver a cargarlo pedía 450 MB libres y
 * el teléfono, con muchas apps de fondo, tenía 376-420 MB. Aquí: motor y modelo REALES (en-es instalado; si no, se
 * salta), caché de Room EN MEMORIA (nunca la de Juan), textos inventados y solo números o nombres de tipo en los
 * mensajes. Un "lastre" de memoria nativa deja el teléfono con menos de 450 MB libres, como el de Juan.
 */
@RunWith(AndroidJUnit4::class)
class ClearCacheReloadOnDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LectorApp
    private val db = Room.inMemoryDatabaseBuilder(app, LectorDatabase::class.java).build()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "motor-prueba") }.asCoroutineDispatcher()
    private val scope = translationScope(worker)
    private val pair = LanguagePair("en", "es")
    // Memoria compartida (fuera del montón de Java, que tiene tope de 256 MB): cuenta como memoria usada del teléfono.
    private val ballast = ArrayList<SharedMemory>()

    @After fun close() {
        dropBallast()
        scope.cancel()
        db.close()
        worker.close()
    }

    private fun memory(): ActivityManager.MemoryInfo =
        ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }

    /** Memoria nativa tocada hasta dejar menos de 430 MB libres, sin acercarse al umbral de memoria baja del sistema. */
    private fun squeeze(): Long {
        while (ballast.size < 120) {
            val m = memory()
            if (m.availMem < 430L * MB || m.availMem < m.threshold + 150L * MB) break
            val shm = SharedMemory.create("lastre", 32 * MB.toInt())
            val b = shm.mapReadWrite()
            var k = 0
            while (k < b.capacity()) { b.put(k, 1); k += 4096 }
            SharedMemory.unmap(b) // las páginas siguen ocupadas mientras viva [shm]
            ballast += shm
            Thread.sleep(50)
        }
        return memory().availMem / MB
    }

    private fun dropBallast() {
        ballast.forEach { it.close() }
        ballast.clear()
    }

    private suspend fun assumeModel() {
        withTimeout(10_000) { app.hub.loaded.first { it } }
        assumeTrue("falta el modelo inglés → español", app.hub.pairs.value.any { it.pair == "en-es" && it.rows.any { r -> r.installed } })
    }

    private fun req(text: String) = TranslateRequest(pair, text, Priority.TAP, "prueba-borrar")

    @Test fun traducirBorrarYTraducirConPocaMemoriaLibreTraduce() = runBlocking<Unit> {
        assumeModel()
        val s = TranslationService(AndroidEngineProvider(app, worker), db.translations(), worker = worker, scope = scope)
        try {
            val first = s.translate(req("The cat sleeps on the warm stone."))
            assertTrue(first is TranslateResult.Done, "antes de borrar: ${first::class.simpleName}")
            s.clearCache()
            val free = squeeze()
            assumeTrue("no se pudo dejar el teléfono con menos de 450 MB libres", free < 450)
            val m = memory()
            val second = s.translate(req("The dog runs along the quiet road."))
            assertTrue(
                second is TranslateResult.Done,
                "tras borrar: ${second::class.simpleName}, libres=$free MB, umbral=${m.threshold / MB} MB, baja=${m.lowMemory}",
            )
            assertEquals(1, db.translations().count())
        } finally {
            dropBallast()
            s.release()
            withContext(worker) {} // el hilo es único y en orden: al volver, la descarga del motor ya corrió
        }
    }

    @Test fun reintentarTrasUnFalloAlPrepararTraduce() = runBlocking<Unit> {
        assumeModel()
        val real = AndroidEngineProvider(app, worker)
        var refuse = true // la primera vez el motor "no se puede preparar"
        val provider = object : EngineProvider by real {
            override fun hasMemoryFor(id: EngineId) = if (refuse) false.also { refuse = false } else real.hasMemoryFor(id)
        }
        val s = TranslationService(provider, db.translations(), worker = worker, scope = scope)
        try {
            assertEquals(TranslateResult.EngineFailed, s.translate(req("The bird sings at dawn.")))
            val free = squeeze()
            assumeTrue("no se pudo dejar el teléfono con menos de 450 MB libres", free < 450)
            val again = s.translate(req("The bird sings at dawn."))
            assertTrue(again is TranslateResult.Done, "al reintentar: ${again::class.simpleName}, libres=$free MB")
        } finally {
            dropBallast()
            s.release()
            withContext(worker) {} // el hilo es único y en orden: al volver, la descarga del motor ya corrió
        }
    }

    private companion object { const val MB = 1024L * 1024 }
}
