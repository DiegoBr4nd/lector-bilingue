package io.github.diegobr4nd.lectorbilingue.ui.home

import io.github.diegobr4nd.lectorbilingue.ui.library.HomeState
import io.github.diegobr4nd.lectorbilingue.ui.library.PairDownload
import io.github.diegobr4nd.lectorbilingue.ui.library.PairCard
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.models.DownloadState

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
private annotation class HomePreviews

@Composable
private fun Muestra(loaded: Boolean = true, state: HomeState) = LectorTheme {
    HomeContent(loaded = loaded, state = state, onLanguages = {}, onDeveloper = {}, onCancel = {})
}

private val ready = PairCard("en-es", EngineKind.QUALITY)
private val downloading = PairCard(
    "es-en", null,
    listOf(PairDownload("opus-es-en", EngineKind.QUALITY, DownloadState(DownloadState.Status.RUNNING, 90_000_000, 238_000_000, null))),
)
private val missing = PairCard("en-es", null, missing = EngineKind.QUALITY)

@HomePreviews
@Composable
private fun ConIdiomaYDescarga() = Muestra(state = HomeState(listOf(ready, downloading), false))

@HomePreviews
@Composable
private fun SinIdiomas() = Muestra(state = HomeState(emptyList(), true))

@HomePreviews
@Composable
private fun Cargando() = Muestra(loaded = false, state = HomeState(emptyList(), false))

@HomePreviews
@Composable
private fun MotorElegidoAusente() = Muestra(state = HomeState(listOf(missing), false))
