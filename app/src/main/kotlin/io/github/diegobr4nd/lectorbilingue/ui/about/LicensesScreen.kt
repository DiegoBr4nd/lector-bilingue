package io.github.diegobr4nd.lectorbilingue.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.data.LicenseEntry
import io.github.diegobr4nd.lectorbilingue.data.LicenseGroup
import io.github.diegobr4nd.lectorbilingue.data.Licenses
import io.github.diegobr4nd.lectorbilingue.ui.BottomInsetSpacer
import io.github.diegobr4nd.lectorbilingue.ui.ScreenFrame
import io.github.diegobr4nd.lectorbilingue.ui.tips.openExternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface LicensesState {
    data object Loading : LicensesState
    data class Ready(val entries: List<LicenseEntry>) : LicensesState
    data object Broken : LicensesState
}

/** «Licencias» (spec 6a §4): lee el catálogo de assets fuera del hilo principal. */
@Composable
fun LicensesScreen(onBack: () -> Unit, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    val state by produceState<LicensesState>(LicensesState.Loading) {
        value = withContext(Dispatchers.IO) { Licenses.load(assets) }?.let { LicensesState.Ready(it) } ?: LicensesState.Broken
    }
    LicensesContent(state, onBack, onOpen, modifier)
}

/** Sin estado propio: se dibuja igual con el catálogo real o de muestra (previews y pruebas). */
@Composable
fun LicensesContent(state: LicensesState, onBack: () -> Unit, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val appName = stringResource(R.string.app_name)
    val openLabel = stringResource(R.string.licenses_open)
    ScreenFrame(modifier) {
        TitleRow(stringResource(R.string.licenses_title), onBack)
        when (state) {
            LicensesState.Loading -> Unit
            LicensesState.Broken -> Text(stringResource(R.string.licenses_error), style = MaterialTheme.typography.bodyLarge)
            is LicensesState.Ready -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                for (group in LicenseGroup.entries) {
                    val rows = state.entries.filter { it.group == group }
                    if (rows.isEmpty()) continue
                    groupTitle(group)?.let { title ->
                        item(key = "h-$group") {
                            Text(
                                stringResource(title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = Spacing.l, bottom = Spacing.xs).semantics { heading() },
                            )
                        }
                    }
                    items(rows, key = { it.id }) { e ->
                        ListItem(
                            headlineContent = { Text(if (e.group == LicenseGroup.APP) appName else e.name) },
                            supportingContent = { Text(e.license) },
                            modifier = Modifier.heightIn(min = 48.dp).clickable(onClickLabel = openLabel) { onOpen(e.id) },
                        )
                    }
                }
                item(key = "end") { BottomInsetSpacer() }
            }
        }
    }
}

private fun groupTitle(group: LicenseGroup): Int? = when (group) {
    LicenseGroup.APP -> null
    LicenseGroup.LIBRARIES -> R.string.licenses_group_libraries
    LicenseGroup.NATIVE -> R.string.licenses_group_native
    LicenseGroup.FONTS_ICONS -> R.string.licenses_group_fonts
}

private sealed interface LicenseTextState {
    data object Loading : LicenseTextState
    data class Ready(val entry: LicenseEntry, val text: String) : LicenseTextState
    data object Broken : LicenseTextState
}

/** El texto completo de una licencia (sin internet). Para la app, arriba el enlace al código fuente (GPL). */
@Composable
fun LicenseTextScreen(id: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val assets = context.assets
    val state by produceState<LicenseTextState>(LicenseTextState.Loading, id) {
        value = withContext(Dispatchers.IO) {
            val entry = Licenses.load(assets)?.firstOrNull { it.id == id }
            val text = entry?.let { Licenses.readTexts(assets, it) }
            if (entry != null && text != null) LicenseTextState.Ready(entry, text) else LicenseTextState.Broken
        }
    }
    val appName = stringResource(R.string.app_name)
    ScreenFrame(modifier) {
        when (val s = state) {
            LicenseTextState.Loading -> TitleRow(stringResource(R.string.licenses_title), onBack)
            LicenseTextState.Broken -> {
                TitleRow(stringResource(R.string.licenses_title), onBack)
                Text(stringResource(R.string.licenses_error), style = MaterialTheme.typography.bodyLarge)
            }
            is LicenseTextState.Ready -> {
                TitleRow(if (s.entry.group == LicenseGroup.APP) appName else s.entry.name, onBack)
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    Text(s.entry.license, style = MaterialTheme.typography.titleSmall)
                    val url = s.entry.url
                    if (s.entry.group == LicenseGroup.APP && url != null) {
                        LinkButton(stringResource(R.string.licenses_source_code)) { openExternal(context, url) }
                        Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SelectionContainer { Text(s.text, style = MaterialTheme.typography.bodySmall) }
                    BottomInsetSpacer()
                }
            }
        }
    }
}
