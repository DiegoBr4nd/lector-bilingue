package io.github.diegobr4nd.lectorbilingue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing

/** Ancho máximo del contenido: en tabletas las pantallas no se estiran de lado a lado. */
val ScreenMaxWidth = 560.dp

/** Fondo, márgenes seguros (barras del sistema y teclado) y ancho máximo comunes a Inicio e Idiomas. */
@Composable
fun ScreenFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ScreenMaxWidth).fillMaxSize().padding(horizontal = Spacing.xl),
                content = content,
            )
        }
    }
}

/** Barra de carga con texto; TalkBack lo anuncia con calma. */
@Composable
fun LoadingLine(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
