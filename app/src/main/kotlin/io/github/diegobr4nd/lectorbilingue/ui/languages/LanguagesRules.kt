package io.github.diegobr4nd.lectorbilingue.ui.languages

import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.core.ui.components.ModelRowState
import io.github.diegobr4nd.lectorbilingue.data.EnginePreference
import io.github.diegobr4nd.lectorbilingue.data.HubRules
import io.github.diegobr4nd.lectorbilingue.data.ModelActions
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineSelector
import io.github.diegobr4nd.lectorbilingue.models.DownloadState
import io.github.diegobr4nd.lectorbilingue.ui.kind
import java.util.Locale

/** Una fila de modelo lista para dibujar con `ModelRow`. */
data class UiRow(
    val modelId: String,
    val kind: EngineKind,
    val sizeMb: Long,
    val state: ModelRowState,
    val engine: EngineId,
)

/** Tarjeta de un par. [message] es el aviso fijo de una descarga que falló (null si no hay). */
data class LanguageCard(val pair: String, val rows: List<UiRow>, val message: ModelMessage? = null)

/** Qué elige Automático en este teléfono: [kind] y la RAM ya redondeada para mostrar ([ramGb], sin "GB"). */
data class AutoLine(val kind: EngineKind, val ramGb: String)

enum class RowAction { DOWNLOAD, DELETE }

/** Lo que dice el diálogo de confirmación; el par lo agrega la pantalla (la fila no lo sabe). */
data class Confirm(val action: RowAction, val kind: EngineKind, val sizeMb: Long)

/** Reglas puras (sin Android) de Idiomas: se prueban en la JVM. */
object LanguagesRules {
    fun rows(pairs: List<PairStatus>, ram: Long, pref: EnginePreference): List<LanguageCard> =
        pairs.map { status ->
            val inUse = HubRules.inUse(status, ram, pref)
            val rows = status.rows.map { row ->
                val state = when {
                    ModelActions.isActive(row.download) -> ModelRowState.Downloading(
                        row.download?.let { ModelActions.fraction(it.bytes, it.total) },
                    )
                    row === inUse -> ModelRowState.InUse
                    row.installed -> ModelRowState.Installed
                    else -> ModelRowState.NotInstalled
                }
                UiRow(row.modelId, row.engine.kind(), ModelActions.megabytes(row.sizeBytes), state, row.engine)
            }
            val failed = status.rows.firstOrNull { it.download?.status == DownloadState.Status.FAILED }
            LanguageCard(status.pair, rows, failed?.let { ModelActions.finalMessage(it.download) })
        }

    /** La línea bajo el selector de motor: qué motor usa Automático con esta RAM. */
    fun autoLine(ram: Long, locale: Locale = Locale.getDefault()): AutoLine {
        val engine = if (ram >= EngineSelector.OPUS_MIN_RAM_BYTES) EngineId.OPUS else EngineId.FIREFOX
        return AutoLine(engine.kind(), HubRules.ramText(ram, locale))
    }

    /**
     * ¿Al abrir Idiomas hay que leer el motor y buscar idiomas? Sí si este modelo de vista es nuevo (p. ej. tras
     * cerrar Android la app) o si es una visita nueva; no al girar la pantalla (no debe borrar avisos).
     */
    fun shouldStartVisit(viewModelStarted: Boolean, newVisit: Boolean): Boolean = !viewModelStarted || newVisit

    /** Borrar siempre pregunta; descargar solo si ese modelo ya está instalado (se reemplazaría). */
    fun confirmFor(action: RowAction, row: UiRow): Confirm? = when (action) {
        RowAction.DELETE -> Confirm(action, row.kind, row.sizeMb)
        RowAction.DOWNLOAD ->
            if (row.state == ModelRowState.Installed || row.state == ModelRowState.InUse) {
                Confirm(action, row.kind, row.sizeMb)
            } else {
                null
            }
    }
}
