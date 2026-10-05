package io.github.diegobr4nd.lectorbilingue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
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

/**
 * Fondo, márgenes seguros arriba y a los lados (barras del sistema) y ancho máximo comunes a Inicio e Idiomas.
 * El final lo resuelve [BottomInsetSpacer].
 */
@Composable
fun ScreenFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Arriba y a los lados se respetan las barras; abajo no: el contenido se desliza bajo la barra de gestos
        // y [BottomInsetSpacer] deja el final libre.
        Box(
            Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            ),
            contentAlignment = Alignment.TopCenter,
        ) {
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

/** Final de una lista que se desliza: deja libre la barra de gestos (o el teclado) y un poco más de aire. */
@Composable
fun BottomInsetSpacer() {
    Spacer(Modifier.height(Spacing.xl))
    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
}
