package io.github.diegobr4nd.lectorbilingue.ui.welcome

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.data.RowStatus
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId

/** Claro y oscuro, letra normal y al 200 %, teléfono (360 dp) y tableta (840 dp). */
@Preview(name = "Claro 360", widthDp = 360, heightDp = 720, showBackground = true)
@Preview(name = "Oscuro 360", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Claro 360 letra 200", widthDp = 360, heightDp = 720, fontScale = 2f, showBackground = true)
@Preview(
    name = "Oscuro 360 letra 200",
    widthDp = 360,
    heightDp = 720,
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Preview(name = "Claro 840", widthDp = 840, heightDp = 800, showBackground = true)
@Preview(name = "Oscuro 840", widthDp = 840, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
private annotation class WelcomePreviews

private fun pairs(installedEnEs: Boolean = false) = listOf(
    PairStatus("en-es", listOf(RowStatus("opus-en-es", EngineId.OPUS, 238_524_992, installedEnEs, null))),
    PairStatus("es-en", listOf(RowStatus("opus-es-en", EngineId.OPUS, 238_203_778, false, null))),
)

@Composable
private fun Muestra(
    step: Int,
    loading: Boolean = false,
    pairs: List<PairStatus> = pairs(),
    catalogMessage: ModelMessage? = null,
    selected: Set<String> = setOf("en-es"),
    importing: Boolean = false,
    message: ModelMessage? = null,
) = LectorTheme {
    WelcomeContent(
        step = step,
        loading = loading,
        step3 = WelcomeRules.step3(pairs, catalogMessage, 8L * 1024 * 1024 * 1024),
        selectedPairs = selected,
        importing = importing,
        message = message,
        onNext = {},
        onToggle = { _, _ -> },
        onDownload = {},
        onImport = {},
        onLater = {},
        onRetry = {},
    )
}

@WelcomePreviews
@Composable
private fun Paso1() = Muestra(1)

@WelcomePreviews
@Composable
private fun Paso2() = Muestra(2)

@WelcomePreviews
@Composable
private fun Paso3() = Muestra(3)

@WelcomePreviews
@Composable
private fun Paso3Cargando() = Muestra(3, loading = true, pairs = emptyList())

@WelcomePreviews
@Composable
private fun Paso3SinCatalogo() = Muestra(3, pairs = emptyList(), catalogMessage = ModelMessage.NO_CATALOG)

@WelcomePreviews
@Composable
private fun Paso3YaInstalado() = Muestra(3, pairs = pairs(installedEnEs = true), selected = setOf("es-en"))

@WelcomePreviews
@Composable
private fun Paso3ErrorAlImportar() =
    Muestra(3, selected = setOf("en-es", "es-en"), message = ModelMessage.INVALID_ZIP)

@WelcomePreviews
@Composable
private fun Paso3Importando() = Muestra(3, importing = true)
