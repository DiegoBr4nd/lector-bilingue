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
import io.github.diegobr4nd.lectorbilingue.data.FakeEngine
import io.github.diegobr4nd.lectorbilingue.data.FakeEngineProvider
import io.github.diegobr4nd.lectorbilingue.data.FakeTranslationDao
import io.github.diegobr4nd.lectorbilingue.data.TranslationService
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.ui.library.FakeBookDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** El VM con un [TranslationService] real (hilo de prueba) sobre el motor falso. [engine] reemplaza al de [provider]. */
    private suspend fun TestScope.viewModel(
        engine: FakeEngine? = null,
        languages: List<String> = listOf("en"),
        direction: String? = null,
    ): ReaderViewModel {
        val files = BookFiles(tmp.root)
        dao.insert(BookEntity(id, "T", null, null, 1, null, 0f, null, direction))
        val importer = BookImporter(files, dao, readMetadata = { MetadataRead.Ok(BookMetadata("T", null, null)) }, saveCover = { _, _ -> })
        val p = if (engine == null) provider else FakeEngineProvider(opus = engine, installedEngines = provider.installedEngines)
        service = TranslationService(p, cache, clock = { 1L }, worker = dispatcher, scope = this) // no backgroundScope: advanceUntilIdle no espera esas tareas
        return ReaderViewModel(id, BookRepository(dao, files, importer), service, languages)
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
}
