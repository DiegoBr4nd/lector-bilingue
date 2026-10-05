package io.github.diegobr4nd.lectorbilingue.ui.home

import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.data.EnginePreference
import io.github.diegobr4nd.lectorbilingue.data.HubRules
import io.github.diegobr4nd.lectorbilingue.data.ModelActions
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.models.DownloadState
import io.github.diegobr4nd.lectorbilingue.ui.kind

/**
 * Una tarjeta del Inicio. [engine] es el motor que se usaría ahora (null si aún no hay ninguno instalado);
 * [download] es la descarga en curso de este par (null si no hay) y [modelId] el modelo que se descarga.
 */
data class PairCard(
    val pair: String,
    val engine: EngineKind?,
    val download: DownloadState?,
    val modelId: String? = null,
)

data class HomeState(val pairCards: List<PairCard>, val showNoLanguages: Boolean)

/** Reglas puras (sin Android) del Inicio: se prueban en la JVM. */
object HomeRules {
    /** Una tarjeta por par instalado o descargándose; sin ninguna, el Inicio invita a descargar idiomas. */
    fun cards(pairs: List<PairStatus>, ram: Long, pref: EnginePreference): HomeState {
        val cards = pairs.mapNotNull { status ->
            val downloading = status.rows.firstOrNull { ModelActions.isActive(it.download) }
            val installed = status.rows.any { it.installed }
            if (!installed && downloading == null) return@mapNotNull null
            // Si el motor elegido no está instalado, se muestra el que sí lo está: la tarjeta dice la verdad.
            val using = HubRules.inUse(status, ram, pref) ?: status.rows.firstOrNull { it.installed }
            PairCard(
                pair = status.pair,
                engine = using?.engine?.kind(),
                download = downloading?.download,
                modelId = downloading?.modelId,
            )
        }
        return HomeState(cards, showNoLanguages = cards.isEmpty())
    }
}
