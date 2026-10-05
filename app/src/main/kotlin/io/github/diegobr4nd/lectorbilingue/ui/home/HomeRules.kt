package io.github.diegobr4nd.lectorbilingue.ui.home

import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.data.EnginePreference
import io.github.diegobr4nd.lectorbilingue.data.HubRules
import io.github.diegobr4nd.lectorbilingue.data.ModelActions
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.models.DownloadState
import io.github.diegobr4nd.lectorbilingue.ui.kind

/** Una descarga en curso de un par: [kind] es el motor que se baja. */
data class PairDownload(val modelId: String, val kind: EngineKind, val state: DownloadState)

/**
 * Una tarjeta del Inicio. [engine] es el motor que se usaría ahora; [missing] es el motor que la persona
 * eligió en Idiomas pero no está instalado (la app nunca cambia de motor sola); [downloads] son todas las
 * descargas en curso de este par.
 */
data class PairCard(
    val pair: String,
    val engine: EngineKind?,
    val downloads: List<PairDownload> = emptyList(),
    val missing: EngineKind? = null,
)

data class HomeState(val pairCards: List<PairCard>, val showNoLanguages: Boolean)

/** Reglas puras (sin Android) del Inicio: se prueban en la JVM. */
object HomeRules {
    /** Una tarjeta por par instalado o descargándose; sin ninguna, el Inicio invita a descargar idiomas. */
    fun cards(pairs: List<PairStatus>, ram: Long, pref: EnginePreference): HomeState {
        val cards = pairs.mapNotNull { status ->
            val downloads = status.rows.mapNotNull { row ->
                row.download?.takeIf { ModelActions.isActive(it) }?.let { PairDownload(row.modelId, row.engine.kind(), it) }
            }
            val installed = status.rows.any { it.installed }
            if (!installed && downloads.isEmpty()) return@mapNotNull null
            val using = HubRules.inUse(status, ram, pref)
            // Motor elegido y ausente: no se usa otro en silencio; la tarjeta avisa que falta.
            val forced = pref.toForced()
            val missing = if (using == null && installed && forced != null &&
                downloads.none { it.kind == forced.kind() }
            ) forced.kind() else null
            PairCard(status.pair, using?.engine?.kind(), downloads, missing)
        }
        return HomeState(cards, showNoLanguages = cards.isEmpty())
    }
}
