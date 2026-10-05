package io.github.diegobr4nd.lectorbilingue.ui.languages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.components.ConfirmDialog
import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.core.ui.components.LectorIcons
import io.github.diegobr4nd.lectorbilingue.core.ui.components.ModelRow
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ButtonShape
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.core.ui.R as UiR
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.EnginePreference
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.ui.BottomInsetSpacer
import io.github.diegobr4nd.lectorbilingue.ui.LoadingLine
import io.github.diegobr4nd.lectorbilingue.ui.ScreenFrame
import io.github.diegobr4nd.lectorbilingue.ui.pairDirection
import io.github.diegobr4nd.lectorbilingue.ui.pairName
import io.github.diegobr4nd.lectorbilingue.ui.textRes

/**
 * Idiomas: descargar, borrar e importar modelos, y elegir el motor. No carga ningún motor de traducción.
 * [onBack] vuelve a la pantalla anterior.
 */
@Composable
fun LanguagesScreen(
    hub: ModelHubApi,
    settings: AppSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LanguagesViewModel =
        viewModel(factory = viewModelFactory { initializer { LanguagesViewModel(hub, settings) } })
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Una vez por visita (no al girar la pantalla): el aviso de borrar o importar no debe desaparecer al girar.
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val newVisit = !entered
        entered = true
        viewModel.onEnter(newVisit)
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }

    // En Android 13 o superior se pide el permiso de notificaciones antes de descargar; si se niega, se descarga igual.
    var pendingDownload by rememberSaveable { mutableStateOf<String?>(null) }
    val currentCards by rememberUpdatedState(cards)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingDownload?.split('|', limit = 2)?.takeIf { it.size == 2 }?.let { (pair, modelId) ->
            val row = currentCards.firstOrNull { it.pair == pair }?.rows?.firstOrNull { it.modelId == modelId }
            if (row != null) viewModel.request(RowAction.DOWNLOAD, pair, row) else viewModel.downloadById(modelId)
        }
        pendingDownload = null
    }

    LanguagesContent(
        loaded = loaded && ui.preferenceLoaded,
        ui = ui,
        cards = cards,
        autoLine = viewModel.autoLine(),
        onBack = onBack,
        onSelectEngine = viewModel::selectEngine,
        onDownload = { pair, row ->
            val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsAsk) {
                pendingDownload = "$pair|${row.modelId}"
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.request(RowAction.DOWNLOAD, pair, row)
            }
        },
        onDelete = { pair, row -> viewModel.request(RowAction.DELETE, pair, row) },
        onCancel = viewModel::cancel,
        onImport = { importPicker.launch(arrayOf("application/zip")) },
        onRetry = viewModel::refresh,
        onConfirm = viewModel::confirm,
        onDismissConfirm = viewModel::dismissConfirm,
        modifier = modifier,
    )
}

/** Idiomas sin estado propio: se dibuja igual con datos de verdad o de muestra. */
@Composable
fun LanguagesContent(
    loaded: Boolean,
    ui: LanguagesUiState,
    cards: List<LanguageCard>,
    autoLine: AutoLine,
    onBack: () -> Unit,
    onSelectEngine: (EnginePreference) -> Unit,
    onDownload: (pair: String, row: UiRow) -> Unit,
    onDelete: (pair: String, row: UiRow) -> Unit,
    onCancel: (modelId: String) -> Unit,
    onImport: () -> Unit,
    onRetry: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenFrame(modifier) {
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(LectorIcons.ArrowBack), contentDescription = stringResource(R.string.languages_back))
            }
            Spacer(Modifier.width(Spacing.s))
            Text(
                stringResource(R.string.languages_title),
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = ReadingFontFamily),
                modifier = Modifier.semantics { heading() },
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            if (ui.message != null) MessageText(ui.message)
            when {
                !loaded -> LoadingLine(stringResource(R.string.languages_searching))
                else -> {
                    if (ui.searching) LoadingLine(stringResource(R.string.languages_searching))
                    if (cards.isEmpty() && !ui.searching) {
                        MessageText(ui.catalogMessage ?: ModelMessage.NO_CATALOG)
                        TextButton(
                            onClick = onRetry,
                            enabled = !ui.busy,
                            shape = ButtonShape,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.languages_retry)) }
                    }
                    for (card in cards) {
                        PairCardView(card, enabled = !ui.busy, onDownload, onDelete, onCancel)
                    }
                    EngineSelector(ui.preference, autoLine, onSelectEngine)
                }
            }
            if (ui.busy) LoadingLine(stringResource(R.string.languages_working))
            // Importar se ve siempre, aunque no haya catálogo o esté buscando.
            TextButton(
                onClick = onImport,
                enabled = !ui.busy,
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(painterResource(LectorIcons.FolderOpen), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.languages_import))
            }
            BottomInsetSpacer()
        }
    }
    ui.pending?.let { PendingDialog(it, onConfirm, onDismissConfirm) }
}

/** Aviso fijo en lenguaje sencillo; los errores van en el color de error y se anuncian solos. */
@Composable
private fun MessageText(message: ModelMessage) {
    Text(
        stringResource(message.textRes()),
        style = MaterialTheme.typography.bodyMedium,
        color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun PairCardView(
    card: LanguageCard,
    enabled: Boolean,
    onDownload: (String, UiRow) -> Unit,
    onDelete: (String, UiRow) -> Unit,
    onCancel: (String) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
            Text(
                pairName(card.pair),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            card.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ModelRow(
                    kind = row.kind,
                    sizeMb = row.sizeMb,
                    state = row.state,
                    onDownload = { onDownload(card.pair, row) },
                    onCancel = { onCancel(row.modelId) },
                    onDelete = { onDelete(card.pair, row) },
                    enabled = enabled,
                )
            }
            card.message?.let { MessageText(it) }
        }
    }
}

/**
 * Automático / Calidad / Rápido. Elección única: cada opción se anuncia como botón de opción (radio),
 * y la elegida lleva una marca además del color.
 */
@Composable
private fun EngineSelector(selected: EnginePreference, autoLine: AutoLine, onSelect: (EnginePreference) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Icon(
                painterResource(LectorIcons.Tune),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.languages_engine_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        val options = EnginePreference.entries.map { option ->
            option to stringResource(
                when (option) {
                    EnginePreference.AUTO -> R.string.languages_engine_auto
                    EnginePreference.QUALITY -> UiR.string.engine_quality
                    EnginePreference.FAST -> UiR.string.engine_fast
                },
            )
        }
        val measurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelLarge
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Se mide el texto real (con la letra del usuario): si las tres opciones iguales caben en una fila,
            // se reparten el ancho; si no, se apilan, cada una a todo el ancho.
            val gap = Spacing.s
            val optionWidth = with(LocalDensity.current) {
                // Peor caso: la opción más ancha en negrita (la elegida va en negrita).
                val boldStyle = labelStyle.copy(fontWeight = FontWeight.Bold)
                val widest = options.maxOf { measurer.measure(it.second, boldStyle, maxLines = 1).size.width }
                widest.toDp() + Spacing.m * 2
            }
            val sideBySide = optionWidth * options.size + gap * (options.size - 1) <= maxWidth
            if (sideBySide) {
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    options.forEach { (option, label) ->
                        EngineOption(label, option == selected, { onSelect(option) }, Modifier.weight(1f))
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    options.forEach { (option, label) ->
                        EngineOption(label, option == selected, { onSelect(option) }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (selected == EnginePreference.AUTO) {
            val engineName = stringResource(if (autoLine.kind == EngineKind.QUALITY) UiR.string.engine_quality else UiR.string.engine_fast)
            Text(
                stringResource(R.string.languages_engine_auto_line, engineName, autoLine.ramGb),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EngineOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        shape = ButtonShape,
        color = if (selected) scheme.primaryContainer else scheme.surface,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outline),
        // Elección única: se anuncia como botón de opción; el ripple queda recortado a la forma del botón.
        modifier = modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton },
    ) {
        // Elegida: fondo relleno, borde más grueso y letra en negrita (no depende solo del color).
        // TalkBack dice "seleccionado" por el rol de botón de opción y el estado de la superficie.
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Spacing.m),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else null,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PendingDialog(pending: PendingAction, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val engineName = stringResource(if (pending.confirm.kind == EngineKind.QUALITY) UiR.string.engine_quality else UiR.string.engine_fast)
    // Espacio sin corte antes de la flecha: nunca empieza una línea.
    val direction = pairDirection(pending.pair).replace(" →", " →")
    when (pending.confirm.action) {
        RowAction.DELETE -> ConfirmDialog(
            title = stringResource(R.string.languages_delete_title, engineName),
            body = stringResource(R.string.languages_delete_body, pending.confirm.sizeMb.toInt(), direction),
            confirmLabel = stringResource(R.string.languages_delete_confirm),
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
        RowAction.DOWNLOAD -> ConfirmDialog(
            title = stringResource(R.string.languages_redownload_title, engineName),
            body = stringResource(R.string.languages_redownload_body, pending.confirm.sizeMb.toInt(), direction),
            confirmLabel = stringResource(R.string.languages_redownload_confirm),
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}
