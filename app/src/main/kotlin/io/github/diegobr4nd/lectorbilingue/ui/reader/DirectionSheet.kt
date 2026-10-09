package io.github.diegobr4nd.lectorbilingue.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.components.LectorIcons
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.data.TranslationRules
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.ui.pairName

/** Las dos direcciones de la 3b, en el orden de la hoja. */
val ReaderDirections = listOf(LanguagePair("en", "es"), LanguagePair("es", "en"))

/** "EN → ES": abre la hoja "Traducir de". TalkBack dice "Idioma de traducción: inglés a español" y "cambiar el idioma". */
@Composable
internal fun DirectionButton(direction: LanguagePair, onClick: () -> Unit) {
    val description = stringResource(R.string.reader_direction_desc, languageName(direction.source), languageName(direction.target))
    val action = stringResource(R.string.reader_direction_action)
    TextButton(
        onClick = onClick,
        // Va por fuera del clic del botón: reemplaza su acción por una con nombre (la misma función).
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics {
            contentDescription = description
            onClick(label = action) { onClick(); true }
        },
    ) {
        Text(
            stringResource(R.string.reader_direction, direction.source.uppercase(), direction.target.uppercase()),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

/** "Traducir de" en una hoja inferior. Elegir una dirección la guarda para el libro y cierra la hoja. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectionSheet(current: LanguagePair, onSelect: (LanguagePair) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 560.dp, // En tableta, centrada y sin estirarse.
    ) {
        DirectionContent(current, onSelect)
    }
}

/**
 * Contenido de la hoja, sin la hoja (así se previsualiza). La dirección actual va con ícono, negrita y
 * "Dirección actual" para TalkBack: nunca solo con color. Se desplaza si con letra grande no cabe.
 */
@Composable
fun DirectionContent(current: LanguagePair, onSelect: (LanguagePair) -> Unit, modifier: Modifier = Modifier) {
    val currentLabel = stringResource(R.string.reader_direction_current)
    val selectLabel = stringResource(R.string.reader_direction_select)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.reader_direction_title),
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = ReadingFontFamily),
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s).semantics { heading() },
        )
        for (pair in ReaderDirections) {
            val selected = pair == current
            // TalkBack oye "Inglés a español", no "flecha".
            val spoken = stringResource(R.string.reader_direction_option, languageName(pair.source), languageName(pair.target))
                .replaceFirstChar { it.uppercase() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = selectLabel) { onSelect(pair) }
                    .semantics {
                        contentDescription = spoken
                        if (selected) {
                            this.selected = true
                            stateDescription = currentLabel
                        }
                    }
                    .padding(horizontal = Spacing.xl, vertical = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                // El ícono va delante, como en el boceto; sin dirección actual queda el hueco para alinear.
                if (selected) {
                    Icon(
                        painterResource(LectorIcons.CheckCircle),
                        contentDescription = null, // stateDescription ya lo dice.
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Spacer(Modifier.size(24.dp))
                }
                Text(
                    pairName(TranslationRules.wire(pair)),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.Bold else null,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.size(Spacing.xl))
    }
}

/** Nombre del idioma dentro de una frase ("inglés"); un código desconocido se muestra tal cual. */
@Composable
fun languageName(code: String): String = when (code) {
    "en" -> stringResource(R.string.language_en)
    "es" -> stringResource(R.string.language_es)
    else -> code
}
