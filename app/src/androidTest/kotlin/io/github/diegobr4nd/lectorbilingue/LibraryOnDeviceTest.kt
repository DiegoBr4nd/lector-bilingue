package io.github.diegobr4nd.lectorbilingue

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.books.Book
import io.github.diegobr4nd.lectorbilingue.books.ImportResult
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.ui.library.LanguageNotice
import io.github.diegobr4nd.lectorbilingue.ui.library.LibraryContent
import io.github.diegobr4nd.lectorbilingue.ui.library.LibraryUiState
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** La Biblioteca con datos de muestra (TalkBack simulado por semántica) y una importación real con portada. */
@RunWith(AndroidJUnit4::class)
class LibraryOnDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val id = "00000000-0000-0000-0000-0000000000aa"
    private val sample = LibraryUiState(
        books = listOf(
            Book(id, "Libro de muestra", "Autora Inventada", null, 0.42f, null),
            Book("00000000-0000-0000-0000-0000000000bb", "", null, null, 0f, null),
        ),
        loaded = true,
    )

    private fun show(
        state: LibraryUiState,
        onAdd: () -> Unit = {},
        onOpen: (String) -> Unit = {},
        onDelete: (String) -> Unit = {},
        onLanguages: () -> Unit = {},
    ) = rule.setContent {
        LectorTheme {
            LibraryContent(
                state = state, onAdd = onAdd, onOpen = onOpen, onDelete = onDelete,
                onLanguages = onLanguages, onDeveloper = null, snackbar = SnackbarHostState(),
            )
        }
    }

    @Test
    fun borrarEstaEnLasAccionesDeAccesibilidad() {
        var deleted: String? = null
        show(sample, onDelete = { deleted = it })
        val row = rule.onNodeWithText("Libro de muestra")
        row.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        row.assertHeightIsAtLeast(88.dp)
        // Lo mismo que hace TalkBack al elegir la acción "Borrar libro".
        val action = row.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Borrar libro" }
        rule.runOnIdle { action.action() }
        rule.onNodeWithText("¿Borrar «Libro de muestra»?").assertIsDisplayed()
        assertNull(deleted) // Nada se borra sin confirmar.
        rule.onNode(hasText("Borrar") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.runOnIdle { assertEquals(id, deleted) }
    }

    @Test
    fun vaciaMuestraElBotonGrande() {
        var added = 0
        show(LibraryUiState(loaded = true), onAdd = { added++ })
        rule.onNodeWithText("Añade tu primer libro EPUB").assertIsDisplayed()
        rule.onNodeWithText("Añadir libro").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals(1, added) }
    }

    @Test
    fun tocarAbreElLibroYElPorcentajeSeLeeComoTexto() {
        var opened: String? = null
        show(sample, onOpen = { opened = it })
        rule.onNodeWithText("42 % leído", substring = true).assertExists()
        rule.onNodeWithText("Libro sin título").assertExists() // Título vacío: texto propio.
        rule.onNodeWithContentDescription("Portada de Libro de muestra").assertExists()
        rule.onNodeWithText("Libro de muestra").performClick()
        rule.runOnIdle { assertEquals(id, opened) }
    }

    @Test
    fun elAvisoDeIdiomasLlevaAIdiomas() {
        var languages = 0
        show(sample.copy(notice = LanguageNotice.NoLanguages), onLanguages = { languages++ })
        rule.onNodeWithText("Aún no tienes idiomas · Descargar").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription("Más opciones").assertHeightIsAtLeast(48.dp)
        rule.runOnIdle { assertEquals(1, languages) }
    }

    @Test
    fun importarEpubRealConPortadaGuardaPng() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val app = ctx.applicationContext as LectorApp
        val png = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                .compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val dir = File(ctx.cacheDir, "library_test").apply { deleteRecursively(); mkdirs() }
        try {
            val epub = TestEpub.build(dir, "con-portada.epub") {
                entry("OEBPS/cover.png", png)
                replace("OEBPS/content.opf", coverOpf().toByteArray())
            }
            val r = app.books.import({ epub.inputStream() }, "con-portada.epub")
            val bookId = (r as ImportResult.Ok).bookId
            try {
                val book = app.books.get(bookId)!!
                assertTrue(book.coverFile!!.exists())
                assertEquals("Libro con portada", book.title)
            } finally {
                app.books.delete(bookId)
            }
            assertNull(app.books.get(bookId))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun coverOpf() = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">urn:uuid:00000000-0000-0000-0000-000000000002</dc:identifier>
    <dc:title>Libro con portada</dc:title>
    <dc:language>es</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/></spine>
</package>"""
}
