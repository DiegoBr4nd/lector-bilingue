package io.github.diegobr4nd.lectorbilingue.data

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TranslationScopeTest {
    // Seguridad 3b (BAJO): un fallo inesperado en el ámbito del servicio no tumba la app ni deja su mensaje en el registro.
    @Test fun unFalloEnElAmbitoNoLlegaAlManejadorDelHiloYElAmbitoSigue() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "motor-prueba") }
        val seen = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> synchronized(seen) { seen += e } }
        try {
            val scope = translationScope(executor.asCoroutineDispatcher())
            assertNotNull(scope.coroutineContext[CoroutineExceptionHandler])
            scope.launch { throw IllegalStateException("texto del libro") }.join()
            var ran = false
            scope.launch { ran = true }.join()
            assertTrue(ran)
            assertTrue(scope.isActive)
            assertEquals(emptyList(), synchronized(seen) { seen.toList() })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            executor.shutdownNow()
        }
    }
}
