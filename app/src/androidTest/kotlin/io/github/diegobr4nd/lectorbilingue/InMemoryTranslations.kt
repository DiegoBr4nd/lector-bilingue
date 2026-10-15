package io.github.diegobr4nd.lectorbilingue

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.books.db.LectorDatabase
import io.github.diegobr4nd.lectorbilingue.data.AndroidEngineProvider
import io.github.diegobr4nd.lectorbilingue.data.TranslationService
import io.github.diegobr4nd.lectorbilingue.data.translationScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.rules.ExternalResource
import java.util.concurrent.Executors

/**
 * Regla de prueba: mientras dura cada prueba, `app.translations` es un servicio con el motor REAL y una caché de Room
 * EN MEMORIA. Así nunca se toca la caché de traducciones de Juan. El Lector lo toma al crear su ViewModel.
 *
 * El cambio se hace en [before] (no al construir la clase de prueba): si algo falla al armar el servicio, aún no se
 * cambió nada; y una vez cambiado, [after] corre siempre (seguridad 3c-2, BAJO-4) y devuelve el servicio de la app.
 */
class InMemoryTranslations : ExternalResource() {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LectorApp
    // `by lazy` de LectorApp: el campo que Kotlin genera para él.
    private val delegate = LectorApp::class.java.getDeclaredField("translations\$delegate").apply { isAccessible = true }
    private var original: Any? = null
    private var db: LectorDatabase? = null
    private var worker: ExecutorCoroutineDispatcher? = null

    /** El servicio de la prueba (válido entre [before] y [after]). */
    lateinit var service: TranslationService
        private set

    override fun before() {
        try {
            val db = Room.inMemoryDatabaseBuilder(app, LectorDatabase::class.java).build().also { db = it }
            val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "motor-prueba") }.asCoroutineDispatcher().also { worker = it }
            service = TranslationService(AndroidEngineProvider(app, worker), db.translations(), worker = worker, scope = translationScope(worker))
            val appLazy = delegate.get(app) as Lazy<*>
            // Si el servicio de la app ya existía (otra prueba del mismo proceso), se suelta su motor antes de cambiarlo:
            // así no quedan dos motores cargados. Si no existía, no se crea.
            if (appLazy.isInitialized()) (appLazy.value as TranslationService).release()
            original = appLazy
            delegate.set(app, lazyOf(service))
        } catch (e: Throwable) {
            after() // ExternalResource no llama a after() si before() falla
            throw e
        }
    }

    override fun after() {
        try {
            original?.let { delegate.set(app, it) }
            original = null
        } finally {
            if (::service.isInitialized) runCatching { service.release() }
            db?.close(); db = null
            worker?.close(); worker = null
        }
    }
}
