package io.github.diegobr4nd.lectorbilingue.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.components.DownloadProgress
import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.core.ui.components.LectorIcons
import io.github.diegobr4nd.lectorbilingue.core.ui.components.PrivacyBadge
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ButtonShape
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.core.ui.R as UiR
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.ModelActions
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import io.github.diegobr4nd.lectorbilingue.ui.LoadingLine
import io.github.diegobr4nd.lectorbilingue.ui.ScreenFrame
import io.github.diegobr4nd.lectorbilingue.ui.pairName

/**
 * Inicio provisional. [onDeveloper] es null en la versión de la tienda (no hay menú Desarrollador).
 * No carga ningún motor de traducción.
 */
@Composable
fun HomeScreen(
    hub: ModelHubApi,
    settings: AppSettings,
    onLanguages: () -> Unit,
    onDeveloper: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val viewModel: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(hub, settings) } })
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // El modelo de vista sobrevive a Idiomas: se vuelve a leer el motor elegido al entrar.
    LaunchedEffect(Unit) { viewModel.reloadPreference() }
    HomeContent(
        loaded = loaded,
        state = state,
        onLanguages = onLanguages,
        onDeveloper = onDeveloper,
        onCancel = viewModel::cancel,
        modifier = modifier,
    )
}

/** El Inicio sin estado propio: se dibuja igual con datos de verdad o de muestra. */
@Composable
fun HomeContent(
    loaded: Boolean,
    state: HomeState,
    onLanguages: () -> Unit,
    onDeveloper: (() -> Unit)?,
    onCancel: (modelId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenFrame(modifier) {
        HomeHeader(onLanguages, onDeveloper)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            if (!loaded) {
                LoadingLine(stringResource(R.string.home_loading))
            } else if (state.showNoLanguages) {
                NoLanguagesCard(onLanguages)
            } else {
                for (card in state.pairCards) PairCardView(card, onCancel)
            }
            OutlinedButton(
                onClick = onLanguages,
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(painterResource(LectorIcons.Translate), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.home_languages))
            }
            LibraryCard()
            PrivacyBadge(Modifier.align(Alignment.CenterHorizontally).padding(vertical = Spacing.l))
        }
    }
}

@Composable
private fun HomeHeader(onLanguages: () -> Unit, onDeveloper: (() -> Unit)?) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = ReadingFontFamily),
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(LectorIcons.MoreVert), contentDescription = stringResource(R.string.home_menu))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_languages)) },
                    onClick = {
                        menuOpen = false
                        onLanguages()
                    },
                )
                if (onDeveloper != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_menu_developer)) },
                        onClick = {
                            menuOpen = false
                            onDeveloper()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CardSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun PairCardView(card: PairCard, onCancel: (String) -> Unit) {
    val name = pairName(card.pair)
    CardSurface {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            card.engine?.let { engine ->
                val engineName = stringResource(if (engine == EngineKind.QUALITY) UiR.string.engine_quality else UiR.string.engine_fast)
                Row(
                    Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    // Marca además del texto: el estado no depende del color.
                    Icon(
                        painterResource(LectorIcons.CheckCircle),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.home_ready, engineName), style = MaterialTheme.typography.bodyMedium)
                }
            }
            card.download?.let { download ->
                DownloadProgress(ModelActions.fraction(download.bytes, download.total))
                val modelId = card.modelId
                if (modelId != null) {
                    val cancelLabel = stringResource(R.string.home_cancel_download, name)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { onCancel(modelId) },
                            shape = ButtonShape,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = cancelLabel },
                        ) { Text(stringResource(UiR.string.action_cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoLanguagesCard(onLanguages: () -> Unit) {
    CardSurface {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(R.string.home_no_languages_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.home_no_languages_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onLanguages,
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.home_download_languages)) }
        }
    }
}

@Composable
private fun LibraryCard() {
    CardSurface {
        Row(
            Modifier.padding(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Icon(
                painterResource(LectorIcons.LibraryBooks),
                contentDescription = null, // Decorativo: el título ya dice de qué trata.
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(stringResource(R.string.home_library_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.home_library_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
