package io.github.diegobr4nd.lectorbilingue.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.EnginePreference
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Estado del Inicio: las tarjetas salen de los idiomas del gestor y del motor elegido en los ajustes.
 * No carga motores ni guarda texto de libros.
 */
class HomeViewModel(private val hub: ModelHubApi, private val settings: AppSettings) : ViewModel() {
    private val pref = MutableStateFlow(EnginePreference.AUTO)

    /** false hasta leer los ajustes (la primera lectura toca el disco, así que va fuera del hilo principal). */
    private val prefLoaded = MutableStateFlow(false)

    val loaded: StateFlow<Boolean> =
        combine(hub.loaded, prefLoaded) { a, b -> a && b }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val state: StateFlow<HomeState> =
        combine(hub.pairs, pref) { pairs, p -> HomeRules.cards(pairs, hub.totalRamBytes(), p) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState(emptyList(), showNoLanguages = false))

    init {
        reloadPreference()
    }

    /** Vuelve a leer el motor elegido (puede haber cambiado en Idiomas mientras este modelo seguía vivo). */
    fun reloadPreference() {
        // Mientras se relee no se dibujan tarjetas con el motor viejo.
        prefLoaded.value = false
        viewModelScope.launch {
            pref.value = withContext(Dispatchers.IO) { settings.enginePreference }
            prefLoaded.value = true
        }
    }

    fun cancel(modelId: String) = hub.cancel(modelId)
}
