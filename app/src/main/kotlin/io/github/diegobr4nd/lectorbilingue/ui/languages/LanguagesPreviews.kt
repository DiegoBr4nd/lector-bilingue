package io.github.diegobr4nd.lectorbilingue.ui.languages

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.core.ui.components.ModelRowState
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId

/** Claro y oscuro, letra normal y al 200 %, teléfono (360 dp) y tableta (840 dp). */
@Preview(name = "Claro 360", widthDp = 360, heightDp = 800, showBackground = true)
@Preview(name = "Oscuro 360", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Claro 360 letra 200", widthDp = 360, heightDp = 800, fontScale = 2f, showBackground = true)
@Preview(
    name = "Oscuro 360 letra 200",
    widthDp = 360,
    heightDp = 800,
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Preview(name = "Claro 840", widthDp = 840, heightDp = 900, showBackground = true)
@Preview(name = "Oscuro 840", widthDp = 840, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
private annotation class LanguagesPreviews

private fun row(engine: EngineId, state: ModelRowState) = UiRow(
    modelId = "${engine.wire}-x",
    kind = if (engine == EngineId.OPUS) EngineKind.QUALITY else EngineKind.FAST,
    sizeMb = if (engine == EngineId.OPUS) 227 else 35,
    state = state,
    engine = engine,
)

private val cards = listOf(
    LanguageCard(
        "en-es",
        listOf(row(EngineId.OPUS, ModelRowState.InUse), row(EngineId.FIREFOX, ModelRowState.NotInstalled)),
    ),
    LanguageCard(
        "es-en",
        listOf(row(EngineId.OPUS, ModelRowState.Downloading(0.4f)), row(EngineId.FIREFOX, ModelRowState.NotInstalled)),
        message = ModelMessage.NETWORK,
    ),
)

@Composable
private fun Muestra(
    loaded: Boolean = true,
    ui: LanguagesUiState = LanguagesUiState(preferenceLoaded = true, searching = false),
    cards: List<LanguageCard> = io.github.diegobr4nd.lectorbilingue.ui.languages.cards,
    autoLine: AutoLine = AutoLine(EngineKind.QUALITY, "8"),
) = LectorTheme {
    LanguagesContent(
        loaded = loaded,
        ui = ui,
        cards = cards,
        autoLine = autoLine,
        onBack = {},
        onSelectEngine = {},
        onDownload = { _, _ -> },
        onDelete = { _, _ -> },
        onCancel = {},
        onImport = {},
        onRetry = {},
        onConfirm = {},
        onDismissConfirm = {},
    )
}

@LanguagesPreviews
@Composable
private fun ConIdiomas() = Muestra()

@LanguagesPreviews
@Composable
private fun Buscando() = Muestra(ui = LanguagesUiState(preferenceLoaded = true, searching = true))

@LanguagesPreviews
@Composable
private fun Cargando() = Muestra(loaded = false, cards = emptyList())

@LanguagesPreviews
@Composable
private fun SinCatalogo() = Muestra(
    ui = LanguagesUiState(preferenceLoaded = true, searching = false, catalogMessage = ModelMessage.NO_CATALOG),
    cards = emptyList(),
)

@LanguagesPreviews
@Composable
private fun ErrorAlImportar() = Muestra(
    ui = LanguagesUiState(preferenceLoaded = true, searching = false, message = ModelMessage.INVALID_ZIP),
)

@LanguagesPreviews
@Composable
private fun ConfirmarBorrado() = Muestra(
    ui = LanguagesUiState(
        preferenceLoaded = true,
        searching = false,
        pending = PendingAction("en-es", cards[0].rows[0], Confirm(RowAction.DELETE, EngineKind.QUALITY, 227)),
    ),
)
