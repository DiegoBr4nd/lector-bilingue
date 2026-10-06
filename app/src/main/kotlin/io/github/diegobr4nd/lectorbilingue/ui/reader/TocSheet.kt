package io.github.diegobr4nd.lectorbilingue.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.components.LectorIcons
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing

/** Índice en una hoja inferior. Tocar un capítulo salta a él y cierra la hoja. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(entries: List<TocEntry>, currentIndex: Int?, onSelect: (TocEntry) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        TocContent(entries, currentIndex, onSelect)
    }
}

/**
 * Contenido del Índice, sin la hoja (así se previsualiza). El capítulo actual va con ícono, negrita y
 * "Capítulo actual" para TalkBack: nunca solo con color.
 */
@Composable
fun TocContent(entries: List<TocEntry>, currentIndex: Int?, onSelect: (TocEntry) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.reader_toc),
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = ReadingFontFamily),
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s).semantics { heading() },
        )
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.reader_toc_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.l),
            )
            Spacer(Modifier.size(Spacing.xl))
            return@Column
        }
        // Abre mostrando el capítulo actual (con uno de contexto arriba).
        val state = rememberLazyListState(initialFirstVisibleItemIndex = ((currentIndex ?: 0) - 1).coerceAtLeast(0))
        val currentLabel = stringResource(R.string.reader_current_chapter)
        val untitled = stringResource(R.string.reader_toc_untitled)
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = Spacing.xl)) {
            itemsIndexed(entries) { index, entry ->
                val current = index == currentIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onSelect(entry) }
                        .semantics {
                            if (current) {
                                selected = true
                                stateDescription = currentLabel
                            }
                        }
                        .padding(start = Spacing.xl + 16.dp * entry.depth.coerceAtMost(MaxIndent), end = Spacing.xl)
                        .padding(vertical = Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    Text(
                        entry.title ?: untitled,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (current) FontWeight.Bold else null,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (current) {
                        Icon(
                            painterResource(LectorIcons.CheckCircle),
                            contentDescription = null, // stateDescription ya lo dice.
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Con letra grande, una sangría sin tope dejaría sin espacio a los títulos de niveles profundos. */
private const val MaxIndent = 4
