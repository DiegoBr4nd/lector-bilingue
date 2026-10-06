package io.github.diegobr4nd.lectorbilingue.ui.library

import io.github.diegobr4nd.lectorbilingue.books.BookFiles
import io.github.diegobr4nd.lectorbilingue.books.BookImporter
import io.github.diegobr4nd.lectorbilingue.books.BookMetadata
import io.github.diegobr4nd.lectorbilingue.books.BookRepository
import io.github.diegobr4nd.lectorbilingue.books.MetadataRead
import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val id = "123e4567-e89b-12d3-a456-426614174000"
    private val dao = FakeBookDao()
    private val events = mutableListOf<LibraryEvent>()
    private val notice = MutableStateFlow<LanguageNotice?>(null)
    private val closedIds = mutableListOf<String>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /**
     * El estado usa stateIn(WhileSubscribed): sin alguien que lo observe, state.value se queda en el valor inicial
     * y las aserciones no probarían nada. Por eso se suscribe un colector, como hace la pantalla.
     */
    private suspend fun TestScope.viewModel(
        opener: suspend (String) -> Boolean = { true },
        readMetadata: suspend (java.io.File) -> MetadataRead = { MetadataRead.Ok(BookMetadata("T", null, null)) },
        close: (String) -> Unit = { closedIds += it },
    ): LibraryViewModel {
        val files = BookFiles(tmp.root)
        val importer = BookImporter(files, dao, readMetadata = readMetadata, saveCover = { _, _ -> })
        dao.insert(BookEntity(id, "T", null, null, 1, null, 0f, null))
        return LibraryViewModel(BookRepository(dao, files, importer), opener, notice, close).also { vm ->
            backgroundScope.launch(dispatcher) { vm.events.toList(events) }
            backgroundScope.launch(dispatcher) { vm.state.collect {} }
        }
    }

    @Test fun `el estado refleja los libros y el aviso`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.loaded)
        assertEquals(listOf(id), vm.state.value.books.map { it.id })
        notice.value = LanguageNotice.NoLanguages
        advanceUntilIdle()
        assertEquals(LanguageNotice.NoLanguages, vm.state.value.notice)
    }

    @Test fun `cancelar el selector no hace nada`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.import(source = null, name = null)
        advanceUntilIdle()
        assertFalse(vm.state.value.importing)
        assertTrue(events.isEmpty())
    }

    @Test fun `error de importacion emite el mensaje`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.import(source = { "no zip".byteInputStream() }, name = "x.epub")
        untilReal { !vm.state.value.importing }
        assertEquals(listOf<LibraryEvent>(LibraryEvent.Message(LibraryMessage.NOT_EPUB)), events)
        assertFalse(vm.state.value.importing)
    }

    @Test fun `mientras importa el estado dice importando y al terminar aparece el libro`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val reading = CompletableDeferred<Unit>()
        val vm = viewModel(readMetadata = {
            reading.complete(Unit)
            gate.await()
            MetadataRead.Ok(BookMetadata("Nuevo", null, null))
        })
        advanceUntilIdle()
        vm.import(source = { minimalEpub().inputStream() }, name = "nuevo.epub")
        // El importador copia en Dispatchers.IO (hilo real): se espera a que llegue a leer los metadatos.
        reading.await()
        advanceUntilIdle()
        assertTrue(vm.state.value.importing)
        // Un segundo intento mientras importa se ignora.
        vm.import(source = { minimalEpub().inputStream() }, name = "otro.epub")
        gate.complete(Unit)
        // Espera a que el importador (en IO) termine y el ViewModel baje la bandera.
        untilReal { !vm.state.value.importing }
        assertEquals(2, vm.state.value.books.size)
        assertTrue(events.isEmpty())
    }

    @Test fun `abrir con exito emite Open y marca abierto`() = runTest(dispatcher) {
        val vm = viewModel(opener = { true })
        vm.open(id)
        advanceUntilIdle()
        assertEquals(listOf<LibraryEvent>(LibraryEvent.Open(id)), events)
        assertNotNull(dao.get(id)!!.lastOpenedAt)
        assertTrue(closedIds.isEmpty()) // Abierto con éxito: no se suelta.
        assertNull(vm.state.value.openingId)
    }

    @Test fun `si lo borraron mientras abria no se abre y se cierra`() = runTest(dispatcher) {
        val closed = mutableListOf<String>()
        val vm = viewModel(
            opener = { bookId ->
                dao.delete(bookId) // Borrado mientras Readium abría el archivo.
                true
            },
            close = { closed += it },
        )
        vm.open(id)
        advanceUntilIdle()
        assertTrue(events.isEmpty())
        assertEquals(listOf(id), closed)
        assertNull(vm.state.value.openingId)
    }

    @Test fun `abrir fallido emite OpenFailed`() = runTest(dispatcher) {
        val vm = viewModel(opener = { false })
        vm.open(id)
        advanceUntilIdle()
        assertEquals(listOf<LibraryEvent>(LibraryEvent.OpenFailed(id)), events)
        assertNull(vm.state.value.openingId)
    }

    @Test fun `un segundo toque mientras abre se ignora`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Boolean>()
        val vm = viewModel(opener = { gate.await() })
        vm.open(id); advanceUntilIdle()
        assertEquals(id, vm.state.value.openingId)
        vm.open(id)
        gate.complete(true); advanceUntilIdle()
        assertEquals(1, events.size)
        assertNull(vm.state.value.openingId)
    }

    @Test fun `borrar quita el libro`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.delete(id)
        untilReal { vm.state.value.books.isEmpty() }
        assertTrue(vm.state.value.books.isEmpty())
    }

    /**
     * El repositorio trabaja en Dispatchers.IO (hilos reales, fuera del reloj virtual): se avanza el reloj y se
     * espera un poco hasta que se cumpla [done], con tope de 5 s para no colgar la prueba.
     */
    private fun TestScope.untilReal(done: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (true) {
            advanceUntilIdle()
            if (done()) return
            check(System.nanoTime() < end) { "no terminó a tiempo" }
            Thread.sleep(5)
        }
    }

    /** EPUB mínimo que pasa la revisión del archivo (mimetype sin comprimir y container.xml). */
    private fun minimalEpub(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size
                    crc = CRC32().apply { update(mime) }.value
                },
            )
            zip.write(mime); zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/content.opf")); zip.write("<package/>".toByteArray()); zip.closeEntry()
        }
        return out.toByteArray()
    }
}
