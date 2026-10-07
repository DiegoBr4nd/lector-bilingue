package io.github.diegobr4nd.lectorbilingue.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.books.Book
import io.github.diegobr4nd.lectorbilingue.books.BookRepository
import io.github.diegobr4nd.lectorbilingue.books.ImportResult
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Lo que dibuja la Biblioteca. [loaded] es false hasta la primera lectura de la base. */
data class LibraryUiState(
    val books: List<Book> = emptyList(),
    val loaded: Boolean = false,
    val importing: Boolean = false,
    val openingId: String? = null,
    val notice: LanguageNotice? = null,
)

/** Avisos de una sola vez (no son estado): un mensaje, abrir el Lector o avisar que el libro no abrió. */
sealed interface LibraryEvent {
    data class Message(val message: LibraryMessage) : LibraryEvent
    data class Open(val bookId: String) : LibraryEvent
    data class OpenFailed(val bookId: String) : LibraryEvent
}

/** Estado de la Biblioteca. No guarda ni registra texto de libros: solo ids y motivos. */
class LibraryViewModel(
    private val repo: BookRepository,
    private val opener: suspend (String) -> Boolean,
    notice: Flow<LanguageNotice?>,
    /** Suelta un libro que el [opener] dejó abierto en memoria (p. ej. `OpenBooks.close`). */
    private val close: (String) -> Unit,
) : ViewModel() {
    private val importing = MutableStateFlow(false)
    private val opening = MutableStateFlow<String?>(null)
    private val eventChannel = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<LibraryUiState> =
        combine(repo.books, importing, opening, notice) { books, imp, op, n -> LibraryUiState(books, true, imp, op, n) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /**
     * [source] es null si la persona cerró el selector sin elegir: no pasa nada. Una importación a la vez.
     * [name] da el nombre del archivo (título de respaldo); puede consultar el ContentResolver, por eso se pide
     * aquí, fuera del hilo principal.
     */
    fun import(source: (() -> InputStream?)?, name: () -> String?) {
        if (source == null || importing.value) return
        importing.value = true
        viewModelScope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) { name() }
                val r = repo.import(source, fileName)
                if (r is ImportResult.Error) eventChannel.send(LibraryEvent.Message(LibraryRules.message(r.reason)))
            } finally { importing.value = false }
        }
    }

    /**
     * Abre el libro (fuera de la pantalla) y avisa. Un segundo toque mientras abre se ignora.
     * Si lo borraron mientras se abría, se suelta y no se abre el Lector.
     */
    fun open(id: String) {
        if (opening.value != null) return
        opening.value = id
        viewModelScope.launch {
            try {
                if (opener(id)) {
                    if (repo.get(id) == null) {
                        close(id)
                        return@launch
                    }
                    repo.markOpened(id)
                    eventChannel.send(LibraryEvent.Open(id))
                } else {
                    eventChannel.send(LibraryEvent.OpenFailed(id))
                }
            } finally { opening.value = null }
        }
    }

    /** Suelta el libro si estaba abierto y lo borra. Si se estaba abriendo, [open] lo suelta al terminar. */
    fun delete(id: String) {
        close(id)
        viewModelScope.launch { repo.delete(id) }
    }
}

/** Aviso de idiomas a partir del gestor de modelos y del motor elegido. Nada hasta tener los dos cargados. */
fun languageNotices(hub: ModelHubApi, settings: AppSettings): Flow<LanguageNotice?> =
    combine(hub.pairs, settings.enginePreferenceFlow, hub.loaded, settings.enginePreferenceLoaded) { pairs, pref, a, b ->
        if (a && b) LibraryRules.notice(pairs, HomeRules.cards(pairs, hub.totalRamBytes(), pref)) else null
    }
