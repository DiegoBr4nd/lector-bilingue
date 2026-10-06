package io.github.diegobr4nd.lectorbilingue.ui.reader

import io.github.diegobr4nd.lectorbilingue.books.BookFiles
import io.github.diegobr4nd.lectorbilingue.books.BookImporter
import io.github.diegobr4nd.lectorbilingue.books.BookMetadata
import io.github.diegobr4nd.lectorbilingue.books.BookRepository
import io.github.diegobr4nd.lectorbilingue.books.MetadataRead
import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import io.github.diegobr4nd.lectorbilingue.ui.library.FakeBookDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun viewModel(): ReaderViewModel {
        val files = BookFiles(tmp.root)
        dao.insert(BookEntity(id, "T", null, null, 1, null, 0f, null))
        val importer = BookImporter(files, dao, readMetadata = { MetadataRead.Ok(BookMetadata("T", null, null)) }, saveCover = { _, _ -> })
        return ReaderViewModel(id, BookRepository(dao, files, importer))
    }

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
}
