package io.github.diegobr4nd.lectorbilingue.data

import io.github.diegobr4nd.lectorbilingue.books.db.TranslationEntity
import io.github.diegobr4nd.lectorbilingue.data.FakeEngineProvider.Companion.FIREFOX_TAG
import io.github.diegobr4nd.lectorbilingue.data.FakeEngineProvider.Companion.OPUS_TAG
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationServiceTest {
    private val dispatcher = StandardTestDispatcher()
    private val pair = LanguagePair("en", "es")
    private val dao = FakeTranslationDao()

    /** El servicio con el hilo de prueba como "hilo único" y el TestScope como ámbito de la app. */
    private fun TestScope.service(
        engine: FakeEngine = FakeEngine(),
        provider: FakeEngineProvider = FakeEngineProvider(opus = engine),
    ) = TranslationService(provider, dao, clock = { 42L }, worker = dispatcher, scope = this)

    private fun req(text: String, priority: Priority = Priority.TAP, resource: String = "c1") =
        TranslateRequest(pair, text, priority, resource)

    private fun key(text: String, tag: String = OPUS_TAG) = TranslationRules.cacheKey(tag, pair, text)

    @Test fun tocarSaleAntesQueLaPretraduccion() = runTest(dispatcher) {
        val engine = FakeEngine(gate = true) // cada translate espera a engine.releaseAll()
        val s = service(engine)
        s.prefetch((1..3).map { req("p$it.", Priority.PREFETCH) })
        runCurrent() // p1 en curso
        val tap = async { s.translate(req("toque.", Priority.TAP)) }
        runCurrent(); engine.releaseAll(); advanceUntilIdle()
        assertEquals(listOf("p1.", "toque.", "p2.", "p3."), engine.translatedTexts) // p1 ya estaba en curso
        assertEquals(TranslateResult.Done("T(toque.)"), tap.await())
    }

    @Test fun tocarUnoQueEsperabaEnLaPretraduccionLoAdelanta() = runTest(dispatcher) {
        val engine = FakeEngine(gate = true)
        val s = service(engine)
        s.prefetch((1..3).map { req("p$it.", Priority.PREFETCH) })
        runCurrent() // p1 en curso; p2 y p3 esperan
        val tap = async { s.translate(req("p3.", Priority.TAP)) }
        runCurrent(); engine.releaseAll(); advanceUntilIdle()
        assertEquals(listOf("p1.", "p3.", "p2."), engine.translatedTexts)
        assertEquals(TranslateResult.Done("T(p3.)"), tap.await())
    }

    @Test fun nuncaDosTraduccionesALaVez() = runTest(dispatcher) {
        val engine = FakeEngine(workMillis = 100) // cada traducción tarda: si dos se solaparan, se vería
        val s = service(engine)
        s.prefetch((1..5).map { req("pre$it.", Priority.PREFETCH) })
        val taps = (1..5).map { i -> async { s.translate(req("tap$i.", Priority.TAP)) } }
        advanceUntilIdle()
        assertEquals(1, engine.maxConcurrent)
        assertEquals(10, engine.translatedTexts.size)
        taps.forEachIndexed { i, t -> assertEquals(TranslateResult.Done("T(tap${i + 1}.)"), t.await()) }
    }

    @Test fun delCacheNoTocaElMotor() = runTest(dispatcher) {
        val engine = FakeEngine()
        dao.rows[key("Hola.")] = TranslationEntity(key("Hola."), "Hola (guardado)", 1L)
        val s = service(engine)
        assertEquals(TranslateResult.Done("Hola (guardado)"), s.translate(req("  Hola.\n ")))
        assertEquals(0, engine.loadCount)
        assertTrue(engine.translatedTexts.isEmpty())
    }

    @Test fun guardaElParrafoCompletoConLaHuellaDelModelo() = runTest(dispatcher) {
        val s = service()
        assertEquals(TranslateResult.Done("T(Uno.) T(Dos.)"), s.translate(req("Uno.  Dos.")))
        assertEquals(TranslationEntity(key("Uno. Dos."), "T(Uno.) T(Dos.)", 42L), dao.rows[key("Uno. Dos.")])
        assertEquals(1, dao.rows.size)
    }

    @Test fun cachedDevuelveSoloLoGuardadoPorTextoNormalizado() = runTest(dispatcher) {
        val engine = FakeEngine()
        dao.rows[key("Hola.")] = TranslationEntity(key("Hola."), "Hola (guardado)", 1L)
        val s = service(engine)
        assertEquals(mapOf("Hola." to "Hola (guardado)"), s.cached(pair, listOf(" Hola. ", "Nada.", "   ")))
        assertEquals(0, engine.loadCount)
    }

    @Test fun noTraduceDosVecesLoMismo() = runTest(dispatcher) {
        val engine = FakeEngine()
        val s = service(engine)
        val pre = async { s.translate(req("A.", Priority.PREFETCH)) }
        val tap = async { s.translate(req("A.", Priority.TAP)) }
        s.prefetch(listOf(req("B.", Priority.PREFETCH)))
        val tapB = async { s.translate(req("B.", Priority.TAP)) }
        advanceUntilIdle()
        assertEquals(listOf("A.", "B."), engine.translatedTexts)
        assertEquals(TranslateResult.Done("T(A.)"), pre.await())
        assertEquals(TranslateResult.Done("T(A.)"), tap.await())
        assertEquals(TranslateResult.Done("T(B.)"), tapB.await())
    }

    @Test fun fallaUnaOracionNoGuardaNada() = runTest(dispatcher) {
        val engine = FakeEngine(failOn = "Dos.") // lanza en la 2.ª oración
        val s = service(engine)
        assertEquals(TranslateResult.ParagraphFailed, s.translate(req("Uno. Dos. Tres.")))
        assertEquals(listOf("Uno."), engine.translatedTexts)
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun sinModeloDiceCualDescargar() = runTest(dispatcher) {
        val engine = FakeEngine()
        val provider = FakeEngineProvider(opus = engine, installedEngines = emptyMap(), ramBytes = 8L * 1024 * 1024 * 1024)
        val s = service(engine, provider)
        assertEquals(TranslateResult.MissingModel(EngineId.OPUS), s.translate(req("Hola.")))
        provider.ramBytes = 2L * 1024 * 1024 * 1024
        assertEquals(TranslateResult.MissingModel(EngineId.FIREFOX), s.translate(req("Hola.")))
        assertEquals(0, engine.loadCount)
        assertEquals(0, provider.firefox.loadCount)
    }

    @Test fun motorQueNoCargaEsEngineFailedYReintentarVuelveACargar() = runTest(dispatcher) {
        val engine = FakeEngine(failLoads = 1)
        val s = service(engine)
        assertEquals(TranslateResult.EngineFailed, s.translate(req("Hola.")))
        assertEquals(1, engine.loadCount)
        assertFalse(s.engineReady(pair))
        assertEquals(TranslateResult.Done("T(Hola.)"), s.translate(req("Hola.")))
        assertEquals(2, engine.loadCount)
    }

    @Test fun sinMemoriaEsEngineFailedSinCargar() = runTest(dispatcher) {
        val engine = FakeEngine()
        val s = service(engine, FakeEngineProvider(opus = engine, memoryOk = false))
        assertEquals(TranslateResult.EngineFailed, s.translate(req("Hola.")))
        assertEquals(0, engine.loadCount)
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun vuelveAMirarLoInstaladoAlCargar() = runTest(dispatcher) {
        val provider = FakeEngineProvider()
        val s = service(provider.opus, provider)
        assertEquals(TranslateResult.Done("T(A.)"), s.translate(req("A.")))
        s.release(); runCurrent()
        provider.installedEngines = mapOf(EngineId.FIREFOX to FIREFOX_TAG) // se borró OPUS y se instaló Firefox
        assertEquals(TranslateResult.Done("T(B.)"), s.translate(req("B.")))
        assertEquals(1, provider.opus.loadCount)
        assertEquals(listOf("A."), provider.opus.translatedTexts) // nunca usa el motor que ya no está
        assertEquals(1, provider.firefox.loadCount)
        assertEquals(listOf("B."), provider.firefox.translatedTexts)
        assertEquals("T(B.)", dao.rows[key("B.", FIREFOX_TAG)]?.translation)
    }

    @Test fun cancelaPretraduccionDeOtroRecurso() = runTest(dispatcher) {
        val engine = FakeEngine(gate = true)
        val s = service(engine)
        s.prefetch(listOf("a.", "b.", "c.").map { req(it, Priority.PREFETCH, resource = "c1") })
        runCurrent() // "a." en curso
        s.cancelPrefetchExcept("c3")
        engine.releaseAll(); advanceUntilIdle()
        assertEquals(listOf("a."), engine.translatedTexts) // la que estaba en curso termina
        assertEquals(setOf(key("a.")), dao.rows.keys) // y se guarda
    }

    @Test fun cancelaTambienLaPretraduccionQueAunNoEntraALaFila() = runTest(dispatcher) {
        val engine = FakeEngine()
        val s = service(engine)
        s.prefetch(listOf(req("a.", Priority.PREFETCH, "c1"), req("b.", Priority.PREFETCH, "c3")))
        s.cancelPrefetchExcept("c3") // antes de que el pedido llegue a la fila
        s.prefetch(listOf(req("c.", Priority.PREFETCH, "c1"))) // pedida después: vale
        advanceUntilIdle()
        assertEquals(listOf("b.", "c."), engine.translatedTexts)
    }

    @Test fun engineReadySoloConMotorCargado() = runTest(dispatcher) {
        val s = service()
        assertFalse(s.engineReady(pair))
        assertEquals(TranslateResult.Done("T(A.)"), s.translate(req("A.")))
        assertTrue(s.engineReady(pair))
        assertFalse(s.engineReady(LanguagePair("es", "en")))
        s.release(); runCurrent()
        assertFalse(s.engineReady(pair))
    }

    @Test fun seDescargaTrasDosMinutosSinUso() = runTest(dispatcher) {
        val engine = FakeEngine()
        val s = service(engine)
        assertEquals(TranslateResult.Done("T(A.)"), s.translate(req("A.")))
        advanceTimeBy(119_999)
        assertEquals(0, engine.unloadCount)
        advanceTimeBy(2)
        assertEquals(1, engine.unloadCount)
        assertFalse(s.engineReady(pair))
    }

    @Test fun releaseCancelaLaFilaYDescarga() = runTest(dispatcher) {
        val engine = FakeEngine(gate = true)
        val s = service(engine)
        val running = async { s.translate(req("A.")) }
        val queued = async { s.translate(req("B.")) }
        runCurrent() // "A." en curso, "B." en la fila
        s.release(); runCurrent()
        assertFailsWith<CancellationException> { running.await() }
        assertFailsWith<CancellationException> { queued.await() }
        assertEquals(1, engine.unloadCount)
        engine.releaseAll(); advanceUntilIdle()
        assertTrue(engine.translatedTexts.isEmpty())
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun textoVacioNoVaAlMotor() = runTest(dispatcher) {
        val engine = FakeEngine()
        val s = service(engine)
        assertEquals(TranslateResult.Done(""), s.translate(req("  \n ")))
        assertEquals(0, engine.loadCount)
        assertTrue(engine.translatedTexts.isEmpty())
    }

    // Seguridad 3b (MEDIO): un párrafo enorme ocuparía el motor minutos; se responde enseguida, sin fila ni motor.
    @Test fun parrafoDemasiadoLargoRespondeEnseguidaSinMotor() = runTest(dispatcher) {
        val engine = FakeEngine()
        val provider = FakeEngineProvider(opus = engine)
        val s = service(engine, provider)
        val huge = "Ab. ".repeat(6_000) // 23 999 caracteres normalizados
        assertEquals(TranslateResult.TooLong, s.translate(req(huge)))
        s.prefetch(listOf(req(huge, Priority.PREFETCH)))
        advanceUntilIdle()
        assertEquals(0, provider.installedCalls) // ni siquiera mira lo instalado
        assertEquals(0, engine.loadCount)
        assertTrue(engine.translatedTexts.isEmpty())
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun elTopeSeMideSobreElTextoNormalizado() = runTest(dispatcher) {
        val s = service()
        assertEquals(TranslateResult.Done("T(a b)"), s.translate(req("a" + " ".repeat(30_000) + "b")))
        // Justo en el tope todavía se traduce.
        assertTrue(s.translate(req("a".repeat(20_000))) is TranslateResult.Done)
    }

    // Revisión final 3b (I1): un Error (no Exception) del motor no deja la fila muerta para el resto de la sesión.
    @Test fun unErrorAlCargarDaFalloYElSiguientePedidoTraduce() = runTest(dispatcher) {
        val engine = FakeEngine().apply { loadError = ExceptionInInitializerError("biblioteca nativa") }
        val s = service(engine)
        assertEquals(TranslateResult.EngineFailed, s.translate(req("A.")))
        assertEquals(TranslateResult.Done("T(A.)"), s.translate(req("A.")))
    }

    @Test fun unErrorAlTraducirDaFalloDelParrafoYElSiguienteTraduce() = runTest(dispatcher) {
        val engine = FakeEngine().apply { translateError = OutOfMemoryError("prueba") }
        val s = service(engine)
        assertEquals(TranslateResult.ParagraphFailed, s.translate(req("A.")))
        assertEquals(TranslateResult.Done("T(B.)"), s.translate(req("B.")))
        assertTrue(dao.rows.keys.none { it == key("A.") })
    }

    // --- Borrar las traducciones guardadas ---

    @Test fun borrarConTraduccionEnCursoNoDejaFilas() = runTest(dispatcher) {
        val engine = FakeEngine(gate = true)
        val s = service(engine)
        dao.rows[key("Vieja.")] = TranslationEntity(key("Vieja."), "T(Vieja.)", 1L)
        s.prefetch(listOf(req("pre1.", Priority.PREFETCH), req("pre2.", Priority.PREFETCH)))
        val tap = async { runCatching { s.translate(req("toque.", Priority.TAP)) } }
        runCurrent() // una traducción en curso, detenida en la compuerta
        s.clearCache()
        engine.releaseAll()
        advanceUntilIdle()
        assertTrue(dao.rows.isEmpty(), "no debe quedar ninguna fila")
        assertTrue(tap.await().exceptionOrNull() is CancellationException, "el pedido en curso recibe cancelación")
        assertEquals(0L, s.cacheBytes())
    }

    // I2: secure_delete pone en ceros las páginas del .db, pero las copias viejas siguen en el WAL hasta un checkpoint.
    @Test fun borrarVaciaElWalDespuesDeBorrar() = runTest(dispatcher) {
        dao.rows["k"] = TranslationEntity("k", "texto", 1L)
        service().clearCache()
        advanceUntilIdle()
        assertEquals(listOf("deleteAll", "PRAGMA wal_checkpoint(TRUNCATE)"), dao.maintenance)
    }

    @Test fun borrarConLaFilaVaciaNoFalla() = runTest(dispatcher) {
        val s = service()
        s.clearCache()
        advanceUntilIdle()
        assertEquals(0L, s.cacheBytes())
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun despuesDeBorrarSeSigueTraduciendo() = runTest(dispatcher) {
        val s = service()
        assertEquals(TranslateResult.Done("T(A.)"), s.translate(req("A.")))
        s.clearCache()
        advanceUntilIdle()
        assertEquals(TranslateResult.Done("T(A.)"), s.translate(req("A.")))
        assertEquals(1, dao.rows.size)
    }

    @Test fun cacheBytesDaElTamanoAproximadoDelDao() = runTest(dispatcher) {
        dao.rows["ab"] = TranslationEntity("ab", "cde", 1L)
        assertEquals(10L, service().cacheBytes())
    }

    // Un motor nativo bloquea el hilo sin suspender: cancelar no lo detiene hasta que vuelve.
    @Test fun borrarEsperaAUnMotorQueBloqueaSinSuspender() {
        val started = java.util.concurrent.CountDownLatch(1)
        val latch = java.util.concurrent.CountDownLatch(1)
        val blocking = object : io.github.diegobr4nd.lectorbilingue.engine.api.TranslationEngine {
            override val id = EngineId.OPUS.wire
            override suspend fun load(pair: LanguagePair, config: io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig) {}
            override suspend fun translate(sentences: List<String>): List<String> {
                started.countDown()
                latch.await() // bloquea el hilo (sin suspender), como el código nativo
                return sentences.map { "T($it)" }
            }
            override fun unload() {}
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val real = executor.asCoroutineDispatcher()
        val realDao = FakeTranslationDao()
        val s = TranslationService(
            FakeEngineProvider(opus = FakeEngine()).let { p ->
                object : EngineProvider by p {
                    override fun engine(id: EngineId) = blocking
                }
            },
            realDao, clock = { 42L }, worker = real, scope = translationScope(real),
        )
        try {
            kotlinx.coroutines.runBlocking {
                val tap = async(kotlinx.coroutines.Dispatchers.Default) { runCatching { s.translate(req("Toque.")) } }
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val clear = async(kotlinx.coroutines.Dispatchers.Default) { s.clearCache() }
                kotlinx.coroutines.delay(300) // clearCache ya esperando al bucle bloqueado
                assertFalse(clear.isCompleted, "debe esperar a que el motor vuelva")
                latch.countDown()
                clear.await()
                tap.await()
                assertTrue(realDao.rows.isEmpty(), "el guardado del motor bloqueado cayó antes del borrado")
            }
        } finally {
            executor.shutdownNow()
        }
    }

    // Lo que hace la prueba de tiempos en el teléfono: soltar y pedir enseguida, sin esperar la descarga.
    @Test fun pedirJustoDespuesDeSoltarVuelveACargarYTraduce() = runTest(dispatcher) {
        val engine = FakeEngine()
        val s = service(engine)
        assertEquals(TranslateResult.Done("T(A.)"), s.translate(req("A.")))
        s.release()
        assertEquals(TranslateResult.Done("T(B.)"), s.translate(req("B.")))
        assertEquals(1, engine.unloadCount)
        assertEquals(2, engine.loadCount)
    }

    // Con un hilo real y un motor que bloquea (como el nativo): soltar a mitad y pedir enseguida no pierde el pedido.
    @Test fun pedirTrasSoltarConElMotorBloqueadoTraduceAlVolver() {
        val started = java.util.concurrent.CountDownLatch(1)
        val latch = java.util.concurrent.CountDownLatch(1)
        var calls = 0
        val blocking = object : io.github.diegobr4nd.lectorbilingue.engine.api.TranslationEngine {
            override val id = EngineId.OPUS.wire
            override suspend fun load(pair: LanguagePair, config: io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig) {}
            override suspend fun translate(sentences: List<String>): List<String> {
                if (calls++ == 0) {
                    started.countDown()
                    latch.await()
                }
                return sentences.map { "T($it)" }
            }
            override fun unload() {}
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val real = executor.asCoroutineDispatcher()
        val s = TranslationService(
            FakeEngineProvider(opus = FakeEngine()).let { p ->
                object : EngineProvider by p {
                    override fun engine(id: EngineId) = blocking
                }
            },
            FakeTranslationDao(), clock = { 42L }, worker = real, scope = translationScope(real),
        )
        try {
            kotlinx.coroutines.runBlocking {
                val first = async(kotlinx.coroutines.Dispatchers.Default) { runCatching { s.translate(req("Uno.")) } }
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
                s.release()
                val second = async(kotlinx.coroutines.Dispatchers.Default) { s.translate(req("Dos.")) }
                kotlinx.coroutines.delay(200)
                latch.countDown()
                assertEquals(TranslateResult.Done("T(Dos.)"), kotlinx.coroutines.withTimeout(10_000) { second.await() })
                assertTrue(first.await().isFailure) // el primero se canceló
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
