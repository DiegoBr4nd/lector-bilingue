package io.github.diegobr4nd.lectorbilingue.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.diegobr4nd.lectorbilingue.books.BookFiles
import io.github.diegobr4nd.lectorbilingue.books.BookImporter
import io.github.diegobr4nd.lectorbilingue.books.BookMetadata
import io.github.diegobr4nd.lectorbilingue.books.BookRepository
import io.github.diegobr4nd.lectorbilingue.books.MetadataRead
import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import io.github.diegobr4nd.lectorbilingue.books.db.TranslationEntity
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.FakeEngine
import io.github.diegobr4nd.lectorbilingue.data.FakeEngineProvider
import io.github.diegobr4nd.lectorbilingue.data.FakeTranslationDao
import io.github.diegobr4nd.lectorbilingue.data.MemoryPrefs
import io.github.diegobr4nd.lectorbilingue.data.PageTheme
import io.github.diegobr4nd.lectorbilingue.data.ReadingFont
import io.github.diegobr4nd.lectorbilingue.data.ReadingMode
import io.github.diegobr4nd.lectorbilingue.data.ReadingSettings
import io.github.diegobr4nd.lectorbilingue.data.TipId
import io.github.diegobr4nd.lectorbilingue.data.TranslationRules
import io.github.diegobr4nd.lectorbilingue.data.TranslationService
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.ui.tips.TipEvent
import io.github.diegobr4nd.lectorbilingue.ui.library.FakeBookDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val id = "123e4567-e89b-12d3-a456-426614174000"
    private val dao = FakeBookDao()
    private val provider = FakeEngineProvider()
    private val engine: FakeEngine get() = provider.opus
    private val cache = FakeTranslationDao()
    private val enEs = LanguagePair("en", "es")
    private val esEn = LanguagePair("es", "en")
    private lateinit var service: TranslationService
    private val prefs = MemoryPrefs()
    private val settings = AppSettings(prefs)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** El VM con un [TranslationService] real (hilo de prueba) sobre el motor falso. [engine] reemplaza al de [provider]. */
    private suspend fun TestScope.viewModel(
        engine: FakeEngine? = null,
        languages: List<String> = listOf("en"),
        direction: String? = null,
        tipsIo: CoroutineDispatcher = dispatcher,
    ): ReaderViewModel {
        val files = BookFiles(tmp.root)
        dao.insert(BookEntity(id, "T", null, null, 1, null, 0f, null, direction))
        val importer = BookImporter(files, dao, readMetadata = { MetadataRead.Ok(BookMetadata("T", null, null)) }, saveCover = { _, _ -> })
        val p = if (engine == null) provider else FakeEngineProvider(opus = engine, installedEngines = provider.installedEngines)
        service = TranslationService(p, cache, clock = { 1L }, worker = dispatcher, scope = this) // no backgroundScope: advanceUntilIdle no espera esas tareas
        return ReaderViewModel(id, BookRepository(dao, files, importer), service, languages, settings, tipsIo = tipsIo)
    }

    /** El VM y la lista de todo lo que emite `cardOps` (se suscribe enseguida: no se pierde nada). */
    private suspend fun TestScope.vmWithOps(engine: FakeEngine? = null): Pair<ReaderViewModel, List<CardOp>> {
        val vm = viewModel(engine)
        val ops = mutableListOf<CardOp>()
        // Sin hilo propio: cada op llega a la lista en el momento en que se emite.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.cardOps.toList(ops) }
        return vm to ops
    }

    private fun p(i: Int, t: String) = PageParagraph(i, t)

    @Test fun `guarda la posicion un segundo despues del ultimo cambio`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onPosition(ReaderPosition("{\"a\":1}", 0.2, "Uno"))
        advanceTimeBy(500)
        vm.onPosition(ReaderPosition("{\"a\":2}", 0.3, "Uno"))
        advanceTimeBy(900); runCurrent()
        assertNull(dao.get(id)!!.locator) // Aún dentro del retraso.
        advanceTimeBy(200); runCurrent()
        assertEquals("{\"a\":2}", dao.get(id)!!.locator)
        assertEquals(0.3f, dao.get(id)!!.progress)
        assertEquals(PositionLabel("Uno", 30), vm.label.value)
    }

    @Test fun `al salir guarda enseguida`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onPosition(ReaderPosition("{\"b\":1}", 0.5, null))
        vm.flush(); runCurrent()
        assertEquals("{\"b\":1}", dao.get(id)!!.locator)
    }

    @Test fun `al salir sin posicion no escribe nada`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.flush(); advanceUntilIdle()
        assertNull(dao.get(id)!!.locator)
    }

    @Test fun `barras por arrastre, final del libro y TalkBack`() = runTest(dispatcher) {
        val vm = viewModel()
        assertTrue(vm.barsVisible.value) // Al abrir se ven.
        vm.onDrag(-30.0)
        assertFalse(vm.barsVisible.value)
        vm.onDrag(30.0)
        assertTrue(vm.barsVisible.value)
        vm.onDrag(-30.0)
        vm.onPosition(ReaderPosition("{}", 1.0, null)) // Final del libro.
        assertTrue(vm.barsVisible.value)
        vm.onPosition(ReaderPosition("{}", 0.5, null))
        vm.onDrag(-30.0)
        assertFalse(vm.barsVisible.value)
        vm.setTouchExploration(true)
        assertTrue(vm.barsVisible.value)
        vm.onDrag(-30.0)
        assertTrue(vm.barsVisible.value)
        advanceUntilIdle()
    }

    @Test fun tocarAbreConEsqueletoYLuegoTexto() = runTest(dispatcher) {
        val (vm, ops) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        runCurrent(); assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Preparing), ops.first()) // motor aún sin cargar
        advanceUntilIdle(); assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hi.)")), ops.last())
    }

    @Test fun conElMotorCargadoElToqueMuestraTraduciendo() = runTest(dispatcher) {
        val (vm, ops) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Uno.")))
        advanceTimeBy(1_000); runCurrent() // el motor sigue cargado (se descarga a los 2 min sin uso)
        vm.onTap("c1.xhtml", listOf(p(1, "Dos.")))
        runCurrent()
        assertEquals(CardOp.Show("c1.xhtml", 1, CardState.Skeleton), ops.first { it is CardOp.Show && it.index == 1 })
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 1, CardState.Text("T(Dos.)")), ops.last())
    }

    @Test fun tocarSinParrafoNoHaceNada() = runTest(dispatcher) {
        val (vm, ops) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", emptyList())
        advanceUntilIdle()
        assertTrue(ops.isEmpty())
        assertEquals(0, engine.loadCount)
    }

    @Test fun tocarOtraVezCierra() = runTest(dispatcher) {
        val (vm, ops) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hi.)")), ops.last())
        vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceUntilIdle()
        assertEquals(CardOp.Hide("c1.xhtml", 0), ops.last())
        // Olvidada: volver al recurso no la reinserta.
        val before = ops.size
        vm.onResourceShown("c1.xhtml"); advanceUntilIdle()
        assertEquals(before, ops.size)
    }

    @Test fun cerrarAntesDeQueLlegueNoReabre() = runTest(dispatcher) {
        val g = FakeEngine(gate = true)
        val (vm, ops) = vmWithOps(g)
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        runCurrent()
        vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        runCurrent()
        assertEquals(CardOp.Hide("c1.xhtml", 0), ops.last())
        g.releaseAll(); advanceUntilIdle()
        val afterHide = ops.drop(ops.indexOf(CardOp.Hide("c1.xhtml", 0)) + 1)
        assertTrue(afterHide.none { it is CardOp.Show }, "reabrió: $afterHide")
        assertEquals(listOf("Hi."), g.translatedTexts) // la traducción terminó igual
    }

    @Test fun noInsertaEnOtroRecurso() = runTest(dispatcher) {
        val g = FakeEngine(gate = true)
        val (vm, ops) = vmWithOps(g)
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        runCurrent()
        vm.onResourceShown("c3.xhtml")
        g.releaseAll(); advanceUntilIdle()
        assertTrue(ops.none { it is CardOp.Show && it.resource == "c1.xhtml" && it.card is CardState.Text }, "insertó: $ops")
        assertEquals(mapOf("Hi." to "T(Hi.)"), service.cached(enEs, listOf("Hi.")))
    }

    @Test fun pretraduceLosSiguientesSinTarjeta() = runTest(dispatcher) {
        val (vm, _) = vmWithOps()
        val hit = (0..5).map { p(10 + it, "P$it.") }
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", hit)
        advanceUntilIdle()
        assertEquals(listOf("P0.", "P1.", "P2.", "P3.", "P4.", "P5."), engine.translatedTexts)
        // Tocar el siguiente: ya está en caché, el motor no vuelve a trabajar.
        vm.onTap("c1.xhtml", hit.drop(1))
        advanceUntilIdle()
        assertEquals(6, engine.translatedTexts.size)
    }

    @Test fun noPretraduceLosQueYaTienenTarjeta() = runTest(dispatcher) {
        val g = FakeEngine(gate = true)
        val (vm, _) = vmWithOps(g)
        vm.onResourceShown("c1.xhtml")
        vm.onTap("c1.xhtml", listOf(p(1, "B.")))
        runCurrent()
        vm.onTap("c1.xhtml", listOf(p(0, "A."), p(1, "B."), p(2, "C.")))
        runCurrent(); g.releaseAll(); advanceUntilIdle()
        assertEquals(listOf("B.", "A.", "C."), g.translatedTexts) // B. una sola vez
    }

    @Test fun reinsertaAlVolverAlRecurso() = runTest(dispatcher) {
        val (vm, ops) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceUntilIdle()
        vm.onResourceShown("c3.xhtml"); advanceUntilIdle()
        assertTrue(ops.none { it is CardOp.Show && it.resource == "c3.xhtml" })
        vm.onResourceShown("c1.xhtml"); advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hi.)")), ops.last())
    }

    @Test fun volverTrasCambiarAntesDeQueLlegueMuestraElTexto() = runTest(dispatcher) {
        val g = FakeEngine(gate = true)
        val (vm, ops) = vmWithOps(g)
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        runCurrent()
        vm.onResourceShown("c3.xhtml")
        g.releaseAll(); advanceUntilIdle()
        vm.onResourceShown("c1.xhtml"); advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hi.)")), ops.last())
    }

    @Test fun cambiarDireccionGuardaYCierraTarjetas() = runTest(dispatcher) {
        val (vm, ops) = vmWithOps()
        advanceUntilIdle()
        assertEquals(enEs, vm.direction.value)
        vm.onResourceShown("c1.xhtml")
        vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        vm.onTap("c1.xhtml", listOf(p(2, "Bye.")))
        advanceUntilIdle()
        vm.setDirection(esEn)
        advanceUntilIdle()
        assertEquals("es-en", dao.get(id)!!.direction)
        assertEquals(esEn, vm.direction.value)
        assertEquals(setOf(CardOp.Hide("c1.xhtml", 0), CardOp.Hide("c1.xhtml", 2)), ops.takeLast(2).toSet())
        // Cerradas: tocar otra vez abre, ya con la dirección nueva.
        vm.onTap("c1.xhtml", listOf(p(0, "Hola.")))
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hola.)")), ops.last())
        assertEquals(esEn, engine.loadedPairs.last())
    }

    @Test fun laDireccionInicialSaleDelLibroODelIdiomaDelEpub() = runTest(dispatcher) {
        val guardada = viewModel(direction = "es-en", languages = listOf("en"))
        advanceUntilIdle()
        assertEquals(esEn, guardada.direction.value)
        dao.delete(id)
        val automatica = viewModel(languages = listOf("es-MX"))
        advanceUntilIdle()
        assertEquals(esEn, automatica.direction.value)
    }

    @Test fun sinModeloMuestraLaTarjetaDeDescarga() = runTest(dispatcher) {
        provider.installedEngines = emptyMap()
        val (vm, ops) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.MissingModel(EngineId.OPUS)), ops.last())
        assertEquals(0, engine.loadCount)
    }

    @Test fun alCerrarIdiomasReintentaLasTarjetasSinModelo() = runTest(dispatcher) {
        provider.installedEngines = emptyMap()
        val (vm, ops) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.MissingModel(EngineId.OPUS)), ops.last())
        provider.installedEngines = mapOf(EngineId.OPUS to FakeEngineProvider.OPUS_TAG) // se descargó en Idiomas
        vm.onLanguagesClosed()
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hi.)")), ops.last())
    }

    @Test fun alCerrarIdiomasSueltaElMotorParaUsarElElegido() = runTest(dispatcher) {
        val (vm, _) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, engine.unloadCount)
        vm.onLanguagesClosed()
        runCurrent()
        assertEquals(1, engine.unloadCount)
    }

    @Test fun reintentarTrasFallo() = runTest(dispatcher) {
        engine.failOn = "Hi."
        val (vm, ops) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Failed(prepare = false)), ops.last())
        engine.failOn = null
        vm.retry("c1.xhtml", 0)
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hi.)")), ops.last())
    }

    @Test fun motorQueNoCargaMuestraErrorDePreparacionYSeReintenta() = runTest(dispatcher) {
        engine.failLoads = 1
        val (vm, ops) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Failed(prepare = true)), ops.last())
        vm.retry("c1.xhtml", 0)
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hi.)")), ops.last())
    }

    // Juan: tocar "Reintentar" cuando el motor sigue sin poder prepararse no se veía (el fallo volvía en el acto).
    @Test fun reintentarQueVuelveAFallarMuestraPreparandoUnMomento() = runTest(dispatcher) {
        provider.memoryOk = false
        val (vm, ops) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceUntilIdle()
        val failed = CardOp.Show("c1.xhtml", 0, CardState.Failed(prepare = true))
        assertEquals(failed, ops.last())
        vm.retry("c1.xhtml", 0)
        runCurrent()
        val preparing = CardOp.Show("c1.xhtml", 0, CardState.Preparing)
        assertEquals(preparing, ops.last())
        advanceTimeBy(ReaderViewModel.RETRY_MIN_MILLIS - 1)
        assertEquals(preparing, ops.last(), "Preparando… se ve un momento antes del fallo")
        advanceUntilIdle()
        assertEquals(failed, ops.last())
    }

    // Reintentar que sí traduce no espera de más.
    @Test fun reintentarQueTraduceNoEspera() = runTest(dispatcher) {
        engine.failLoads = 1
        val (vm, ops) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceUntilIdle()
        vm.retry("c1.xhtml", 0)
        runCurrent()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hi.)")), ops.last())
    }

    @Test fun reintentarSinTarjetaOConTextoNoHaceNada() = runTest(dispatcher) {
        val (vm, ops) = vmWithOps()
        vm.onResourceShown("c1.xhtml")
        vm.retry("c1.xhtml", 0)
        advanceUntilIdle()
        assertTrue(ops.isEmpty())
        vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceUntilIdle()
        val before = ops.size
        vm.retry("c1.xhtml", 0)
        advanceUntilIdle()
        assertEquals(before, ops.size)
    }

    // Fix 1: tras un fallo del motor, la pretraducción no vuelve a intentar cargarlo una vez por párrafo.
    @Test fun sinTraduccionNoPretraduce() = runTest(dispatcher) {
        engine.failLoads = 1
        val (vm, ops) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "A."), p(1, "B."), p(2, "C.")))
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Failed(prepare = true)), ops.last())
        assertEquals(1, engine.loadCount)
        assertTrue(engine.translatedTexts.isEmpty())
    }

    @Test fun sinModeloNoPretraduce() = runTest(dispatcher) {
        provider.installedEngines = emptyMap()
        val (vm, _) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "A."), p(1, "B.")))
        advanceUntilIdle()
        val calls = provider.installedCalls
        provider.installedEngines = mapOf(EngineId.OPUS to FakeEngineProvider.OPUS_TAG)
        advanceUntilIdle()
        assertEquals(calls, provider.installedCalls) // nada quedó en la fila
        assertTrue(engine.translatedTexts.isEmpty())
    }

    // Fix 3: elegir la misma dirección antes de leer el libro no tapa la guardada.
    @Test fun elegirLaMismaDireccionNoTapaLaGuardada() = runTest(dispatcher) {
        val vm = viewModel(direction = "es-en", languages = listOf("en"))
        assertEquals(enEs, vm.direction.value) // aún no se leyó el libro
        vm.setDirection(enEs) // igual a la actual: no hace nada
        advanceUntilIdle()
        assertEquals(esEn, vm.direction.value)
        assertEquals("es-en", dao.get(id)!!.direction)
    }

    // Fix 2: si la base falla al guardar la dirección, el Lector sigue (sin caerse) con la dirección elegida.
    @Test fun guardarLaDireccionQueFallaNoTumbaElLector() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        dao.failSetDirection = true
        vm.setDirection(esEn)
        advanceUntilIdle()
        assertEquals(esEn, vm.direction.value)
        assertNull(dao.get(id)!!.direction)
    }

    // Fix 5a: soltar el motor con una traducción en curso no deja la tarjeta en "Traduciendo…".
    @Test fun cerrarIdiomasConUnaTraduccionEnCursoLaTermina() = runTest(dispatcher) {
        val g = FakeEngine(gate = true)
        val (vm, ops) = vmWithOps(g)
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        runCurrent()
        vm.onLanguagesClosed()
        runCurrent(); g.releaseAll(); advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hi.)")), ops.last())
    }

    // Fix 5b: cambiar de dirección con una traducción en curso: el resultado viejo no aparece.
    @Test fun cambiarDireccionEnCursoNoMuestraLaVieja() = runTest(dispatcher) {
        val g = FakeEngine(gate = true)
        val (vm, ops) = vmWithOps(g)
        advanceUntilIdle()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        runCurrent()
        vm.setDirection(esEn)
        runCurrent(); g.releaseAll(); advanceUntilIdle()
        assertEquals(CardOp.Hide("c1.xhtml", 0), ops.last())
        assertTrue(ops.none { it is CardOp.Show && it.card is CardState.Text }, "$ops")
    }

    @Test fun alLimpiarseSueltaElMotor() = runTest(dispatcher) {
        val (vm, _) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, engine.loadCount); assertEquals(0, engine.unloadCount)
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        }
        ViewModelProvider(store, factory)[ReaderViewModel::class.java]
        store.clear()
        runCurrent()
        assertEquals(1, engine.unloadCount)
    }

    // La pantalla resuelve un toque sobre la tarjeta con este estado (reintentar, abrir Idiomas o cerrar).
    @Test fun estadoDeUnaTarjetaAbierta() = runTest(dispatcher) {
        provider.installedEngines = emptyMap()
        val (vm, _) = vmWithOps()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceUntilIdle()
        assertEquals(CardState.MissingModel(EngineId.OPUS), vm.cardState("c1.xhtml", 0))
        assertNull(vm.cardState("c1.xhtml", 1))
        assertNull(vm.cardState("c3.xhtml", 0))
        vm.onTap("c1.xhtml", listOf(p(0, "")))
        assertNull(vm.cardState("c1.xhtml", 0)) // cerrada
    }

    /** El VM, sus ops y lo que pide anunciar a TalkBack (Ruling L). */
    private suspend fun TestScope.vmWithAnnouncements(): Triple<ReaderViewModel, List<CardOp>, List<CardOp.Show>> {
        val (vm, ops) = vmWithOps()
        val said = mutableListOf<CardOp.Show>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.announcements.toList(said) }
        return Triple(vm, ops, said)
    }

    // Ruling L: se anuncia el resultado de un toque; la reinserción (volver al capítulo o al frente) no.
    @Test fun anunciaElResultadoDelToqueYNoLaReinsercion() = runTest(dispatcher) {
        val (vm, _, said) = vmWithAnnouncements()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi."), p(1, "Dos.")))
        advanceUntilIdle()
        assertEquals(listOf(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hi.)"))), said)
        vm.onResourceShown("c3.xhtml"); vm.onResourceShown("c1.xhtml")
        advanceUntilIdle()
        assertEquals(1, said.size) // ni la reinserción ni la pretraducción del párrafo 1
    }

    @Test fun anunciaElFalloYElReintentoPeroNoAlCerrarIdiomas() = runTest(dispatcher) {
        engine.failOn = "Hi."
        val (vm, _, said) = vmWithAnnouncements()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Failed(prepare = false)), said.single())
        engine.failOn = null
        vm.retry("c1.xhtml", 0) // también es un toque (sobre la tarjeta)
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hi.)")), said.last())
        assertEquals(2, said.size)
    }

    @Test fun sinModeloAnunciaYAlCerrarIdiomasNoVuelveAAnunciar() = runTest(dispatcher) {
        provider.installedEngines = emptyMap()
        val (vm, ops, said) = vmWithAnnouncements()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.MissingModel(EngineId.OPUS)), said.single())
        provider.installedEngines = mapOf(EngineId.OPUS to FakeEngineProvider.OPUS_TAG)
        vm.onLanguagesClosed()
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hi.)")), ops.last())
        assertEquals(1, said.size)
    }

    @Test fun resultadoQueLlegaEnOtroCapituloNoSeAnuncia() = runTest(dispatcher) {
        val (vm, _, said) = vmWithAnnouncements()
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Hi.")))
        vm.onResourceShown("c3.xhtml")
        advanceUntilIdle()
        vm.onResourceShown("c1.xhtml")
        advanceUntilIdle()
        assertTrue(said.isEmpty())
    }

    // Seguridad 3b (MEDIO): el párrafo enorme no ocupa el motor, no se reintenta y el siguiente se traduce.
    @Test fun parrafoDemasiadoLargoMuestraSuTarjetaSinReintento() = runTest(dispatcher) {
        val (vm, ops) = vmWithOps()
        val huge = "Ab. ".repeat(6_000)
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, huge), p(1, "B.")))
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 0, CardState.TooLong), ops.last())
        assertEquals(0, engine.loadCount)
        val before = ops.size
        vm.retry("c1.xhtml", 0); advanceUntilIdle()
        assertEquals(before, ops.size)
        vm.onTap("c1.xhtml", listOf(p(1, "B.")))
        advanceUntilIdle()
        assertEquals(CardOp.Show("c1.xhtml", 1, CardState.Text("T(B.)")), ops.last())
    }

    @Test fun noPretraduceParrafosLargos() = runTest(dispatcher) {
        val (vm, _) = vmWithOps()
        vm.onResourceShown("c1.xhtml")
        vm.onTap("c1.xhtml", listOf(p(0, "A."), p(1, "B".repeat(4_001)), p(2, "C.")))
        advanceUntilIdle()
        assertEquals(listOf("A.", "C."), engine.translatedTexts)
    }

    // Revisión final 3b (I2): al cambiar de dirección, lo pedido con la vieja no llega al motor (ni lo obliga a cambiar).
    @Test fun cambiarDireccionVaciaLaFilaDeLaVieja() = runTest(dispatcher) {
        val slow = FakeEngine(workMillis = 100)
        val (vm, _) = vmWithOps(slow)
        advanceUntilIdle()
        vm.onResourceShown("c1.xhtml")
        vm.onTap("c1.xhtml", listOf(p(0, "A."), p(1, "B."), p(2, "C."), p(3, "D.")))
        advanceTimeBy(150); runCurrent() // A. hecha; B. en curso; C. y D. en la fila
        vm.setDirection(esEn)
        vm.onTap("c1.xhtml", listOf(p(5, "Hola.")))
        advanceUntilIdle()
        assertEquals(listOf("A.", "Hola."), slow.translatedTexts)
        assertEquals(listOf(enEs, esEn), slow.loadedPairs)
    }

    // Revisión final 3b (M2): el párrafo demasiado largo no pasa por "Preparando…": su tarjeta sale directa y se anuncia.
    @Test fun parrafoDemasiadoLargoNoPasaPorPreparando() = runTest(dispatcher) {
        val (vm, ops) = vmWithOps()
        val said = mutableListOf<CardOp.Show>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.announcements.toList(said) }
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(p(0, "Ab. ".repeat(6_000))))
        advanceUntilIdle()
        assertEquals(listOf<CardOp>(CardOp.Show("c1.xhtml", 0, CardState.TooLong)), ops)
        assertEquals(listOf(CardOp.Show("c1.xhtml", 0, CardState.TooLong)), said)
        assertEquals(CardState.TooLong, vm.cardState("c1.xhtml", 0))
        assertEquals(0, provider.installedCalls)
    }

    // Revisión final 3b (M5): la cadena entera, de la respuesta de la página a la tarjeta, con un texto que tras el
    // recorte de Kotlin queda justo en el tope: la marca `cut` basta para "demasiado largo" y el motor ni se carga.
    @Test fun recortadoPorLaPaginaEsDemasiadoLargoAunqueQuedeEnElTope() = runTest(dispatcher) {
        val (vm, ops) = vmWithOps()
        val json = org.json.JSONArray()
            .put(org.json.JSONObject().put("i", 0).put("t", "a".repeat(20_000) + Char(0x1F)).put("cut", true))
            .put(org.json.JSONObject().put("i", 1).put("t", "B."))
            .toString()
        val hit = ParagraphScripts.parseFind(json)
        assertEquals(20_000, hit[0].text.length) // por longitud sola se traduciría
        vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", hit)
        advanceUntilIdle()
        assertEquals(listOf<CardOp>(CardOp.Show("c1.xhtml", 0, CardState.TooLong)), ops)
        assertEquals(0, engine.loadCount)
        assertTrue(engine.translatedTexts.isEmpty())
    }

    // ---------- Ajustes de lectura (3c-1) ----------

    @Test fun `elegir un ajuste lo guarda en el telefono y lo publica`() = runTest(dispatcher) {
        val vm = viewModel()
        val sepia = ReadingSettings(theme = PageTheme.SEPIA, fontScale = 1.2)
        vm.setReadingSettings(sepia)
        assertEquals(sepia, vm.readingSettings.value)
        assertEquals(sepia, AppSettings(prefs).readingSettings) // releído del archivo, no de la memoria
    }

    // M3: la hoja manda "qué cambiar", no una copia de lo último que dibujó: dos toques antes de redibujar no se pisan.
    @Test fun `dos cambios seguidos de la hoja se conservan los dos`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.updateReadingSettings { it.copy(theme = PageTheme.SEPIA) }
        vm.updateReadingSettings { it.copy(font = ReadingFont.INTER) }
        val both = ReadingSettings(theme = PageTheme.SEPIA, font = ReadingFont.INTER)
        assertEquals(both, vm.readingSettings.value)
        assertEquals(both, AppSettings(prefs).readingSettings)
    }

    @Test fun `restablecer vuelve a los ajustes de fabrica`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setReadingSettings(ReadingSettings(theme = PageTheme.BLACK, fontScale = 2.0))
        vm.resetReadingSettings()
        assertTrue(vm.readingSettings.value.isFactory)
        assertTrue(AppSettings(prefs).readingSettings.isFactory)
    }

    @Test fun `A mas y A menos van de 10 en 10 y no pasan los topes`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.stepFontScale(up = true)
        assertEquals(1.1, vm.readingSettings.value.fontScale)
        vm.setReadingSettings(ReadingSettings(fontScale = 2.5))
        vm.stepFontScale(up = true)
        assertEquals(2.5, vm.readingSettings.value.fontScale)
        vm.setReadingSettings(ReadingSettings(fontScale = 0.75))
        vm.stepFontScale(up = false)
        assertEquals(0.75, vm.readingSettings.value.fontScale)
        assertEquals(0.75, AppSettings(prefs).readingSettings.fontScale)
    }

    @Test fun `cambiar el tamano conserva los demas ajustes`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setReadingSettings(ReadingSettings(theme = PageTheme.SEPIA))
        vm.stepFontScale(up = true); vm.stepFontScale(up = true)
        assertEquals(ReadingSettings(theme = PageTheme.SEPIA, fontScale = 1.2), AppSettings(prefs).readingSettings)
    }

    // ---------- Modos de lectura (3c-2): la guía de modo ----------

    private val c1 = "c1.xhtml"
    private val c2 = "c2.xhtml"
    private val screen = CardAnchor.SCREEN

    /**
     * Párrafos "P{i}." de una página, de arriba abajo como los da `visible()`: los [onScreen] primeros tocan la pantalla
     * y el resto son los siguientes.
     */
    private fun ps(range: IntRange, onScreen: Int = 2) =
        range.mapIndexed { k, i -> PageParagraph(i, "P$i.", onScreen = k < onScreen) }

    /** El modo de lectura guardado antes de abrir el Lector (igual para todos los libros). */
    private fun mode(m: ReadingMode) {
        settings.readingSettings = ReadingSettings(mode = m)
    }

    /** La última op (Show o Hide) del párrafo [index] de [resource], o null si no hubo ninguna. */
    private fun List<CardOp>.lastFor(index: Int, resource: String = c1): CardOp? = lastOrNull {
        (it is CardOp.Show && it.index == index && it.resource == resource) ||
            (it is CardOp.Hide && it.index == index && it.resource == resource)
    }

    /** Ninguna tarjeta de [indices] quedó en espera ("Traduciendo…", "Preparando…" o el original en gris). */
    private fun List<CardOp>.noneWaiting(indices: Iterable<Int>, resource: String = c1) = indices.all { i ->
        val last = lastFor(i, resource)
        !(last is CardOp.Show && (last.card == CardState.Skeleton || last.card == CardState.Preparing || last.card == CardState.Waiting))
    }

    private fun List<CardOp>.anchors() = mapNotNull {
        when (it) {
            is CardOp.Show -> it.anchor
            is CardOp.Hide -> it.anchor
            is CardOp.Mode -> null
            is CardOp.RemoveWaiting -> null
        }
    }

    @Test fun intercaladoPideLoVisibleEnOrdenYLosSiguientesComoPretraduccion() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        val slow = FakeEngine(workMillis = 100) // cada párrafo tarda 100 ms
        val (vm, ops) = vmWithOps(slow)
        val page = ps(0..11) // 2 en pantalla + los 10 siguientes
        vm.onResourceShown(c1)
        vm.onTap(c1, listOf(p(50, "Cincuenta."))) // un toque ocupa el motor de 0 a 100 ms
        vm.onVisible(c1, page)
        runCurrent()
        // Cada uno recibe enseguida su tarjeta de espera, de arriba abajo.
        val first = ops.filterIsInstance<CardOp.Show>().filter { it.index in 0..11 }.distinctBy { it.index }
        assertEquals((0..11).toList(), first.map { it.index })
        assertTrue(first.all { it.card == CardState.Skeleton || it.card == CardState.Preparing }, "$first")
        // P0. (en pantalla) espera en la fila del servicio detrás del toque: va como toque, cambiar de capítulo
        // (aquí, directo al servicio) no lo saca.
        advanceTimeBy(50); runCurrent()
        service.cancelPrefetchExcept("otro.xhtml")
        advanceTimeBy(200); runCurrent() // t = 250: P0. hecho; P1. (en pantalla) en curso
        vm.onTap(c1, listOf(p(60, "Sesenta."))) // otro toque: sale antes que cualquier pretraducción
        advanceTimeBy(100); runCurrent() // t = 350: "Sesenta." en curso; P2. (siguiente) espera detrás
        // Los siguientes van como pretraducción: cambiar de capítulo sí los saca de la fila.
        service.cancelPrefetchExcept("otro.xhtml")
        advanceUntilIdle()
        assertEquals(listOf("Cincuenta.", "P0.", "P1.", "Sesenta.") + (3..11).map { "P$it." }, slow.translatedTexts)
        for (i in (0..11) - 2) assertEquals(CardOp.Show(c1, i, CardState.Text("T(P$i.)"), screen), ops.lastFor(i))
        // El que salió de la fila no se queda en "Traduciendo…": se quita y se pide en la siguiente vuelta.
        assertEquals(CardOp.Hide(c1, 2, screen), ops.lastFor(2))
        assertNull(vm.cardState(c1, 2))
        vm.onVisible(c1, page); advanceUntilIdle()
        assertEquals(CardState.Text("T(P2.)"), vm.cardState(c1, 2))
        assertEquals(14, slow.translatedTexts.size)
        assertTrue(ops.anchors().all { it == screen }, "en los modos el ancla es la pantalla: ${ops.anchors()}")
    }

    @Test fun topeDeQuincePedidos() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        val g = FakeEngine(gate = true)
        val (vm, ops) = vmWithOps(g)
        val page = ps(0..29)
        vm.onResourceShown(c1); vm.onVisible(c1, page)
        runCurrent()
        assertEquals((0..14).toList(), ops.filterIsInstance<CardOp.Show>().map { it.index }.distinct())
        assertTrue((15..29).all { vm.cardState(c1, it) == null })
        g.releaseAll(); advanceUntilIdle()
        // Lo que sobró se pidió al vaciarse la fila (sin esperar a desplazar), en orden…
        assertEquals((0..29).map { "P$it." }, g.translatedTexts)
        assertTrue(ops.noneWaiting(0..29))
        // …y nunca hubo más de 15 en espera a la vez.
        assertEquals(15, ops.maxWaiting())
    }

    // Revisión Tarea 4 (menor 3): lo que pasó del tope se pide cuando la fila del modo se vacía, no antes.
    @Test fun alVaciarseLaFilaPideLoQueSobro() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        val slow = FakeEngine(workMillis = 100) // P0. de 0 a 100 ms, P1. de 100 a 200… P14. de 1400 a 1500
        val (vm, ops) = vmWithOps(slow)
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..19))
        advanceTimeBy(1_450); runCurrent()
        assertEquals((0..13).map { "P$it." }, slow.translatedTexts)
        assertTrue((15..19).all { vm.cardState(c1, it) == null }, "aún no: la fila del modo no se vació")
        advanceUntilIdle()
        assertEquals((0..19).map { "P$it." }, slow.translatedTexts)
        for (i in 15..19) assertEquals(CardOp.Show(c1, i, CardState.Text("T(P$i.)"), screen), ops.lastFor(i))
        // Con todo pedido no hay más vueltas: la fila queda quieta.
        val before = ops.size
        advanceUntilIdle()
        assertEquals(before, ops.size)
    }

    // Revisión Tarea 4 (importante 1): reponer muchas tarjetas de una vez no pierde ninguna aunque la pantalla vaya lenta.
    @Test fun reponerTrescientasTarjetasNoPierdeNinguna() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        for (i in 0..299) {
            val key = TranslationRules.cacheKey(FakeEngineProvider.OPUS_TAG, enEs, "P$i.")
            cache.rows[key] = TranslationEntity(key, "T(P$i.)", 1L)
        }
        val vm = viewModel()
        val ops = mutableListOf<CardOp>()
        // La pantalla aplica cada op con un script (suspende): aquí recibe solo cuando corre el hilo de prueba, después
        // de que el ViewModel ya mandó todas (en backgroundScope advanceUntilIdle no la esperaría).
        val screenJob = launch { vm.cardOps.toList(ops) }
        runCurrent()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..299, onScreen = 10))
        advanceUntilIdle()
        assertEquals((0..299).toList(), ops.filterIsInstance<CardOp.Show>().map { it.index }) // todas, desde el caché
        val before = ops.size
        vm.onResourceShown(c1) // la página se recargó: se reponen todas en el mismo instante
        vm.updateReadingSettings { it.copy(fontScale = 1.5) } // y otra vez con un ajuste nuevo
        advanceUntilIdle()
        val again = ops.drop(before).filterIsInstance<CardOp.Show>().map { it.index }
        assertEquals((0..299).toList() + (0..299).toList(), again)
        assertTrue(engine.translatedTexts.isEmpty())
        screenJob.cancel()
    }

    /** Guarda en el caché falso "T(P{i}.)" para cada párrafo "P{i}." de [range]. */
    private fun cacheP(range: IntRange) {
        for (i in range) {
            val key = TranslationRules.cacheKey(FakeEngineProvider.OPUS_TAG, enEs, "P$i.")
            cache.rows[key] = TranslationEntity(key, "T(P$i.)", 1L)
        }
    }

    /** Lo que espera en la fila de tarjetas sin pantalla: se recoge ahora, de una vez. */
    private fun TestScope.pendingOps(vm: ReaderViewModel): List<CardOp> {
        val ops = mutableListOf<CardOp>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.cardOps.toList(ops) }
        job.cancel()
        return ops
    }

    // Seguridad 3c-2 (BAJO-2): sin pantalla que recoja (Lector en segundo plano), los cambios de ajustes y de modo no
    // hacen crecer la fila de tarjetas: al reponer se descarta lo viejo y queda solo lo que vale ahora.
    @Test fun sinPantallaLaFilaDeTarjetasNoCrece() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        cacheP(0..9)
        val vm = viewModel()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..9, onScreen = 5))
        advanceUntilIdle()
        repeat(20) { k -> vm.updateReadingSettings { it.copy(fontScale = if (k % 2 == 0) 1.2 else 1.1) }; advanceUntilIdle() }
        repeat(5) {
            vm.updateReadingSettings { it.copy(mode = ReadingMode.TRANSLATION_ONLY) }; advanceUntilIdle()
            vm.updateReadingSettings { it.copy(mode = ReadingMode.INTERLEAVED) }; advanceUntilIdle()
        }
        val ops = pendingOps(vm)
        // Sin pérdidas: el último modo y cada tarjeta una vez, con lo que muestra ahora.
        assertEquals(
            listOf<CardOp>(CardOp.Mode(ReadingMode.INTERLEAVED)) + (0..9).map { CardOp.Show(c1, it, CardState.Text("T(P$it.)"), screen) },
            ops,
        )
        assertTrue(engine.translatedTexts.isEmpty())
    }

    // Seguridad 3c-2 (BAJO-2): lo que esperaba del capítulo anterior se descarta al cambiar de capítulo.
    @Test fun cambiarDeCapituloSinPantallaDescartaLasOpsDelAnterior() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        cacheP(0..9)
        val vm = viewModel()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..9))
        advanceUntilIdle()
        vm.onResourceShown(c2); vm.onVisible(c2, listOf(p(0, "Q0.")))
        advanceUntilIdle()
        val ops = pendingOps(vm)
        assertTrue(ops.none { it is CardOp.Show && it.resource == c1 || it is CardOp.Hide && it.resource == c1 }, "ops de c1: $ops")
        assertEquals(CardOp.Show(c2, 0, CardState.Text("T(Q0.)"), screen), ops.last())
    }

    // Lo que sigue valiendo no se pierde: un cierre pendiente del recurso visible queda en la fila tras reponer.
    @Test fun reponerSinPantallaConservaLosCierresDelVisible() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onResourceShown(c1); vm.onTap(c1, listOf(p(0, "Hi.")))
        advanceUntilIdle()
        vm.onTap(c1, listOf(p(0, "Hi."))) // en Tocar, otro toque la cierra
        vm.onTap(c1, listOf(p(1, "Two.")))
        advanceUntilIdle()
        vm.onResourceShown(c1) // vuelta al frente: se repone
        val ops = pendingOps(vm)
        assertEquals(
            listOf<CardOp>(CardOp.Hide(c1, 0, CardAnchor.SELF), CardOp.Show(c1, 1, CardState.Text("T(Two.)"), CardAnchor.SELF)),
            ops,
        )
    }

    // Decisión del controlador: un toque que da con el motor caído o sin modelo en un modo nuevo solo anuncia el aviso.
    @Test fun tocarQueFallaEnUnModoSoloDaElAviso() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        provider.installedEngines = emptyMap()
        val (vm, ops, said) = vmWithAnnouncements()
        vm.onResourceShown(c1); vm.onTap(c1, listOf(p(7, "Siete.")))
        advanceUntilIdle()
        assertEquals(ChapterNotice.MissingModel(EngineId.OPUS), vm.chapterNotice.value)
        assertTrue(said.isEmpty(), "ni la tarjeta ni su texto: la pantalla anuncia el aviso: $said")
        assertNull(vm.cardState(c1, 7))
        assertTrue(ops.none { it is CardOp.Show && it.card is CardState.MissingModel })
    }

    // Revisión Tarea 4 (menor 5): al volver a Tocar quedan las tarjetas abiertas con un toque; las de la guía se van.
    @Test fun volverATocarConservaLasTocadas() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        val (vm, ops) = vmWithOps()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..1))
        advanceUntilIdle()
        vm.onTap(c1, listOf(p(7, "Siete."))) // abierta con un toque (no la pidió la guía)
        advanceUntilIdle()
        val before = ops.size
        vm.updateReadingSettings { it.copy(mode = ReadingMode.TAP) }
        runCurrent()
        val after = ops.drop(before)
        assertEquals(CardOp.Mode(ReadingMode.TAP), after.first())
        assertEquals(setOf<CardOp>(CardOp.Hide(c1, 0, screen), CardOp.Hide(c1, 1, screen)), after.filterIsInstance<CardOp.Hide>().toSet())
        assertEquals(listOf<CardOp>(CardOp.Show(c1, 7, CardState.Text("T(Siete.)"), screen)), after.filterIsInstance<CardOp.Show>())
        assertEquals(CardState.Text("T(Siete.)"), vm.cardState(c1, 7))
        assertNull(vm.cardState(c1, 0))
        // En Tocar se cierra con un toque, como en la 3b (fijando el propio párrafo).
        vm.onTap(c1, listOf(p(7, "Siete.")))
        assertEquals(CardOp.Hide(c1, 7), ops.last())
    }

    /** Cuántas tarjetas llegaron a estar en espera a la vez, repasando las ops en orden. */
    private fun List<CardOp>.maxWaiting(): Int {
        val waiting = HashSet<Pair<String, Int>>()
        var max = 0
        for (op in this) {
            when (op) {
                is CardOp.Show -> if (op.card == CardState.Skeleton || op.card == CardState.Preparing || op.card == CardState.Waiting) {
                    waiting += op.resource to op.index
                } else {
                    waiting -= op.resource to op.index
                }
                is CardOp.Hide -> waiting -= op.resource to op.index
                is CardOp.Mode -> Unit
                is CardOp.RemoveWaiting -> waiting.removeAll { it.first == op.resource }
            }
            max = maxOf(max, waiting.size)
        }
        return max
    }

    // Review Focus 1: desplazar rápido varias pantallas no llena la fila de pedidos viejos.
    @Test fun desplazarRapidoSoloPideLoUltimoVisible() = runTest(dispatcher) {
        mode(ReadingMode.TRANSLATION_ONLY)
        val g = FakeEngine(gate = true)
        val (vm, ops) = vmWithOps(g)
        vm.onResourceShown(c1)
        vm.onVisible(c1, ps(0..11)); runCurrent() // P0. queda en curso (la compuerta lo detiene)
        vm.onVisible(c1, ps(20..31)); runCurrent()
        vm.onVisible(c1, ps(40..51)); runCurrent()
        g.releaseAll(); advanceUntilIdle()
        // Solo el que ya estaba en curso y la última pantalla; nada de las pantallas que pasaron.
        assertEquals(listOf("P0.") + (40..51).map { "P$it." }, g.translatedTexts)
        for (i in (1..11) + (20..31)) {
            assertEquals(CardOp.Hide(c1, i, screen), ops.lastFor(i), "párrafo $i")
            assertNull(vm.cardState(c1, i))
        }
        for (i in 40..51) assertEquals(CardOp.Show(c1, i, CardState.Solo("T(P$i.)"), screen), ops.lastFor(i))
        assertTrue(ops.noneWaiting((0..11) + (20..31) + (40..51)))
    }

    @Test fun cambiarDeCapituloCancelaLoPendiente() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        val slow = FakeEngine(workMillis = 100)
        val (vm, ops) = vmWithOps(slow)
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..10, onScreen = 1)) // 1 en pantalla + 10 siguientes
        advanceTimeBy(150); runCurrent() // P0. hecho; P1. en curso (en el servicio); P2.–P10. esperan en la fila del modo
        vm.onResourceShown(c2); vm.onVisible(c2, listOf(p(0, "Q0."), p(1, "Q1.")))
        advanceUntilIdle()
        // Lo que estaba en curso termina (nunca se corta); lo pendiente del capítulo anterior ya no se traduce.
        assertEquals(listOf("P0.", "P1.", "Q0.", "Q1."), slow.translatedTexts)
        assertEquals(CardState.Text("T(P0.)"), vm.cardState(c1, 0))
        assertEquals(CardState.Text("T(P1.)"), vm.cardState(c1, 1))
        assertTrue((2..10).all { vm.cardState(c1, it) == null })
        assertEquals(CardOp.Show(c2, 1, CardState.Text("T(Q1.)"), screen), ops.last())
        // Al volver solo se reponen las traducidas: ningún esqueleto viejo.
        val before = ops.size
        vm.onResourceShown(c1); runCurrent()
        assertEquals(
            listOf<CardOp>(CardOp.Show(c1, 0, CardState.Text("T(P0.)"), screen), CardOp.Show(c1, 1, CardState.Text("T(P1.)"), screen)),
            ops.drop(before),
        )
    }

    // Review Focus 3: cambiar de modo con traducciones a medias no deja nada en espera para siempre.
    @Test fun cambiarDeModoAMitadNoDejaEsqueletos() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        val g = FakeEngine(gate = true)
        val (vm, ops) = vmWithOps(g)
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..4))
        runCurrent() // P0. en curso, el resto en la fila del modo
        // Intercalado → Solo traducción: primero el modo de la página y luego cada tarjeta adaptada (en espera).
        var before = ops.size
        vm.updateReadingSettings { it.copy(mode = ReadingMode.TRANSLATION_ONLY) }
        runCurrent()
        val toSolo = ops.drop(before)
        assertEquals(CardOp.Mode(ReadingMode.TRANSLATION_ONLY), toSolo.first())
        assertEquals((0..4).map { CardOp.Show(c1, it, CardState.Waiting, screen) }, toSolo.drop(1).take(5))
        // Solo traducción → Tocar a mitad: las automáticas se quitan (la fila del modo también).
        before = ops.size
        vm.updateReadingSettings { it.copy(mode = ReadingMode.TAP) }
        runCurrent()
        val toTap = ops.drop(before)
        assertEquals(CardOp.Mode(ReadingMode.TAP), toTap.first())
        assertEquals((0..4).map { CardOp.Hide(c1, it, screen) }.toSet(), toTap.drop(1).toSet())
        g.releaseAll(); advanceUntilIdle()
        assertEquals(listOf("P0."), g.translatedTexts) // solo terminó el que estaba en curso (queda en caché)
        assertTrue(ops.drop(before).none { it is CardOp.Show }, "el resultado tardío no reabre: ${ops.drop(before)}")
        assertTrue((0..4).all { vm.cardState(c1, it) == null })
        // Otra vez a Solo traducción: la pantalla manda lo visible de ese momento (P0. sale del caché) y todo se traduce.
        vm.updateReadingSettings { it.copy(mode = ReadingMode.TRANSLATION_ONLY) }
        runCurrent()
        vm.onVisible(c1, ps(0..4))
        advanceUntilIdle()
        for (i in 0..4) assertEquals(CardOp.Show(c1, i, CardState.Solo("T(P$i.)"), screen), ops.lastFor(i))
        assertEquals((0..4).map { "P$it." }, g.translatedTexts)
        assertTrue(ops.noneWaiting(0..4))
    }

    // Review Focus 4: un motor que no carga da un solo aviso arriba, no un error por párrafo.
    @Test fun fallaElMotorUnSoloAviso() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        engine.failLoads = 100
        val (vm, ops) = vmWithOps()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..4))
        advanceUntilIdle()
        assertEquals(ChapterNotice.EngineFailed, vm.chapterNotice.value)
        assertTrue(ops.none { it is CardOp.Show && it.card is CardState.Failed }, "$ops")
        assertTrue(ops.noneWaiting(0..4))
        assertTrue((0..4).all { vm.cardState(c1, it) == null }) // Intercalado: sin tarjeta
        assertEquals(1, engine.loadCount) // una sola carga, no una por párrafo
        // La página quieta otra vez no vuelve a intentar.
        vm.onVisible(c1, ps(0..4)); advanceUntilIdle()
        assertEquals(1, engine.loadCount)
        assertEquals(ChapterNotice.EngineFailed, vm.chapterNotice.value)
    }

    @Test fun reintentarCapituloVuelveAPedirLoVisible() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        engine.failLoads = 1
        val (vm, ops) = vmWithOps()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..4))
        advanceUntilIdle()
        assertEquals(ChapterNotice.EngineFailed, vm.chapterNotice.value)
        vm.onVisible(c1, ps(3..7)) // se desplazó: "Reintentar" pide lo que se ve ahora
        advanceUntilIdle()
        assertTrue(engine.translatedTexts.isEmpty()) // con el aviso puesto, nada
        vm.retryChapter(c1)
        assertNull(vm.chapterNotice.value)
        advanceUntilIdle()
        assertEquals((3..7).map { "P$it." }, engine.translatedTexts)
        for (i in 3..7) assertEquals(CardOp.Show(c1, i, CardState.Text("T(P$i.)"), screen), ops.lastFor(i))
        assertNull(vm.chapterNotice.value)
    }

    @Test fun faltaElModeloUnSoloAviso() = runTest(dispatcher) {
        mode(ReadingMode.TRANSLATION_ONLY)
        provider.installedEngines = emptyMap()
        val (vm, ops) = vmWithOps()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..4))
        advanceUntilIdle()
        assertEquals(ChapterNotice.MissingModel(EngineId.OPUS), vm.chapterNotice.value)
        assertTrue(ops.none { it is CardOp.Show && it.card is CardState.MissingModel }, "$ops")
        assertTrue(ops.noneWaiting(0..4)) // Solo traducción: el original queda a la vista, sin gris
        assertTrue((0..4).all { vm.cardState(c1, it) == null })
        assertEquals(0, engine.loadCount)
        val calls = provider.installedCalls
        vm.onVisible(c1, ps(0..4)); advanceUntilIdle()
        assertEquals(calls, provider.installedCalls) // con el aviso puesto no se vuelve a mirar
        // Se descargó en Idiomas: al cerrar se quita el aviso y se traduce lo visible.
        provider.installedEngines = mapOf(EngineId.OPUS to FakeEngineProvider.OPUS_TAG)
        vm.onLanguagesClosed()
        advanceUntilIdle()
        assertNull(vm.chapterNotice.value)
        for (i in 0..4) assertEquals(CardOp.Show(c1, i, CardState.Solo("T(P$i.)"), screen), ops.lastFor(i))
    }

    // Diseño (Tarea 6, ALTO): al salir el aviso no queda ninguna tarjeta en espera; las ya traducidas se quedan.
    @Test fun elAvisoQuitaTodasLasEsperasEnIntercalado() = runTest(dispatcher) { avisoQuitaEsperas(ReadingMode.INTERLEAVED) }

    @Test fun elAvisoQuitaTodasLasEsperasEnSolo() = runTest(dispatcher) { avisoQuitaEsperas(ReadingMode.TRANSLATION_ONLY) }

    private suspend fun TestScope.avisoQuitaEsperas(m: ReadingMode) {
        run {
            mode(m)
            provider.installedEngines = mapOf(EngineId.OPUS to FakeEngineProvider.OPUS_TAG)
            val (vm, ops) = vmWithOps()
            vm.onResourceShown(c1); vm.onVisible(c1, ps(0..1))
            advanceUntilIdle()
            val final = if (m == ReadingMode.INTERLEAVED) CardState.Text("T(P0.)") else CardState.Solo("T(P0.)")
            assertEquals(CardOp.Show(c1, 0, final, screen), ops.lastFor(0))
            // Se pierde el modelo y la página trae más: el aviso sale con varias tarjetas en espera.
            provider.installedEngines = emptyMap()
            vm.onLanguagesClosed(); advanceUntilIdle()
            vm.onTap(c1, listOf(p(50, "Cincuenta."))) // una abierta con un toque, también en espera
            vm.onVisible(c1, ps(0..8)); advanceUntilIdle()
            assertEquals(ChapterNotice.MissingModel(EngineId.OPUS), vm.chapterNotice.value)
            assertTrue(ops.noneWaiting((2..8) + 50), "$m: $ops")
            assertTrue((2..8).all { vm.cardState(c1, it) == null } && vm.cardState(c1, 50) == null)
            assertEquals(CardOp.Show(c1, 0, final, screen), ops.lastFor(0)) // la final se queda
            // La página recibe además el barrido de esperas, después de las ops de las tarjetas (nunca antes).
            val sweep = ops.indexOfLast { it == CardOp.RemoveWaiting(c1) }
            assertTrue(sweep >= 0 && ops.drop(sweep).none { it is CardOp.Show }, "$m: $ops")
            // Reintentar: vuelve la espera normal y la guía rellena.
            provider.installedEngines = mapOf(EngineId.OPUS to FakeEngineProvider.OPUS_TAG)
            vm.retryChapter(c1); advanceUntilIdle()
            assertNull(vm.chapterNotice.value)
            for (i in 2..8) assertTrue(ops.lastFor(i).let { it is CardOp.Show && it.card != CardState.Skeleton }, "$m $i")
        }
    }

    // Diseño T6 (ALTO), prueba en el Pixel: la pantalla aplica las ops una a una y despacio; las esperas que el aviso
    // ya dejó sin tarjeta no deben esperar su turno en la fila (se verían un buen rato antes de quitarse).
    @Test fun elAvisoSacaDeLaFilaLosShowDeEsperasQueNadieHaAplicado() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        engine.failLoads = 100
        val vm = viewModel() // nadie recoge cardOps: todo queda en la fila, como con la pantalla ocupada
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..4))
        advanceUntilIdle()
        assertEquals(ChapterNotice.EngineFailed, vm.chapterNotice.value)
        val late = mutableListOf<CardOp>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.cardOps.toList(late) }
        runCurrent()
        assertTrue(late.none { it is CardOp.Show }, "quedaron Show de esperas sin aplicar: $late")
        assertTrue(late.contains(CardOp.RemoveWaiting(c1)), "$late")
    }

    @Test fun tocarEnIntercaladoOcultaYMuestra() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        val (vm, ops) = vmWithOps()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..1))
        advanceUntilIdle()
        assertEquals(CardOp.Show(c1, 0, CardState.Text("T(P0.)"), screen), ops.lastFor(0))
        vm.onTap(c1, listOf(p(0, "P0."), p(1, "P1.")))
        assertEquals(CardOp.Hide(c1, 0, screen), ops.last())
        assertNull(vm.cardState(c1, 0))
        // Oculta a pedido: la página quieta otra vez no la vuelve a pedir ni a mostrar.
        val before = ops.size
        vm.onVisible(c1, ps(0..1)); advanceUntilIdle()
        assertEquals(before, ops.size)
        vm.onTap(c1, listOf(p(0, "P0."), p(1, "P1.")))
        assertEquals(CardOp.Show(c1, 0, CardState.Text("T(P0.)"), screen), ops.last()) // al instante
        assertEquals(CardState.Text("T(P0.)"), vm.cardState(c1, 0))
        assertEquals(listOf("P0.", "P1."), engine.translatedTexts) // sin volver a traducir
        // Un párrafo sin tarjeta (aún no pedido): el toque lo abre y lo traduce.
        vm.onTap(c1, listOf(p(7, "Siete.")))
        advanceUntilIdle()
        assertEquals(CardOp.Show(c1, 7, CardState.Text("T(Siete.)"), screen), ops.last())
    }

    @Test fun tocarEnSoloAlternaOriginal() = runTest(dispatcher) {
        mode(ReadingMode.TRANSLATION_ONLY)
        val (vm, ops) = vmWithOps()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..1))
        advanceUntilIdle()
        assertEquals(CardOp.Show(c1, 0, CardState.Solo("T(P0.)"), screen), ops.lastFor(0))
        // El toque sobre la tarjeta Solo (la pantalla manda el índice sin texto) muestra el original…
        vm.onTap(c1, listOf(p(0, "")))
        assertEquals(CardOp.Show(c1, 0, CardState.ShowingOriginal("T(P0.)"), screen), ops.last())
        assertEquals(CardState.ShowingOriginal("T(P0.)"), vm.cardState(c1, 0))
        // …y otro toque (sobre el original) vuelve a la traducción.
        vm.onTap(c1, listOf(p(0, "P0."), p(1, "P1.")))
        assertEquals(CardOp.Show(c1, 0, CardState.Solo("T(P0.)"), screen), ops.last())
        assertEquals(CardState.Solo("T(P0.)"), vm.cardState(c1, 0))
        assertEquals(listOf("P0.", "P1."), engine.translatedTexts) // alternar no traduce otra vez
        assertTrue(ops.anchors().all { it == screen })
    }

    @Test fun lasTarjetasAutomaticasNoSeAnuncian() = runTest(dispatcher) {
        mode(ReadingMode.TRANSLATION_ONLY)
        val (vm, _, said) = vmWithAnnouncements()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..3))
        advanceUntilIdle()
        assertTrue(said.isEmpty(), "$said")
        // Solo lo pedido con un toque: alternar al original sí se anuncia.
        vm.onTap(c1, listOf(p(0, "")))
        assertEquals(listOf(CardOp.Show(c1, 0, CardState.ShowingOriginal("T(P0.)"), screen)), said)
        // Ni la adaptación al cambiar de modo ni lo que se llena solo en Intercalado.
        vm.updateReadingSettings { it.copy(mode = ReadingMode.INTERLEAVED) }
        vm.onVisible(c1, ps(10..12))
        advanceUntilIdle()
        assertEquals(CardState.Text("T(P12.)"), vm.cardState(c1, 12))
        assertEquals(1, said.size)
    }

    @Test fun modoTocarNoPideNadaAlDesplazar() = runTest(dispatcher) {
        val (vm, ops) = vmWithOps() // modo de fábrica: Tocar
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..11))
        advanceUntilIdle()
        assertTrue(ops.isEmpty(), "$ops")
        assertTrue(engine.translatedTexts.isEmpty())
        assertEquals(0, provider.installedCalls) // ni siquiera mira el caché
        assertNull(vm.chapterNotice.value)
    }

    // Tarea 3 (revisión): el estilo copiado de la tarjeta Solo se queda viejo al cambiar un ajuste; se repone todo.
    @Test fun cambiarUnAjusteEnSoloReponeLasTarjetas() = runTest(dispatcher) {
        mode(ReadingMode.TRANSLATION_ONLY)
        val (vm, ops) = vmWithOps()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..1))
        advanceUntilIdle()
        vm.onTap(c1, listOf(p(0, ""))) // la persona está viendo el original del 0
        val before = ops.size
        vm.updateReadingSettings { it.copy(fontScale = 1.5) }
        runCurrent()
        assertEquals(
            listOf<CardOp>(
                CardOp.Show(c1, 0, CardState.ShowingOriginal("T(P0.)"), screen),
                CardOp.Show(c1, 1, CardState.Solo("T(P1.)"), screen),
            ),
            ops.drop(before),
        )
        assertEquals(2, engine.translatedTexts.size)
    }

    @Test fun enTocarCambiarUnAjusteNoReponeNada() = runTest(dispatcher) {
        val (vm, ops) = vmWithOps()
        vm.onResourceShown(c1); vm.onTap(c1, listOf(p(0, "Hi.")))
        advanceUntilIdle()
        val before = ops.size
        vm.updateReadingSettings { it.copy(theme = PageTheme.SEPIA) }
        advanceUntilIdle()
        assertEquals(before, ops.size)
    }

    // Tarea 3 (revisión): salir de "solo" y volver deja la tarjeta como "solo" en la página: se repone con el modo.
    @Test fun salirYVolverASoloReponeLasTarjetas() = runTest(dispatcher) {
        mode(ReadingMode.TRANSLATION_ONLY)
        val (vm, ops) = vmWithOps()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..1))
        advanceUntilIdle()
        var before = ops.size
        vm.updateReadingSettings { it.copy(mode = ReadingMode.INTERLEAVED) }
        advanceUntilIdle()
        assertEquals(
            listOf(
                CardOp.Mode(ReadingMode.INTERLEAVED),
                CardOp.Show(c1, 0, CardState.Text("T(P0.)"), screen),
                CardOp.Show(c1, 1, CardState.Text("T(P1.)"), screen),
            ),
            ops.drop(before),
        )
        before = ops.size
        vm.updateReadingSettings { it.copy(mode = ReadingMode.TRANSLATION_ONLY) }
        advanceUntilIdle()
        assertEquals(
            listOf(
                CardOp.Mode(ReadingMode.TRANSLATION_ONLY),
                CardOp.Show(c1, 0, CardState.Solo("T(P0.)"), screen),
                CardOp.Show(c1, 1, CardState.Solo("T(P1.)"), screen),
            ),
            ops.drop(before),
        )
        assertEquals(2, engine.translatedTexts.size)
    }

    // Puerta 3c-2, punto 4: cambiar la dirección vuelve a traducir lo visible.
    @Test fun cambiarDireccionEnUnModoVuelveATraducirLoVisible() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        val (vm, ops) = vmWithOps()
        advanceUntilIdle()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..1))
        advanceUntilIdle()
        val before = ops.size
        vm.setDirection(esEn)
        advanceUntilIdle()
        val after = ops.drop(before)
        assertEquals(setOf<CardOp>(CardOp.Hide(c1, 0, screen), CardOp.Hide(c1, 1, screen)), after.take(2).toSet())
        assertEquals(esEn, engine.loadedPairs.last())
        assertEquals(listOf("P0.", "P1.", "P0.", "P1."), engine.translatedTexts)
        for (i in 0..1) assertEquals(CardOp.Show(c1, i, CardState.Text("T(P$i.)"), screen), ops.lastFor(i))
    }

    // El toque en Tocar sigue fijando el propio párrafo (Ruling F); al pasar a un modo, la pantalla.
    @Test fun elAnclaSaleDelModo() = runTest(dispatcher) {
        val (vm, ops) = vmWithOps()
        vm.onResourceShown(c1); vm.onTap(c1, listOf(p(0, "Hi.")))
        advanceUntilIdle()
        assertTrue(ops.anchors().all { it == CardAnchor.SELF })
        val before = ops.size
        vm.updateReadingSettings { it.copy(mode = ReadingMode.TRANSLATION_ONLY) }
        runCurrent()
        assertEquals(
            listOf<CardOp>(CardOp.Mode(ReadingMode.TRANSLATION_ONLY), CardOp.Show(c1, 0, CardState.Solo("T(Hi.)"), screen)),
            ops.drop(before),
        )
    }

    // Revisión final 3c-2 (I1): volver al frente sin que la página se recargue no repone nada (las tarjetas siguen ahí).
    @Test fun volverAlFrenteSinRecargarNoReponeEnIntercalado() = runTest(dispatcher) { resumeWithoutReload(ReadingMode.INTERLEAVED) }

    @Test fun volverAlFrenteSinRecargarNoReponeEnSolo() = runTest(dispatcher) { resumeWithoutReload(ReadingMode.TRANSLATION_ONLY) }

    private suspend fun TestScope.resumeWithoutReload(m: ReadingMode) {
        mode(m)
        cacheP(0..4)
        val (vm, ops) = vmWithOps()
        vm.onResourceShown(c1); vm.onVisible(c1, ps(0..4))
        advanceUntilIdle()
        assertTrue(ops.any { it is CardOp.Show }, "$m: no puso tarjetas")
        val before = ops.size
        vm.onResourceShown(c1, reloaded = false)
        advanceUntilIdle()
        assertEquals(emptyList(), ops.drop(before), "$m: repuso sin recarga")
        // Con recarga, sí: cada tarjeta otra vez.
        vm.onResourceShown(c1)
        advanceUntilIdle()
        assertEquals((0..4).toList(), ops.drop(before).filterIsInstance<CardOp.Show>().map { it.index }, "$m")
    }

    // Revisión final 3c-2 (m3): un toque que vuelve a fallar con el aviso ya puesto se anuncia otra vez (el aviso no
    // cambia, así que su región viva calla); el primero no (lo dice la región viva al salir el aviso).
    @Test fun tocarConElAvisoPuestoLoVuelveADecir() = runTest(dispatcher) {
        mode(ReadingMode.INTERLEAVED)
        provider.installedEngines = emptyMap()
        val (vm, _, said) = vmWithAnnouncements()
        val again = mutableListOf<ChapterNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.noticeAgain.toList(again) }
        vm.onResourceShown(c1); vm.onTap(c1, listOf(p(7, "Siete.")))
        advanceUntilIdle()
        assertEquals(ChapterNotice.MissingModel(EngineId.OPUS), vm.chapterNotice.value)
        assertTrue(again.isEmpty(), "el primero ya lo dice la región viva: $again")
        vm.onTap(c1, listOf(p(8, "Ocho.")))
        advanceUntilIdle()
        assertEquals(listOf<ChapterNotice>(ChapterNotice.MissingModel(EngineId.OPUS)), again)
        assertTrue(said.isEmpty(), "ninguna tarjeta: $said")
        assertNull(vm.cardState(c1, 8))
    }

    // ---------- Consejos (3c-3) ----------
    // El VM lee los vistos en su init (hilo de prueba): las llamadas a loadSeenTips de aquí no hacen falta salvo donde
    // la lectura del VM se deja en espera a propósito.

    @Test fun `libro nuevo en Tocar muestra tocar y al tocar pasa a ocultar y modos`() = runTest(dispatcher) {
        val vm = viewModel()
        val tips = mutableListOf<TipId?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tip.toList(tips) }
        runCurrent()
        assertNull(vm.tip.value) // sin página todavía
        vm.onResourceShown("c1.xhtml")
        runCurrent()
        assertEquals(TipId.TAP, vm.tip.value)
        vm.setTipOnScreen(true)
        vm.onTap("c1.xhtml", listOf(p(0, "Hello world.")))
        advanceUntilIdle()
        assertTrue(TipId.TAP in settings.seenTipsFlow.value) // tocar lo cerró y lo marcó
        assertEquals(TipId.HIDE_AND_MODES, vm.tip.value) // ya hay traducción por toque
        vm.onTap("c1.xhtml", listOf(p(0, "Hello world."))) // ocultar
        runCurrent()
        assertTrue(TipId.HIDE_AND_MODES in settings.seenTipsFlow.value)
        assertNull(vm.tip.value)
        assertEquals(listOf(null, TipId.TAP, null, TipId.HIDE_AND_MODES, null), tips)
    }

    @Test fun `libro en espanol muestra primero la direccion y abrir la hoja la cierra sin mostrar otro`() = runTest(dispatcher) {
        val vm = viewModel(languages = listOf("es"))
        val tips = mutableListOf<TipId?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tip.toList(tips) }
        vm.onResourceShown("c1.xhtml")
        advanceUntilIdle()
        assertEquals(TipId.DIRECTION, vm.tip.value)
        vm.setTipOnScreen(true)
        vm.onSheetOpened(TipEvent.DIRECTION_OPENED)
        runCurrent()
        assertTrue(TipId.DIRECTION in settings.seenTipsFlow.value)
        assertNull(vm.tip.value) // con la hoja abierta, nada
        assertEquals(listOf(null, TipId.DIRECTION, null), tips) // "tocar" no se asomó (ni se anunció) al abrir la hoja
        vm.setTipBlocked(false) // se cerró la hoja
        runCurrent()
        assertEquals(TipId.TAP, vm.tip.value)
    }

    @Test fun `Entendido marca el consejo y uno ya visto no parpadea al abrir`() = runTest(dispatcher) {
        prefs.values["tips_seen"] = "tap"
        // La lectura del VM queda en espera (otro planificador que nadie avanza): aún no hay vistos.
        val vm = viewModel(tipsIo = StandardTestDispatcher(TestCoroutineScheduler()))
        vm.onResourceShown("c1.xhtml")
        runCurrent()
        assertFalse(settings.seenTipsLoaded.value)
        assertNull(vm.tip.value) // aún sin leer los vistos: nada (no parpadea "tocar")
        settings.loadSeenTips(dispatcher); runCurrent()
        assertNull(vm.tip.value) // TAP ya visto; nada más elegible
        vm.onChapterEnd(); runCurrent()
        assertEquals(TipId.CHAPTER_EDGE, vm.tip.value)
        vm.dismissTip(); runCurrent()
        assertTrue(TipId.CHAPTER_EDGE in settings.seenTipsFlow.value)
        assertNull(vm.tip.value)
    }

    @Test fun `bloqueado no muestra nada y al desbloquear vuelve el mismo`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onResourceShown("c1.xhtml"); runCurrent()
        vm.setTipBlocked(true); runCurrent()
        assertNull(vm.tip.value)
        vm.setTipBlocked(false); runCurrent()
        assertEquals(TipId.TAP, vm.tip.value)
        assertFalse(TipId.TAP in settings.seenTipsFlow.value) // ocultarlo no lo marca
    }

    @Test fun `un evento con el consejo bloqueado no marca nada`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onResourceShown("c1.xhtml"); runCurrent()
        vm.setTipOnScreen(true)
        vm.setTipBlocked(true); runCurrent()
        vm.onTipEvent(TipEvent.TAPPED); runCurrent()
        assertFalse(TipId.TAP in settings.seenTipsFlow.value)
        vm.setTipBlocked(false); runCurrent()
        assertEquals(TipId.TAP, vm.tip.value)
    }

    @Test fun `un evento no marca un consejo que no esta en pantalla`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onResourceShown("c1.xhtml"); runCurrent()
        assertEquals(TipId.TAP, vm.tip.value)
        vm.setTipOnScreen(false) // p. ej. barras ocultas
        vm.onTipEvent(TipEvent.TAPPED); runCurrent()
        assertFalse(TipId.TAP in settings.seenTipsFlow.value)
        vm.setTipOnScreen(true)
        vm.onTipEvent(TipEvent.TAPPED); runCurrent()
        assertTrue(TipId.TAP in settings.seenTipsFlow.value)
    }

    @Test fun `cambiar de capitulo cierra el consejo del borde`() = runTest(dispatcher) {
        prefs.values["tips_seen"] = "tap"
        val vm = viewModel()
        vm.onResourceShown("c1.xhtml"); vm.onChapterEnd(); runCurrent()
        assertEquals(TipId.CHAPTER_EDGE, vm.tip.value)
        vm.setTipOnScreen(true)
        vm.onResourceShown("c2.xhtml"); runCurrent()
        assertTrue(TipId.CHAPTER_EDGE in settings.seenTipsFlow.value)
        assertNull(vm.tip.value)
    }

    @Test fun `cambiar de capitulo sin verlo no lo marca y olvida el final del anterior`() = runTest(dispatcher) {
        prefs.values["tips_seen"] = "tap"
        val vm = viewModel()
        vm.onResourceShown("c1.xhtml"); vm.onChapterEnd(); runCurrent()
        assertEquals(TipId.CHAPTER_EDGE, vm.tip.value)
        assertFalse(vm.wantsChapterEdge()) // ya se llegó al final: la pantalla no vuelve a medir
        vm.onResourceShown("c2.xhtml"); runCurrent() // nunca estuvo en pantalla
        assertFalse(TipId.CHAPTER_EDGE in settings.seenTipsFlow.value)
        assertNull(vm.tip.value) // el final era del capítulo anterior
        assertTrue(vm.wantsChapterEdge())
    }

    @Test fun `abrir Aa marca ocultar y modos sin mostrar otro`() = runTest(dispatcher) {
        prefs.values["tips_seen"] = "tap"
        val vm = viewModel()
        val tips = mutableListOf<TipId?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tip.toList(tips) }
        vm.onResourceShown("c1.xhtml")
        vm.onTap("c1.xhtml", listOf(p(0, "Hello world.")))
        advanceUntilIdle()
        assertEquals(TipId.HIDE_AND_MODES, vm.tip.value)
        vm.onChapterEnd(); runCurrent() // el del borde espera su turno
        assertEquals(TipId.HIDE_AND_MODES, vm.tip.value)
        vm.setTipOnScreen(true)
        vm.onSheetOpened(TipEvent.SETTINGS_OPENED); runCurrent()
        assertTrue(TipId.HIDE_AND_MODES in settings.seenTipsFlow.value)
        assertNull(vm.tip.value)
        assertFalse(TipId.CHAPTER_EDGE in tips, "el del borde se asomó al abrir la hoja: $tips")
        vm.setTipBlocked(false); runCurrent()
        assertEquals(TipId.CHAPTER_EDGE, vm.tip.value)
    }

    @Test fun `la traduccion que llega con otro capitulo a la vista no trae ocultar y modos`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onResourceShown("c1.xhtml"); runCurrent()
        vm.setTipOnScreen(true)
        vm.onTap("c1.xhtml", listOf(p(0, "Hello world.")))
        vm.onResourceShown("c2.xhtml") // se fue antes de que llegara la traducción
        advanceUntilIdle()
        assertTrue(TipId.TAP in settings.seenTipsFlow.value)
        assertNull(vm.tip.value)
    }

    @Test fun `el borde solo se mide con los vistos leidos y sin verlo`() = runTest(dispatcher) {
        val vm = viewModel(tipsIo = StandardTestDispatcher(TestCoroutineScheduler()))
        assertFalse(vm.wantsChapterEdge()) // sin los vistos todavía
        settings.loadSeenTips(dispatcher)
        assertTrue(vm.wantsChapterEdge())
        settings.markTipSeen(TipId.CHAPTER_EDGE)
        assertFalse(vm.wantsChapterEdge())
    }

    // ---------- Ola final de arreglos (3c-3) ----------

    // m1: un consejo que sigue pendiente no se vuelve a decir cada vez que se cierra una hoja (spec §4).
    @Test fun `un consejo se anuncia una sola vez aunque vuelva tras una hoja`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onResourceShown("c1.xhtml"); runCurrent()
        assertEquals(TipId.TAP, vm.tip.value)
        assertTrue(vm.shouldAnnounceTip(TipId.TAP))
        vm.onTipAnnounced(TipId.TAP)
        vm.setTipBlocked(true); runCurrent()
        vm.setTipBlocked(false); runCurrent()
        assertEquals(TipId.TAP, vm.tip.value) // vuelve el mismo…
        assertFalse(vm.shouldAnnounceTip(TipId.TAP)) // …pero callado
        assertTrue(vm.shouldAnnounceTip(TipId.HIDE_AND_MODES)) // otro consejo sí se dice
    }

    // m3: "Volver a ver los consejos" a mitad de sesión empieza por el principio (y se vuelven a decir).
    @Test fun `volver a ver los consejos en el Lector empieza otra vez por tocar`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onResourceShown("c1.xhtml"); runCurrent()
        vm.setTipOnScreen(true)
        vm.onTipAnnounced(TipId.TAP)
        vm.onTap("c1.xhtml", listOf(p(0, "Hello world.")))
        advanceUntilIdle()
        assertEquals(TipId.HIDE_AND_MODES, vm.tip.value)
        vm.onChapterEnd(); runCurrent()
        settings.resetTips(); runCurrent()
        assertEquals(TipId.TAP, vm.tip.value)
        assertTrue(vm.shouldAnnounceTip(TipId.TAP))
        assertTrue(vm.wantsChapterEdge()) // el final del capítulo se vuelve a medir
    }

    // m2: en un libro en español, tocar con "Este libro se traduce…" arriba y ocultar la tarjeta no deja después
    // "Toca otra vez para ocultarla" sobre una tarjeta que ya no está.
    @Test fun `tocar y ocultar con la direccion arriba no deja ocultar y modos`() = runTest(dispatcher) {
        val vm = viewModel(languages = listOf("es"))
        vm.onResourceShown("c1.xhtml"); advanceUntilIdle()
        assertEquals(TipId.DIRECTION, vm.tip.value)
        vm.setTipOnScreen(true)
        vm.onTap("c1.xhtml", listOf(p(0, "Hola mundo.")))
        advanceUntilIdle()
        assertEquals(TipId.DIRECTION, vm.tip.value)
        vm.onTap("c1.xhtml", listOf(p(0, "Hola mundo."))) // ocultar
        runCurrent()
        vm.dismissTip(); runCurrent()
        assertEquals(TipId.TAP, vm.tip.value)
    }

    // m2: si la tarjeta sigue abierta, "Toca otra vez para ocultarla" sí vale.
    @Test fun `tocar con la direccion arriba y dejar la tarjeta trae ocultar y modos`() = runTest(dispatcher) {
        val vm = viewModel(languages = listOf("es"))
        vm.onResourceShown("c1.xhtml"); advanceUntilIdle()
        vm.setTipOnScreen(true)
        vm.onTap("c1.xhtml", listOf(p(0, "Hola mundo.")))
        advanceUntilIdle()
        vm.dismissTip(); runCurrent()
        assertEquals(TipId.HIDE_AND_MODES, vm.tip.value)
    }

    // m2: cambiar la dirección cierra las tarjetas: ya no hay ninguna que ocultar.
    @Test fun `cambiar la direccion cierra las tarjetas y no deja ocultar y modos`() = runTest(dispatcher) {
        prefs.values["tips_seen"] = "tap,direction" // la dirección nueva es español → inglés: su consejo, ya visto
        val vm = viewModel()
        vm.onResourceShown("c1.xhtml"); runCurrent()
        vm.onTap("c1.xhtml", listOf(p(0, "Hello world.")))
        advanceUntilIdle()
        assertEquals(TipId.HIDE_AND_MODES, vm.tip.value)
        vm.setDirection(esEn); runCurrent()
        assertNull(vm.tip.value)
    }

    // m6 (spec §8): salir del Lector sin cerrar el consejo no lo marca visto.
    @Test fun `salir sin cerrar el consejo no lo marca`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onResourceShown("c1.xhtml"); runCurrent()
        assertEquals(TipId.TAP, vm.tip.value)
        vm.setTipOnScreen(true)
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        }
        ViewModelProvider(store, factory)[ReaderViewModel::class.java]
        vm.flush()
        store.clear()
        advanceUntilIdle()
        assertTrue(settings.seenTipsFlow.value.isEmpty())
        assertEquals(null, prefs.values["tips_seen"])
    }
}
