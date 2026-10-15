package io.github.diegobr4nd.lectorbilingue.ui.tips

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
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
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.ui.reader.directionDescription
import io.github.diegobr4nd.lectorbilingue.ui.reader.directionLabel
import io.github.diegobr4nd.lectorbilingue.ui.reader.languageName

/**
 * Consejo del Lector (spec 3c-3 §4): bombilla + texto entero + "Entendido" (≥ 48 dp). Colores de la app (no de la
 * página), así se lee igual en los 4 temas. Con letra ≥ 150 % el botón baja debajo del texto. Sin estado: se
 * previsualiza sola.
 *
 * TalkBack oye [spoken] (por defecto, [text]): nombra los botones como TalkBack los dice ("Ajustes de lectura", no
 * "Aa"). Con [announce] el texto es una región viva y se dice al aparecer; sin él, la tarjeta sale callada (un
 * consejo que vuelve tras cerrar una hoja ya se dijo). "Entendido" va aparte.
 */
@Composable
fun TipCard(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    spoken: String = text,
    announce: Boolean = true,
    /** Botón de acción opcional antes del de cerrar (la tarjeta de la Biblioteca: "Ver"). */
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    /** Texto del botón de cerrar; por defecto "Entendido". */
    dismissLabel: String? = null,
) {
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
                    contentDescription = spoken
                    if (announce) liveRegion = LiveRegionMode.Polite
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                Icon(painterResource(LectorIcons.Lightbulb), contentDescription = null, modifier = Modifier.size(24.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val buttonColors = ButtonDefaults.textButtonColors(contentColor = colors.onSecondaryContainer)
        val button = @Composable {
            // Con letra grande y dos botones, el segundo baja de línea en vez de salirse.
            FlowRow(horizontalArrangement = Arrangement.End) {
                if (actionLabel != null) {
                    TextButton(onClick = onAction, colors = buttonColors, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
                TextButton(onClick = onDismiss, colors = buttonColors, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(dismissLabel ?: stringResource(R.string.tip_got_it), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        val inner = Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.xs, bottom = Spacing.xs)
        // Con dos botones no caben al lado del texto: van debajo, como con letra grande.
        if (largeText || actionLabel != null) {
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

/** Lo que se ve en la tarjeta ([shown]) y lo que dice TalkBack ([spoken]). */
data class TipText(val shown: String, val spoken: String = shown)

/**
 * El texto de cada consejo. Los nombres de botones salen de los mismos strings que esos botones: a la vista, su
 * etiqueta ("ES → EN", "Aa"); para TalkBack, la descripción con que TalkBack los dice (I1 de la revisión final).
 */
@Composable
fun tipText(tip: TipId, direction: LanguagePair): TipText = when (tip) {
    TipId.DIRECTION -> {
        val target = languageName(direction.target)
        TipText(
            shown = stringResource(R.string.tip_direction, target, directionLabel(direction)),
            spoken = stringResource(R.string.tip_direction_spoken, target, directionDescription(direction)),
        )
    }
    TipId.TAP -> TipText(stringResource(R.string.tip_tap))
    TipId.HIDE_AND_MODES -> TipText(
        shown = stringResource(R.string.tip_hide_modes, stringResource(R.string.reading_aa)),
        spoken = stringResource(R.string.tip_hide_modes_spoken, stringResource(R.string.reading_title)),
    )
    TipId.CHAPTER_EDGE -> TipText(stringResource(R.string.tip_chapter_edge, stringResource(R.string.reader_toc)))
    TipId.LIBRARY_HOWTO -> TipText(stringResource(R.string.library_howto_card))
}
