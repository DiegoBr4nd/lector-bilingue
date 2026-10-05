package io.github.diegobr4nd.lectorbilingue.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.diegobr4nd.lectorbilingue.core.ui.R
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing

/**
 * Progreso de descarga. [fraction] de 0 a 1; `null` si aún no se sabe el total (barra sin fin).
 * El porcentaje siempre va escrito, nunca solo en la barra. TalkBack lo anuncia con calma (Polite).
 */
@Composable
fun DownloadProgress(fraction: Float?, modifier: Modifier = Modifier) {
    val percent = fraction?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
    val label = if (percent != null) {
        stringResource(R.string.download_progress_percent, percent)
    } else {
        stringResource(R.string.download_progress_unknown)
    }
    val description = if (percent != null) {
        stringResource(R.string.download_progress_description_percent, percent)
    } else {
        stringResource(R.string.download_progress_description_unknown)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = description
            },
    ) {
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}
