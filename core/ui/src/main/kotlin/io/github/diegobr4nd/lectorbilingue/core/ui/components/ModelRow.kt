package io.github.diegobr4nd.lectorbilingue.core.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.core.ui.R
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ButtonShape
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing

/** Motor de traducción tal como lo ve la persona: Calidad (OPUS) o Rápido (Firefox). */
enum class EngineKind { QUALITY, FAST }

/** Estado de un modelo en la lista. */
sealed interface ModelRowState {
    data object NotInstalled : ModelRowState
    data object Installed : ModelRowState
    data object InUse : ModelRowState

    /** [fraction] de 0 a 1, o `null` si aún no se sabe. */
    data class Downloading(val fraction: Float?) : ModelRowState
}

/**
 * Fila de un modelo: ícono y nombre, tamaño, estado en texto y un solo botón
 * (Descargar, Cancelar o Borrar). El estado nunca depende solo del color.
 * Nombre, tamaño y estado se leen juntos; cada botón dice a qué modelo se refiere,
 * con motor y [pairLabel] (el par de idiomas), porque dos direcciones pueden verse a la vez.
 * "Cancelar" queda activo aunque [enabled] sea falso: siempre se puede frenar una descarga.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelRow(
    kind: EngineKind,
    sizeMb: Long,
    pairLabel: String,
    state: ModelRowState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val nameRes = if (kind == EngineKind.QUALITY) R.string.engine_quality else R.string.engine_fast
    val engineName = stringResource(nameRes)
    val downloadLabel = stringResource(R.string.action_download_model, engineName, pairLabel)
    val cancelLabel = stringResource(R.string.action_cancel_download, engineName, pairLabel)
    val deleteLabel = stringResource(R.string.action_delete_model, engineName, pairLabel)
    val iconRes = if (kind == EngineKind.QUALITY) LectorIcons.WorkspacePremium else LectorIcons.Bolt
    val downloading = state as? ModelRowState.Downloading
    val button: @Composable () -> Unit = {
        when (state) {
            ModelRowState.NotInstalled -> Button(
                onClick = onDownload,
                enabled = enabled,
                shape = ButtonShape,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = downloadLabel },
            ) { Text(stringResource(R.string.action_download)) }
            is ModelRowState.Downloading -> TextButton(
                onClick = onCancel,
                shape = ButtonShape,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = cancelLabel },
            ) { Text(stringResource(R.string.action_cancel)) }
            ModelRowState.Installed, ModelRowState.InUse -> TextButton(
                onClick = onDelete,
                enabled = enabled,
                shape = ButtonShape,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = deleteLabel },
            ) { Text(stringResource(R.string.action_delete)) }
        }
    }
    // Nombre, tamaño y estado se leen juntos. Si cambia el estado (Instalado, En uso…), TalkBack lo anuncia con calma.
    val info: @Composable () -> Unit = {
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(engineName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.model_size_mb, sizeMb),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (downloading == null) StateLabel(state)
        }
    }
    Column(modifier = modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
        if (downloading != null) {
            info()
            DownloadProgress(fraction = downloading.fraction, modifier = Modifier.padding(top = Spacing.s))
            Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.End) { button() }
        } else {
            // Estado a la izquierda y botón al final de la misma fila; con letra muy grande el botón baja y nada se corta.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                itemVerticalAlignment = Alignment.Bottom,
            ) {
                info()
                button()
            }
        }
    }
}

@Composable
private fun StateLabel(state: ModelRowState) {
    @StringRes val textRes = when (state) {
        ModelRowState.NotInstalled -> R.string.model_state_not_installed
        ModelRowState.Installed -> R.string.model_state_installed
        ModelRowState.InUse -> R.string.model_state_in_use
        is ModelRowState.Downloading -> return
    }
    Row(
        modifier = Modifier.padding(top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        // Marca además del texto: el estado no depende del color.
        if (state != ModelRowState.NotInstalled) {
            Icon(
                painter = painterResource(LectorIcons.CheckCircle),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
