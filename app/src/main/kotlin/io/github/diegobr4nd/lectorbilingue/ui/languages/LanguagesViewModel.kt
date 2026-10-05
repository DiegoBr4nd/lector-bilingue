package io.github.diegobr4nd.lectorbilingue.ui.languages

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.EnginePreference
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Una acción que espera la respuesta del diálogo de confirmación. [row] es una copia de la fila tocada. */
data class PendingAction(val pair: String, val row: UiRow, val confirm: Confirm)

/** Lo que cambia en Idiomas además del catálogo: avisos, trabajos en curso y el diálogo abierto. */
data class LanguagesUiState(
    val preference: EnginePreference = EnginePreference.AUTO,
    val preferenceLoaded: Boolean = false,
    /** El refresco del catálogo (con el tope de 5 s del gestor) sigue en marcha. */
    val searching: Boolean = true,
    val catalogMessage: ModelMessage? = null,
    val message: ModelMessage? = null,
    val busy: Boolean = false,
    val pending: PendingAction? = null,
)

/**
 * Estado de Idiomas. Solo guarda mensajes fijos (nunca texto de excepciones ni de libros).
 * Borrar e importar bloquean los botones mientras trabajan ([LanguagesUiState.busy]).
 */
class LanguagesViewModel(private val hub: ModelHubApi, private val settings: AppSettings) : ViewModel() {
    private val _state = MutableStateFlow(LanguagesUiState())
    val state: StateFlow<LanguagesUiState> = _state.asStateFlow()

    val pairs: StateFlow<List<PairStatus>> get() = hub.pairs
    val loaded: StateFlow<Boolean> get() = hub.loaded

    val cards: StateFlow<List<LanguageCard>> =
        combine(hub.pairs, _state) { pairs, s -> LanguagesRules.rows(pairs, hub.totalRamBytes(), s.preference) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Este modelo de vista ya leyó el motor y buscó idiomas al menos una vez. */
    private var started = false

    init {
        // El motor elegido viene de los ajustes (flujo compartido con Inicio): se refleja aquí en cuanto cambia.
        viewModelScope.launch {
            combine(settings.enginePreferenceFlow, settings.enginePreferenceLoaded) { p, l -> p to l }
                .collect { (p, l) -> _state.update { it.copy(preference = p, preferenceLoaded = l) } }
        }
    }

    fun autoLine(): AutoLine = LanguagesRules.autoLine(hub.totalRamBytes())

    /** Al abrir la pantalla: lee el motor elegido, borra avisos viejos y busca idiomas nuevos. */
    fun onEnter(newVisit: Boolean) {
        if (!LanguagesRules.shouldStartVisit(started, newVisit)) return
        started = true
        viewModelScope.launch { settings.loadEnginePreference() }
        refresh()
    }

    /** Pide el catálogo otra vez (al entrar y con "Intentar de nuevo"). */
    fun refresh() {
        _state.update { it.copy(searching = true, message = null) }
        viewModelScope.launch {
            val result = hub.refresh()
            _state.update { it.copy(searching = false, catalogMessage = result) }
        }
    }

    fun selectEngine(value: EnginePreference) {
        settings.enginePreference = value
    }

    /** Pide confirmar si hace falta; si no, ejecuta la acción de una vez. */
    fun request(action: RowAction, pair: String, row: UiRow) {
        val confirm = LanguagesRules.confirmFor(action, row)
        if (confirm == null) run(action, row) else _state.update { it.copy(pending = PendingAction(pair, row, confirm)) }
    }

    fun dismissConfirm() = _state.update { it.copy(pending = null) }

    fun confirm() {
        val p = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        run(p.confirm.action, p.row, p.pair)
    }

    private fun run(action: RowAction, row: UiRow, pair: String? = null) {
        when (action) {
            RowAction.DOWNLOAD -> {
                _state.update { it.copy(message = null) }
                hub.download(row.modelId)
            }
            RowAction.DELETE -> {
                if (pair == null || _state.value.busy) return
                _state.update { it.copy(busy = true, message = null) }
                viewModelScope.launch {
                    val result = hub.delete(row.engine, pair)
                    _state.update { it.copy(busy = false, message = result) }
                }
            }
        }
    }

    /** Descarga por id (respuesta tardía del permiso de notificaciones); el gestor ignora los duplicados. */
    fun downloadById(modelId: String) = hub.download(modelId)

    fun cancel(modelId: String) = hub.cancel(modelId)

    fun import(uri: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = hub.import(uri)
            _state.update { it.copy(busy = false, message = result) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
