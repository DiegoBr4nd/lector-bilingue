package io.github.diegobr4nd.lectorbilingue.ui.home

import io.github.diegobr4nd.lectorbilingue.ui.library.HomeState
import io.github.diegobr4nd.lectorbilingue.ui.library.HomeRules
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Estado del Inicio: las tarjetas salen de los idiomas del gestor y del motor elegido, que viene de los ajustes
 * como flujo (un cambio hecho en Idiomas se ve al volver, sin releer). No carga motores ni guarda texto de libros.
 */
class HomeViewModel(private val hub: ModelHubApi, private val settings: AppSettings) : ViewModel() {
    /** false hasta tener el catálogo y el motor guardado: antes no se dibujan tarjetas (serían viejas). */
    val loaded: StateFlow<Boolean> =
        combine(hub.loaded, settings.enginePreferenceLoaded) { a, b -> a && b }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val state: StateFlow<HomeState> =
        combine(hub.pairs, settings.enginePreferenceFlow) { pairs, p -> HomeRules.cards(pairs, hub.totalRamBytes(), p) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState(emptyList(), showNoLanguages = false))

    init {
        // La primera lectura toca el disco: va fuera del hilo principal (no hace nada si ya se leyó).
        viewModelScope.launch { settings.loadEnginePreference() }
    }

    fun cancel(modelId: String) = hub.cancel(modelId)
}
