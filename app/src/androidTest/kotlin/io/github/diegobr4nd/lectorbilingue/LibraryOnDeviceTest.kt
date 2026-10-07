package io.github.diegobr4nd.lectorbilingue

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
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
import kotlin.test.assertFalse
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
    fun laFilaSeLeeUnaSolaVezSinLaInicialYDiceQueAbre() {
        show(sample)
        // Lo que TalkBack lee de la fila: título, autora y %; sin "Portada de…" (repetía el título) ni la inicial suelta.
        val row = rule.onNodeWithText("Libro de muestra").fetchSemanticsNode().config
        assertEquals(listOf("Libro de muestra", "Autora Inventada", "42 % leído"), row[SemanticsProperties.Text].map { it.text })
        assertFalse(row.contains(SemanticsProperties.ContentDescription), "la fila no debe llevar descripción extra")
        assertEquals("abrir el libro", row[SemanticsActions.OnClick].label)
        val untitled = rule.onNodeWithText("Libro sin título").fetchSemanticsNode().config
        assertEquals(listOf("Libro sin título", "0 % leído"), untitled[SemanticsProperties.Text].map { it.text })
    }

    @Test
    fun elAvisoDiceQueAbreIdiomas() {
        show(sample.copy(notice = LanguageNotice.NoLanguages))
        val notice = rule.onNodeWithText("Aún no tienes idiomas · Descargar").fetchSemanticsNode().config
        assertEquals("abrir Idiomas", notice[SemanticsActions.OnClick].label)
    }

    @Test
    fun laBarraSuperiorEsDelColorDelFondo() {
        var background = ComposeColor.Unspecified
        rule.setContent {
            LectorTheme {
                background = MaterialTheme.colorScheme.background
                LibraryContent(
                    state = sample, onAdd = {}, onOpen = {}, onDelete = {},
                    onLanguages = {}, onDeveloper = null, snackbar = SnackbarHostState(),
                )
            }
        }
        // Un punto de la barra entre el título y el menú, a la altura del título.
        val title = rule.onNodeWithText("Biblioteca").fetchSemanticsNode().boundsInRoot
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val bar = image.getPixel((image.width * 0.6f).toInt(), title.center.y.toInt())
        assertEquals(background.toArgb(), bar, "la barra se ve como una franja de otro tono")
    }

    @Test
    fun vaciaConLetraGrandeAnadirSeVeSinDesplazar() = assertAddVisibleWithoutScrolling(fontScale = 2f)

    // Diseño B12: al 130 % la vista vacía todavía lleva dibujo y márgenes grandes (el modo compacto empieza en 150 %).
    @Test
    fun vaciaConLetraAl130AnadirSeVeSinDesplazar() = assertAddVisibleWithoutScrolling(fontScale = 1.3f)

    /**
     * Teléfono pequeño (360 × 640 dp) con la letra a [fontScale] y el aviso de idiomas arriba: "Añadir libro"
     * cabe ENTERO en la pantalla sin desplazar (diseño B10). `assertIsDisplayed` no basta: pasa aunque solo se
     * vea un trozo del botón. Se usa la posición y el tamaño sin recortar (no `boundsInRoot`, que la lista recorta).
     */
    private fun assertAddVisibleWithoutScrolling(fontScale: Float) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                LectorTheme {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        LibraryContent(
                            state = LibraryUiState(loaded = true, notice = LanguageNotice.NoLanguages), onAdd = {}, onOpen = {},
                            onDelete = {}, onLanguages = {}, onDeveloper = null, snackbar = SnackbarHostState(),
                        )
                    }
                }
            }
        }
        val button = rule.onNodeWithText("Añadir libro").assertIsDisplayed().assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
        with(rule.density) {
            val top = button.positionInRoot.y.toDp()
            val bottom = (button.positionInRoot.y + button.size.height).toDp()
            assertTrue(top >= 0.dp, "al ${fontScale * 100} % el botón empieza en $top")
            assertTrue(bottom <= 640.dp, "al ${fontScale * 100} % el botón termina en $bottom, fuera de los 640 dp")
        }
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
