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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
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
import io.github.diegobr4nd.lectorbilingue.ui.theme.LectorBilingueTheme

/** Conecta el ViewModel con la pantalla sin estado. */
@Composable
fun EngineTestScreen(
    modifier: Modifier = Modifier,
    viewModel: EngineTestViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // En Android 13+ se pide el permiso de notificaciones antes de encolar; si se niega, se descarga igual.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.downloadModel()
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }
    EngineTestContent(
        state = state,
        onInputChange = viewModel::onInputChange,
        onTranslate = viewModel::translate,
        onBenchmark = viewModel::runBenchmark,
        onDownload = {
            val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsAsk) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.downloadModel()
        },
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
    onDownload: () -> Unit = {},
    onImport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ready = state.modelStatus == ModelStatus.READY
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
            ModelStatusCard(state)
            ModelManagerCard(state, onDownload, onImport)
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                label = { Text(stringResource(R.string.input_label)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onTranslate,
                enabled = !state.busy && ready && state.input.isNotBlank(),
            ) { Text(stringResource(R.string.translate_button)) }
            if (state.output.isNotEmpty()) {
                SelectionContainer {
                    Text(state.output, style = MaterialTheme.typography.bodyLarge)
                }
                state.lastMillis?.let { Text(stringResource(R.string.elapsed_ms, it)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBenchmark, enabled = !state.busy && ready) {
                    Text(stringResource(R.string.benchmark_button))
                }
                if (state.busy) {
                    val working = stringResource(R.string.working_description)
                    CircularProgressIndicator(Modifier.semantics { contentDescription = working })
                    Text(stringResource(R.string.bench_measuring), style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.benchmark?.let { BenchmarkCard(it, state.benchSource) }
            state.errorMessage?.takeIf { state.modelStatus != ModelStatus.ERROR }?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ModelStatusCard(state: EngineTestUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.modelStatus) {
                ModelStatus.LOADING -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val working = stringResource(R.string.working_description)
                    CircularProgressIndicator(Modifier.semantics { contentDescription = working })
                    Text(stringResource(R.string.model_loading))
                }
                ModelStatus.READY -> Text(stringResource(R.string.model_ready))
                ModelStatus.MISSING -> {
                    Text(stringResource(R.string.model_missing, state.modelPath))
                    Text(stringResource(R.string.model_missing_hint))
                }
                ModelStatus.ERROR -> Text(
                    stringResource(R.string.model_error, state.errorMessage ?: stringResource(R.string.error_generic)),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ModelManagerCard(state: EngineTestUiState, onDownload: () -> Unit, onImport: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onDownload,
                enabled = !state.modelBusy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    state.modelSizeMb?.let { stringResource(R.string.download_button_size, it) }
                        ?: stringResource(R.string.download_button),
                )
            }
            Text(
                stringResource(R.string.download_wifi_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.downloading) {
                val description = stringResource(R.string.download_progress_description)
                val fraction = state.downloadFraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = description
                                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                            },
                    )
                    Text(stringResource(R.string.download_progress_percent, (fraction * 100).toInt()))
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = description },
                    )
                    Text(stringResource(R.string.download_preparing))
                }
            }
            OutlinedButton(
                onClick = onImport,
                enabled = !state.modelBusy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.import_button)) }
            state.modelMessage?.let {
                val isError = it != ModelMessage.IMPORT_OK && it != ModelMessage.CANCELLED
                Text(
                    stringResource(it.textRes()),
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@StringRes
private fun ModelMessage.textRes(): Int = when (this) {
    ModelMessage.NO_CATALOG -> R.string.msg_no_catalog
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
    ModelMessage.IMPORT_OK -> R.string.msg_import_ok
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
    LectorBilingueTheme {
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
