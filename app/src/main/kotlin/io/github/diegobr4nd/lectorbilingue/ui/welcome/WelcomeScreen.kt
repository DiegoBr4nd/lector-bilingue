package io.github.diegobr4nd.lectorbilingue.ui.welcome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.components.LectorIcons
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ButtonShape
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.core.ui.R as UiR
import io.github.diegobr4nd.lectorbilingue.data.ModelActions
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.ui.nav.Route
import io.github.diegobr4nd.lectorbilingue.ui.textRes

/** Ancho máximo del contenido: en tabletas la Bienvenida no se estira de lado a lado. */
private val ContentMaxWidth = 560.dp

/**
 * Bienvenida en 3 pasos. [step] (1 a 3) lo maneja la navegación; [onFinish] se llama al descargar,
 * importar o elegir "Más tarde" (quien lo recibe marca la Bienvenida como hecha y abre Inicio).
 */
@Composable
fun WelcomeScreen(
    step: Int,
    hub: ModelHubApi,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: WelcomeViewModel = viewModel(factory = viewModelFactory { initializer { WelcomeViewModel(hub) } })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pairs by viewModel.pairs.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val finish by rememberUpdatedState(onFinish)

    val step3 = WelcomeRules.step3(pairs, state.catalogMessage, viewModel.totalRam())
    val selected = step3.options.filter { state.toggled[it.pair] ?: it.selectedByDefault }
    val toDownload = selected.filter { !it.recommended.installed }

    // En Android 13 o superior se pide el permiso de notificaciones antes de encolar; si se niega, se descarga igual.
    var pendingIds by rememberSaveable { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingIds?.split(',')?.filter { it.isNotEmpty() }?.let {
            viewModel.download(it)
            finish()
        }
        pendingIds = null
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }

    // Solo navega la composición actual, y solo desde el paso 3; en otro paso el aviso espera a que vuelva.
    val currentStep by rememberUpdatedState(step)
    LaunchedEffect(state.importOk) {
        if (state.importOk) {
            val onLast = currentStep == Route.WELCOME_STEPS
            viewModel.ackImport(showSuccess = !onLast)
            if (onLast) finish()
        }
    }

    WelcomeContent(
        step = step,
        loading = !loaded || (state.refreshing && step3.options.isEmpty()),
        step3 = step3,
        selectedPairs = selected.mapTo(mutableSetOf()) { it.pair },
        importing = state.importing,
        message = state.message,
        onNext = onNext,
        onToggle = viewModel::toggle,
        onDownload = {
            val ids = toDownload.map { it.recommended.modelId }
            val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsAsk) {
                pendingIds = ids.joinToString(",")
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.download(ids)
                finish()
            }
        },
        onImport = { importPicker.launch(arrayOf("application/zip")) },
        onLater = onFinish,
        onRetry = viewModel::refresh,
        modifier = modifier,
    )
}

/** La Bienvenida sin estado propio: se dibuja igual con datos de verdad o de muestra. */
@Composable
fun WelcomeContent(
    step: Int,
    loading: Boolean,
    step3: WelcomeRules.Step3,
    selectedPairs: Set<String>,
    importing: Boolean,
    message: ModelMessage?,
    onNext: () -> Unit,
    onToggle: (pair: String, selected: Boolean) -> Unit,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onLater: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().padding(horizontal = Spacing.xl, vertical = Spacing.l),
            ) {
                when (step) {
                    1 -> InfoStep(
                        icon = LectorIcons.MenuBook,
                        title = stringResource(R.string.welcome_step1_title),
                        body = stringResource(R.string.welcome_step1_body),
                        step = 1,
                        onNext = onNext,
                        modifier = Modifier.weight(1f),
                    )
                    2 -> InfoStep(
                        icon = LectorIcons.Lock,
                        title = stringResource(R.string.welcome_step2_title),
                        body = stringResource(R.string.welcome_step2_body),
                        step = 2,
                        onNext = onNext,
                        modifier = Modifier.weight(1f),
                    )
                    else -> LanguageStep(
                        loading = loading,
                        step3 = step3,
                        selectedPairs = selectedPairs,
                        importing = importing,
                        message = message,
                        onToggle = onToggle,
                        onDownload = onDownload,
                        onImport = onImport,
                        onLater = onLater,
                        onRetry = onRetry,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (step >= TOTAL_STEPS) StepIndicator(step, Modifier.padding(top = Spacing.l))
            }
        }
    }
}

@Composable
private fun welcomeTitleStyle() = MaterialTheme.typography.headlineMedium.copy(fontFamily = ReadingFontFamily)

@Composable
private fun InfoStep(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    step: Int,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null, // Decorativo: el título ya dice de qué trata.
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    title,
                    style = welcomeTitleStyle(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        StepIndicator(step, Modifier.padding(vertical = Spacing.l))
        Button(
            onClick = onNext,
            shape = ButtonShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.welcome_next)) }
    }
}

@Composable
private fun LanguageStep(
    loading: Boolean,
    step3: WelcomeRules.Step3,
    selectedPairs: Set<String>,
    importing: Boolean,
    message: ModelMessage?,
    onToggle: (String, Boolean) -> Unit,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onLater: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chosen = step3.options.filter { it.pair in selectedPairs && !it.recommended.installed }
    val bytes = WelcomeRules.downloadBytes(chosen)
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(R.string.welcome_step3_title),
            style = welcomeTitleStyle(),
            modifier = Modifier.semantics { heading() },
        )
        when {
            loading -> Loading(stringResource(R.string.welcome_loading))
            step3.options.isEmpty() -> {
                Text(
                    stringResource((step3.message ?: ModelMessage.NO_CATALOG).textRes()),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(
                    onClick = onRetry,
                    enabled = !importing,
                    shape = ButtonShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.welcome_retry)) }
            }
            else -> {
                for (option in step3.options) {
                    OptionCard(option, option.pair in selectedPairs, onToggle)
                }
                if (bytes > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Icon(
                            painter = painterResource(LectorIcons.Wifi),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.welcome_wifi_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (step3.canDownload) {
                    Button(
                        onClick = onDownload,
                        enabled = chosen.isNotEmpty() && !importing,
                        shape = ButtonShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(painterResource(LectorIcons.Download), contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.s))
                        Text(
                            if (bytes > 0) {
                                stringResource(R.string.welcome_download, ModelActions.megabytes(bytes))
                            } else {
                                stringResource(R.string.welcome_download_none)
                            },
                        )
                    }
                } else {
                    Button(
                        onClick = onLater,
                        shape = ButtonShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.welcome_start_reading)) }
                }
            }
        }
        if (importing) Loading(stringResource(R.string.welcome_importing))
        if (message != null) {
            Text(
                stringResource(message.textRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        // Importar y "Más tarde" se ven siempre, aunque no haya catálogo o esté cargando.
        TextButton(
            onClick = onImport,
            enabled = !importing,
            shape = ButtonShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(painterResource(LectorIcons.FolderOpen), contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.welcome_import))
        }
        if (loading || step3.options.isEmpty() || step3.canDownload) {
            TextButton(
                onClick = onLater,
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.welcome_later)) }
        }
    }
}

@Composable
private fun Loading(text: String) {
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun pairName(pair: String): String = when (pair) {
    "en-es" -> stringResource(R.string.welcome_pair_en_es)
    "es-en" -> stringResource(R.string.welcome_pair_es_en)
    else -> stringResource(R.string.welcome_pair_other, pair)
}

@Composable
private fun OptionCard(option: WelcomeRules.PairOption, checked: Boolean, onToggle: (String, Boolean) -> Unit) {
    val row = option.recommended
    val engineName = stringResource(if (row.engine == EngineId.OPUS) UiR.string.engine_quality else UiR.string.engine_fast)
    val sizeMb = ModelActions.megabytes(row.sizeBytes)
    val detail = when {
        row.installed -> stringResource(R.string.welcome_option_installed, engineName)
        option.selectedByDefault -> stringResource(R.string.welcome_option_recommended, engineName, sizeMb)
        else -> stringResource(R.string.welcome_option_optional, engineName, sizeMb)
    }
    val on = checked || row.installed
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = if (on) 2.dp else 1.dp,
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .toggleable(
                    value = on,
                    enabled = !row.installed,
                    role = Role.Checkbox,
                    onValueChange = { onToggle(option.pair, it) },
                )
                .heightIn(min = 48.dp)
                .padding(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Column(Modifier.weight(1f)) {
                Text(pairName(option.pair), style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Decorativo: el estado lo anuncia el propio control (marcado o no).
            Icon(
                painter = painterResource(if (on) LectorIcons.CheckBox else LectorIcons.CheckBoxOutlineBlank),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Puntos de avance. TalkBack lee "Paso N de 3"; el paso actual además es más ancho (no depende del color). */
@Composable
private fun StepIndicator(step: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.welcome_step_indicator, step, TOTAL_STEPS)
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 1..TOTAL_STEPS) {
            val current = i == step
            Box(
                Modifier
                    .clearAndSetSemantics {}
                    .size(width = if (current) 24.dp else 8.dp, height = 8.dp)
                    .background(
                        if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
        }
    }
}

private const val TOTAL_STEPS = 3
