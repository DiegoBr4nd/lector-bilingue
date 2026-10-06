package io.github.diegobr4nd.lectorbilingue.ui.library

import android.content.res.Configuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import io.github.diegobr4nd.lectorbilingue.books.Book
import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme

/** Claro y oscuro, letra normal y al 200 %, teléfono (360 dp) y tableta (840 dp). */
@Preview(name = "Claro 360", widthDp = 360, heightDp = 720, showBackground = true)
@Preview(name = "Oscuro 360", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Claro 360 letra 200", widthDp = 360, heightDp = 720, fontScale = 2f, showBackground = true)
@Preview(
    name = "Oscuro 360 letra 200",
    widthDp = 360,
    heightDp = 720,
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Preview(name = "Claro 840", widthDp = 840, heightDp = 800, showBackground = true)
@Preview(name = "Oscuro 840", widthDp = 840, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
private annotation class LibraryPreviews

// Libros inventados. Sin portada en las vistas previas (no hay archivos): se ve el recuadro con la inicial.
private val sampleBooks = listOf(
    Book(
        "00000000-0000-0000-0000-000000000001",
        "La ciudad de los faros apagados y otras historias de marineros que nunca volvieron a puerto, contadas hoy por sus nietos",
        "Autora Inventada",
        null, 0.42f, null,
    ),
    Book("00000000-0000-0000-0000-000000000002", "El jardín de cobre", "Autor de Muestra", null, 1f, null),
    Book("00000000-0000-0000-0000-000000000003", "", null, null, 0f, null),
)

@Composable
private fun Muestra(state: LibraryUiState) = LectorTheme {
    LibraryContent(
        state = state,
        onAdd = {},
        onOpen = {},
        onDelete = {},
        onLanguages = {},
        onDeveloper = {},
        snackbar = remember { SnackbarHostState() },
    )
}

@LibraryPreviews
@Composable
private fun ConLibros() = Muestra(LibraryUiState(books = sampleBooks, loaded = true))

@LibraryPreviews
@Composable
private fun Vacia() = Muestra(LibraryUiState(loaded = true))

@LibraryPreviews
@Composable
private fun VaciaSinIdiomas() = Muestra(LibraryUiState(loaded = true, notice = LanguageNotice.NoLanguages))

@LibraryPreviews
@Composable
private fun Cargando() = Muestra(LibraryUiState())

@LibraryPreviews
@Composable
private fun Importando() = Muestra(LibraryUiState(books = sampleBooks, loaded = true, importing = true))

@LibraryPreviews
@Composable
private fun ImportandoPrimerLibro() = Muestra(LibraryUiState(loaded = true, importing = true))

@LibraryPreviews
@Composable
private fun ConAvisoDeDescarga() =
    Muestra(LibraryUiState(books = sampleBooks, loaded = true, notice = LanguageNotice.Downloading("en-es", 45)))

@LibraryPreviews
@Composable
private fun ConAvisoDeFallo() = Muestra(LibraryUiState(books = sampleBooks, loaded = true, notice = LanguageNotice.Failed("en-es")))

@LibraryPreviews
@Composable
private fun ConAvisoDeMotorAusente() =
    Muestra(LibraryUiState(books = sampleBooks, loaded = true, notice = LanguageNotice.Missing("en-es", EngineKind.FAST)))

@LibraryPreviews
@Composable
private fun AbriendoUnLibro() =
    Muestra(LibraryUiState(books = sampleBooks, loaded = true, openingId = sampleBooks[1].id))

@LibraryPreviews
@Composable
private fun NoSePudoAbrir() = LectorTheme { OpenFailedDialog(onClose = {}, onRemove = {}) }
