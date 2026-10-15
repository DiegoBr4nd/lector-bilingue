package io.github.diegobr4nd.lectorbilingue.ui.tips

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.components.LectorIcons
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.data.TipId

/**
 * Consejo del Lector (spec 3c-3 §4): bombilla + texto entero + "Entendido" (≥ 48 dp). Colores de la app (no de la
 * página), así se lee igual en los 4 temas. TalkBack: el texto es una región viva (se dice una vez al aparecer); el
 * botón va aparte. Con letra ≥ 150 % el botón baja debajo del texto. Sin estado: se previsualiza sola.
 */
@Composable
fun TipCard(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val largeText = LocalDensity.current.fontScale >= LargeTipFontScale
    Surface(
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        val message = @Composable { m: Modifier ->
            Row(
                m.clearAndSetSemantics {
                    contentDescription = text
                    liveRegion = LiveRegionMode.Polite
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                Icon(painterResource(LectorIcons.Lightbulb), contentDescription = null, modifier = Modifier.size(24.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val button = @Composable {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSecondaryContainer),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.tip_got_it), style = MaterialTheme.typography.labelLarge) }
        }
        val inner = Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.xs, bottom = Spacing.xs)
        if (largeText) {
            Column(inner) {
                message(Modifier.fillMaxWidth().padding(vertical = Spacing.s))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { button() }
            }
        } else {
            Row(inner, verticalAlignment = Alignment.CenterVertically) {
                message(Modifier.weight(1f).padding(vertical = Spacing.s))
                button()
            }
        }
    }
}

/** Desde esta escala de letra el botón de la tarjeta baja debajo del texto. */
private const val LargeTipFontScale = 1.5f

/** El texto de cada consejo. [directionLabel] es la etiqueta del botón de dirección ("ES → EN"); [targetLanguageName], "inglés". */
@Composable
fun tipText(tip: TipId, directionLabel: String, targetLanguageName: String): String = when (tip) {
    TipId.DIRECTION -> stringResource(R.string.tip_direction, targetLanguageName, directionLabel)
    TipId.TAP -> stringResource(R.string.tip_tap)
    TipId.HIDE_AND_MODES -> stringResource(R.string.tip_hide_modes)
    TipId.CHAPTER_EDGE -> stringResource(R.string.tip_chapter_edge, stringResource(R.string.reader_toc))
}
