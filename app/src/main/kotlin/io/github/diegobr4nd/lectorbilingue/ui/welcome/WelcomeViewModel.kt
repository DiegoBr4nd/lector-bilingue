package io.github.diegobr4nd.lectorbilingue.ui.welcome

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que cambia en la Bienvenida además del catálogo: casillas tocadas, mensaje y trabajos en curso. */
data class WelcomeUiState(
    /** Pares que la persona marcó o desmarcó; los demás usan su valor por defecto. */
    val toggled: Map<String, Boolean> = emptyMap(),
    val catalogMessage: ModelMessage? = null,
    val refreshing: Boolean = true,
    val importing: Boolean = false,
    val message: ModelMessage? = null,
    /** La importación salió bien y la pantalla aún no lo atendió (decide ella según el paso en que esté). */
    val importOk: Boolean = false,
)

/**
 * Estado del paso 3. El paso actual vive en la ruta; aquí solo lo que debe sobrevivir al girar la pantalla.
 * No guarda texto de libros; los mensajes son fijos.
 */
class WelcomeViewModel(private val hub: ModelHubApi) : ViewModel() {
    private val _state = MutableStateFlow(WelcomeUiState())
    val state: StateFlow<WelcomeUiState> = _state.asStateFlow()

    val pairs: StateFlow<List<PairStatus>> get() = hub.pairs
    val loaded: StateFlow<Boolean> get() = hub.loaded

    init {
        refresh()
    }

    fun totalRam(): Long = hub.totalRamBytes()

    /** Pide el catálogo otra vez (al entrar y con "Intentar de nuevo"). */
    fun refresh() {
        _state.update { it.copy(refreshing = true, message = null) }
        viewModelScope.launch {
            val result = hub.refresh()
            _state.update { it.copy(refreshing = false, catalogMessage = result) }
        }
    }

    fun toggle(pair: String, selected: Boolean) = _state.update { it.copy(toggled = it.toggled + (pair to selected)) }

    /** Encola la descarga de cada modelo elegido. */
    fun download(modelIds: List<String>) = modelIds.forEach(hub::download)

    /**
     * Importa el .zip. Si sale bien, deja [WelcomeUiState.importOk] para que la pantalla actual decida
     * (el modelo de vista no navega); si no, deja el mensaje en pantalla.
     */
    fun import(uri: Uri) {
        if (_state.value.importing) return
        _state.update { it.copy(importing = true, message = null) }
        viewModelScope.launch {
            val result = hub.import(uri)
            _state.update {
                if (result == ModelMessage.IMPORT_OK) {
                    it.copy(importing = false, importOk = true)
                } else {
                    it.copy(importing = false, message = result)
                }
            }
        }
    }

    /** La pantalla atendió la importación; si no estaba en el paso 3, el aviso de éxito queda para cuando vuelva. */
    fun ackImport(showSuccess: Boolean) =
        _state.update { it.copy(importOk = false, message = if (showSuccess) ModelMessage.IMPORT_OK else it.message) }
}
