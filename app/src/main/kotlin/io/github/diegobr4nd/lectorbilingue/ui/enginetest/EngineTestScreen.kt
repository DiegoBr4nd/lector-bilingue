package io.github.diegobr4nd.lectorbilingue.ui.enginetest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.Reason
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchmarkResult
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme

/** Conecta el ViewModel con la pantalla sin estado. */
@Composable
fun EngineTestScreen(
    modifier: Modifier = Modifier,
    viewModel: EngineTestViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // En Android 13+ se pide el permiso de notificaciones antes de encolar; si se niega, se descarga igual.
    var pendingModelId by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingModelId?.let(viewModel::downloadModel)
        pendingModelId = null
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }
    EngineTestContent(
        state = state,
        onInputChange = viewModel::onInputChange,
        onTranslate = viewModel::translate,
        onBenchmark = viewModel::runBenchmark,
        onSelectEngine = viewModel::selectEngine,
        onSelectPair = viewModel::selectPair,
        onDeleteModel = viewModel::deleteModel,
        onDownload = { modelId ->
            val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsAsk) {
                pendingModelId = modelId
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.downloadModel(modelId)
            }
        },
        onCancelDownload = viewModel::cancelDownload,
        onImport = { importPicker.launch(arrayOf("application/zip")) },
        modifier = modifier,
    )
}

@Composable
fun EngineTestContent(
    state: EngineTestUiState,
    onInputChange: (String) -> Unit,
    onTranslate: () -> Unit,
    onBenchmark: () -> Unit,
    onDownload: (String) -> Unit = {},
    onSelectEngine: (EngineSwitch) -> Unit = {},
    onSelectPair: (PairChoice) -> Unit = {},
    onDeleteModel: (String) -> Unit = {},
    onImport: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.engine_test_title), style = MaterialTheme.typography.titleLarge)
            EngineControlsCard(state, onSelectEngine, onSelectPair)
            ModelStatusCard(state, onUseOffer = onSelectEngine, onDownload = onDownload, onDeleteModel = onDeleteModel)
            ModelManagerCard(state, onDownload, onDeleteModel, onImport, onCancelDownload)
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                label = {
                    Text(stringResource(if (state.pair == PairChoice.ES_EN) R.string.input_label_es else R.string.input_label))
                },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            val translateBlock = ScreenRules.translateBlock(state)
            Button(onClick = onTranslate, enabled = translateBlock == null) {
                Text(stringResource(R.string.translate_button))
            }
            BlockReason(translateBlock)
            if (state.busy && !state.measuring) BusyRow(R.string.translating)
            if (state.output.isNotEmpty()) {
                SelectionContainer {
                    Text(state.output, style = MaterialTheme.typography.bodyLarge)
                }
                state.lastMillis?.let { Text(stringResource(R.string.elapsed_ms, it)) }
            }
            val benchAvailable = ScreenRules.benchAvailable(state)
            val measureBlock = ScreenRules.measureBlock(state)
            Button(onClick = onBenchmark, enabled = measureBlock == null && benchAvailable) {
                Text(stringResource(R.string.benchmark_button))
            }
            BlockReason(measureBlock)
            if (state.busy && state.measuring) BusyRow(R.string.bench_measuring)
            if (!benchAvailable || state.benchNoTexts) {
                Text(
                    stringResource(R.string.bench_no_texts),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            state.benchmark?.let { result ->
                val engine = state.benchEngine
                val pair = state.benchPair
                if (engine != null && pair != null) {
                    Text(
                        stringResource(R.string.bench_measured_with, engine.label(), pair.label()),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                BenchmarkCard(result, state.benchSource)
            }
            state.errorMessage?.takeIf { state.modelStatus != ModelStatus.ERROR }?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** Rueda de progreso con su texto (traduciendo o midiendo). */
@Composable
private fun BusyRow(@StringRes text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val working = stringResource(R.string.working_description)
        CircularProgressIndicator(Modifier.semantics { contentDescription = working })
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EngineId.label(): String = stringResource(
    when (this) {
        EngineId.OPUS -> R.string.engine_opus
        EngineId.FIREFOX -> R.string.engine_firefox
    },
)

@Composable
private fun PairChoice.label(): String =
    stringResource(if (this == PairChoice.EN_ES) R.string.pair_en_es else R.string.pair_es_en)

@Composable
private fun EngineControlsCard(
    state: EngineTestUiState,
    onSelectEngine: (EngineSwitch) -> Unit,
    onSelectPair: (PairChoice) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val lock = ScreenRules.lockReason(state)
            val controlsEnabled = lock == null
            Text(stringResource(R.string.engine_section_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.engine_switch_label), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(
                options = EngineSwitch.entries,
                selected = state.engineSwitch,
                enabled = controlsEnabled,
                onSelect = onSelectEngine,
            ) { option ->
                stringResource(
                    when (option) {
                        EngineSwitch.AUTO -> R.string.engine_auto
                        EngineSwitch.OPUS -> R.string.engine_opus
                        EngineSwitch.FIREFOX -> R.string.engine_firefox
                    },
                )
            }
            Text(stringResource(R.string.pair_label), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(
                options = PairChoice.entries,
                selected = state.pair,
                enabled = controlsEnabled,
                onSelect = onSelectPair,
            ) { option -> option.label() }
            if (lock != null) {
                Text(
                    stringResource(lock.textRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@StringRes
private fun LockReason.textRes(): Int = when (this) {
    LockReason.DOWNLOADING -> R.string.lock_downloading
    LockReason.MODEL_BUSY -> R.string.lock_model_busy
    LockReason.LOADING -> R.string.lock_loading
    LockReason.TRANSLATING -> R.string.lock_translating
    LockReason.MEASURING -> R.string.lock_measuring
}

/**
 * Elección única con chips que saltan de línea (FlowRow). Antes eran botones segmentados, pero con la letra
 * al 200 % "Automático" no cabía en un tercio del ancho; los chips se acomodan solos.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                enabled = enabled,
                label = { Text(label(option), maxLines = 1) },
                // Elección única: se anuncia como botón de radio, no como casilla.
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { role = Role.RadioButton },
            )
        }
    }
}

@Composable
private fun EngineLine(state: EngineTestUiState) {
    val engine = state.engineInUse
    val reason = state.reason
    val text = if (engine == null || reason == null) {
        stringResource(R.string.engine_line_none)
    } else {
        when (reason) {
            Reason.FORCED -> stringResource(R.string.engine_line_forced, engine.label())
            Reason.RAM -> stringResource(R.string.engine_line_ram, engine.label(), state.ramText)
            Reason.ONLY_INSTALLED -> stringResource(R.string.engine_line_only, engine.label())
        }
    }
    Text(text)
}

@Composable
private fun ModelStatusCard(
    state: EngineTestUiState,
    onUseOffer: (EngineSwitch) -> Unit,
    onDownload: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Región "viva": TalkBack anuncia el cambio de estado del modelo sin que el usuario lo busque.
            val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            when (state.modelStatus) {
                ModelStatus.LOADING -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val working = stringResource(R.string.working_description)
                    CircularProgressIndicator(Modifier.semantics { contentDescription = working })
                    Text(stringResource(R.string.model_loading), modifier = live)
                }
                ModelStatus.READY -> Column(modifier = live, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.model_ready))
                    EngineLine(state)
                }
                ModelStatus.MISSING -> Column(modifier = live, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val missing = state.missingEngine
                    if (missing != null) {
                        Text(stringResource(R.string.model_missing_engine, missing.label(), state.pair.label()))
                        val missingRow = state.models.firstOrNull { it.engine == missing && !it.installed }
                        if (missingRow != null) {
                            Button(
                                onClick = { onDownload(missingRow.id) },
                                enabled = !state.modelBusy,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.download_row_button, missingRow.engine.label())) }
                        } else {
                            // El catálogo no tiene ese modelo: no hay botón de descarga al que apuntar.
                            Text(stringResource(R.string.model_missing_no_row, missing.label(), state.pair.label()))
                        }
                    }
                }
                ModelStatus.ERROR -> Column(modifier = live, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val failed = state.engineInUse
                    Text(
                        if (failed != null) {
                            stringResource(R.string.model_error, failed.label(), state.pair.label())
                        } else {
                            stringResource(R.string.model_error_generic)
                        },
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.loadOffer?.let { offer ->
                        Text(stringResource(R.string.load_offer_text, offer.failed.label(), offer.alternative.label()))
                        OutlinedButton(
                            onClick = {
                                onUseOffer(EngineSwitch.of(offer.alternative))
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.load_offer_button, offer.alternative.label())) }
                    }
                    // Borrar el modelo que falló permite descargarlo de nuevo.
                    val failedRow = state.models.firstOrNull { it.engine == failed && it.installed }
                    if (failedRow != null) {
                        OutlinedButton(
                            onClick = { onDeleteModel(failedRow.id) },
                            enabled = !state.modelBusy && !state.busy,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.delete_row_button, failedRow.engine.label())) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelManagerCard(
    state: EngineTestUiState,
    onDownload: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onImport: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.models_title, state.pair.label()), style = MaterialTheme.typography.titleMedium)
            if (state.models.isEmpty()) Text(stringResource(R.string.models_empty))
            state.models.forEach { row ->
                ModelRowItem(state, row, onDownload, onDeleteModel, onCancelDownload)
            }
            if (ScreenRules.showWifiHint(state)) {
                Text(
                    stringResource(R.string.download_wifi_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.modelBusy && !state.downloading && state.phase != ModelPhase.NONE) {
                val busyDescription = stringResource(R.string.model_busy_description)
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = busyDescription },
                )
                Text(
                    stringResource(
                        when (state.phase) {
                            ModelPhase.IMPORTING -> R.string.phase_importing
                            ModelPhase.DELETING -> R.string.phase_deleting
                            else -> R.string.phase_catalog
                        },
                    ),
                )
            }
            OutlinedButton(
                onClick = onImport,
                enabled = !state.modelBusy && !state.busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.import_button)) }
            state.modelMessage?.let {
                Text(
                    stringResource(it.textRes()),
                    color = if (it.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** Una fila: motor, tamaño y estado en texto (no solo color), con su botón Descargar, Cancelar o Borrar. */
@Composable
private fun ModelRowItem(
    state: EngineTestUiState,
    row: ModelRow,
    onDownload: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onCancelDownload: () -> Unit,
) {
    val isActive = state.downloading && state.activeModelId == row.id
    val status = stringResource(
        when {
            isActive -> R.string.model_row_downloading
            row.installed -> R.string.model_row_installed
            else -> R.string.model_row_not_installed
        },
    )
    val engineName = row.engine.label()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.model_row_info, engineName, row.sizeMb, status),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (isActive) {
            val description = stringResource(R.string.download_progress_description)
            val fraction = state.downloadFraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = description },
                )
                Text(stringResource(R.string.download_progress_percent, (fraction * 100).toInt()))
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = description },
                )
                Text(stringResource(if (state.downloadQueued) R.string.download_queued else R.string.download_preparing))
            }
            OutlinedButton(
                onClick = onCancelDownload,
                enabled = !state.cancelling,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    if (state.cancelling) {
                        stringResource(R.string.cancelling)
                    } else {
                        stringResource(R.string.cancel_row_button, engineName)
                    },
                )
            }
        } else if (row.installed) {
            OutlinedButton(
                onClick = { onDeleteModel(row.id) },
                enabled = !state.modelBusy && !state.busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.delete_row_button, engineName)) }
        } else {
            Button(
                onClick = { onDownload(row.id) },
                enabled = !state.modelBusy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.download_row_button, engineName)) }
        }
    }
}

@StringRes
private fun ModelMessage.textRes(): Int = when (this) {
    ModelMessage.NO_CATALOG -> R.string.msg_no_catalog
    ModelMessage.NO_CATALOG_IMPORT -> R.string.msg_no_catalog_import
    ModelMessage.NO_MODEL -> R.string.msg_no_model
    ModelMessage.DOWNLOAD_BUSY -> R.string.msg_download_busy
    ModelMessage.CANCELLED -> R.string.msg_cancelled
    ModelMessage.NETWORK -> R.string.msg_network
    ModelMessage.POLICY -> R.string.msg_policy
    ModelMessage.SIGNATURE -> R.string.msg_signature
    ModelMessage.INTEGRITY -> R.string.msg_integrity
    ModelMessage.CATALOG -> R.string.msg_catalog
    ModelMessage.FILES -> R.string.msg_files
    ModelMessage.INVALID_ZIP -> R.string.msg_invalid_zip
    ModelMessage.IMPORT_NO_MATCH -> R.string.msg_import_no_match
    ModelMessage.IMPORT_OK -> R.string.msg_import_ok
    ModelMessage.DOWNLOAD_OK -> R.string.msg_download_ok
    ModelMessage.DELETE_OK -> R.string.msg_delete_ok
    ModelMessage.UNKNOWN -> R.string.msg_unknown
}

@Composable
private fun BenchmarkCard(result: BenchmarkResult, source: BenchSource?) {
    val ok = stringResource(R.string.mark_ok)
    val fail = stringResource(R.string.mark_fail)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val sourceName = when (source) {
                BenchSource.PRIVATE -> stringResource(R.string.bench_source_private)
                BenchSource.SUBSTITUTES -> stringResource(R.string.bench_source_substitutes)
                null -> ""
            }
            val detail = MaterialTheme.typography.bodyMedium
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                stringResource(R.string.bench_speed, result.wordsPerSecond, if (result.meetsSpeedGoal) ok else fail),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.bench_median, result.medianMillis, if (result.meetsLatencyGoal) ok else fail),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.bench_max, result.maxMillis), style = detail, color = muted)
            Text(stringResource(R.string.bench_source, sourceName), style = detail, color = muted)
            Text(stringResource(R.string.bench_paragraphs, result.paragraphs), style = detail, color = muted)
            Text(stringResource(R.string.bench_words, result.words), style = detail, color = muted)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EngineTestContentPreview() {
    LectorTheme {
        EngineTestContent(
            state = EngineTestUiState(
                modelStatus = ModelStatus.READY,
                input = "Hello world.",
                output = "Hola mundo.",
                lastMillis = 420,
                benchSource = BenchSource.SUBSTITUTES,
                benchmark = BenchmarkResult(25, 900, 50_000, 18.0, 1500, 3200),
            ),
            onInputChange = {},
            onTranslate = {},
            onBenchmark = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EngineTestMissingPreview() {
    LectorTheme {
        EngineTestContent(
            state = EngineTestUiState(
                modelStatus = ModelStatus.MISSING,
                pair = PairChoice.ES_EN,
                missingEngine = EngineId.FIREFOX,
                models = listOf(ModelRow("firefox-es-en-1", EngineId.FIREFOX, PairChoice.ES_EN, 40, false)),
            ),
            onInputChange = {},
            onTranslate = {},
            onBenchmark = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EngineTestErrorPreview() {
    LectorTheme {
        EngineTestContent(
            state = EngineTestUiState(
                modelStatus = ModelStatus.ERROR,
                engineInUse = EngineId.OPUS,
                reason = Reason.RAM,
                loadOffer = LoadOffer(EngineId.OPUS, EngineId.FIREFOX),
                models = listOf(
                    ModelRow("opus-en-es-1", EngineId.OPUS, PairChoice.EN_ES, 120, true),
                    ModelRow("firefox-en-es-1", EngineId.FIREFOX, PairChoice.EN_ES, 40, true),
                ),
            ),
            onInputChange = {},
            onTranslate = {},
            onBenchmark = {},
        )
    }
}

/** Por qué un botón de acción está desactivado: un texto corto y "vivo" justo debajo del botón. */
@Composable
private fun BlockReason(block: ActionBlock?) {
    if (block == null) return
    Text(
        stringResource(
            when (block) {
                is ActionBlock.Lock -> block.reason.textRes()
                ActionBlock.NotReady -> R.string.action_not_ready
                ActionBlock.EmptyInput -> R.string.action_empty_input
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
