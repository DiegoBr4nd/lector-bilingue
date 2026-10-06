package io.github.diegobr4nd.lectorbilingue.ui.reader

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing

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
private annotation class ReaderPreviewSet

// Textos inventados. La página de Readium no se puede previsualizar (es un WebView): se simula con texto fijo.
private const val SampleTitle = "La ciudad de los faros apagados y otras historias de marineros"
private const val SampleText = "Párrafo de muestra inventado. La lluvia golpeaba los cristales del faro mientras " +
    "la guardiana contaba los barcos que no habían vuelto."

private val sampleToc = listOf(
    TocEntry("Portada", 0, "portada.xhtml"),
    TocEntry("Primera parte: el puerto", 0, "p1.xhtml"),
    TocEntry("La tormenta", 1, "c1.xhtml"),
    TocEntry("El faro que no se apagaba nunca, ni siquiera en las noches más largas del invierno", 1, "c2.xhtml"),
    TocEntry("Segunda parte: el regreso", 0, "p2.xhtml"),
    TocEntry("c3.xhtml", 1, "c3.xhtml"),
)

/** Barras sobre una página simulada (la página de Readium usa su propio fondo blanco hasta la 3c). */
@Composable
private fun BarsOverPage(label: PositionLabel, title: String? = SampleTitle) = LectorTheme {
    Box(Modifier.fillMaxSize()) {
        Text(
            SampleText,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingFontFamily),
            color = Color.Black,
            modifier = Modifier.fillMaxSize().padding(Spacing.l),
        )
        ReaderTopBar(title = title, onBack = {}, onToc = {}, modifier = Modifier.align(Alignment.TopCenter))
        ReaderBottomBar(label, Modifier.align(Alignment.BottomCenter))
    }
}

@ReaderPreviewSet
@Composable
private fun BarrasConCapitulo() = BarsOverPage(PositionLabel("La tormenta", 42))

@ReaderPreviewSet
@Composable
private fun BarrasSoloPorcentaje() = BarsOverPage(PositionLabel(null, 42), title = null)

@ReaderPreviewSet
@Composable
private fun BarrasCargandoPosicion() = BarsOverPage(PositionLabel(null, null))

@Composable
private fun SheetSample(entries: List<TocEntry>, currentIndex: Int?) = LectorTheme {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(top = Spacing.l)) { TocContent(entries, currentIndex, onSelect = {}) }
    }
}

@ReaderPreviewSet
@Composable
private fun IndiceConCapituloActual() = SheetSample(sampleToc, currentIndex = 2)

@ReaderPreviewSet
@Composable
private fun IndiceVacio() = SheetSample(emptyList(), currentIndex = null)
