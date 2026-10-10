package io.github.diegobr4nd.lectorbilingue.ui.reader

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.tooling.preview.Preview
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.data.LineHeightLevel
import io.github.diegobr4nd.lectorbilingue.data.MarginLevel
import io.github.diegobr4nd.lectorbilingue.data.PageTheme
import io.github.diegobr4nd.lectorbilingue.data.ReadingFont
import io.github.diegobr4nd.lectorbilingue.data.ReadingSettings
import io.github.diegobr4nd.lectorbilingue.data.TextAlignChoice
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair

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

/**
 * Como [ReaderPreviewSet], con alto para hasta cuatro estados de la tarjeta uno bajo otro: con letra al 200 % cada
 * párrafo con su tarjeta ocupa unos 550 dp y en 720 dp quedaban recortados.
 */
@Preview(name = "Claro 360", widthDp = 360, heightDp = 1200, showBackground = true)
@Preview(name = "Oscuro 360", widthDp = 360, heightDp = 1200, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Claro 360 letra 200", widthDp = 360, heightDp = 2600, fontScale = 2f, showBackground = true)
@Preview(
    name = "Oscuro 360 letra 200",
    widthDp = 360,
    heightDp = 2600,
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Preview(name = "Claro 840", widthDp = 840, heightDp = 1000, showBackground = true)
@Preview(name = "Oscuro 840", widthDp = 840, heightDp = 1000, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
private annotation class CardPreviewSet

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
    TocEntry(null, 1, "c3.xhtml"),
)

/** Barras sobre una página simulada (la página de Readium usa su propio fondo blanco hasta la 3c). */
@Composable
private fun BarsOverPage(
    label: PositionLabel,
    title: String? = SampleTitle,
    direction: LanguagePair = ReaderDirections[0],
    hasPrevious: Boolean = true,
    hasNext: Boolean = true,
) = LectorTheme {
    Box(Modifier.fillMaxSize()) {
        Text(
            SampleText,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingFontFamily),
            color = Color.Black,
            modifier = Modifier.fillMaxSize().padding(Spacing.l),
        )
        ReaderTopBar(
            title = title,
            direction = direction,
            onBack = {},
            onSettings = {},
            onDirection = {},
            onToc = {},
            modifier = Modifier.align(Alignment.TopCenter),
        )
        ReaderBottomBar(label, hasPrevious, hasNext, onPrevious = {}, onNext = {}, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@ReaderPreviewSet
@Composable
private fun BarrasConCapitulo() = BarsOverPage(PositionLabel("La tormenta", 42))

/** Primer capítulo: "anterior" apagado; último: "siguiente" apagado. */
@ReaderPreviewSet
@Composable
private fun BarrasPrimerCapitulo() = BarsOverPage(PositionLabel("Prólogo", 2), hasPrevious = false)

@ReaderPreviewSet
@Composable
private fun BarrasUltimoCapitulo() = BarsOverPage(PositionLabel("Epílogo", 100), hasNext = false)

@ReaderPreviewSet
@Composable
private fun BarrasSoloPorcentaje() = BarsOverPage(PositionLabel(null, 42), title = null)

/** Libro en español: el botón dice "ES → EN". */
@ReaderPreviewSet
@Composable
private fun BarrasLibroEnEspanol() = BarsOverPage(PositionLabel("La tormenta", 42), direction = ReaderDirections[1])

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

@Composable
private fun DirectionSample(current: LanguagePair) = LectorTheme {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.widthIn(max = 560.dp).padding(top = Spacing.l)) { DirectionContent(current, onSelect = {}) }
    }
}

@ReaderPreviewSet
@Composable
private fun DireccionInglesEspanol() = DirectionSample(ReaderDirections[0])

@ReaderPreviewSet
@Composable
private fun DireccionEspanolIngles() = DirectionSample(ReaderDirections[1])

/**
 * "Ajustes de lectura": claro y oscuro, 360 y 840 dp, letra 1 y 2. Alto de sobra para ver la hoja entera (en el
 * teléfono se desplaza).
 */
@Preview(name = "Claro 360", widthDp = 360, heightDp = 1300, showBackground = true)
@Preview(name = "Oscuro 360", widthDp = 360, heightDp = 1300, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Claro 360 letra 200", widthDp = 360, heightDp = 2600, fontScale = 2f, showBackground = true)
@Preview(
    name = "Oscuro 360 letra 200",
    widthDp = 360,
    heightDp = 2600,
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Preview(name = "Claro 840", widthDp = 840, heightDp = 1300, showBackground = true)
@Preview(name = "Oscuro 840", widthDp = 840, heightDp = 1300, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Claro 840 letra 200", widthDp = 840, heightDp = 2200, fontScale = 2f, showBackground = true)
@Preview(
    name = "Oscuro 840 letra 200",
    widthDp = 840,
    heightDp = 2200,
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
private annotation class SettingsPreviewSet

@Composable
private fun SettingsSample(settings: ReadingSettings) = LectorTheme {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.widthIn(max = 560.dp).padding(top = Spacing.l)) {
            ReadingSettingsContent(settings, onChange = {}, onStepScale = {}, onReset = {})
        }
    }
}

/** De fábrica: "Como el teléfono", 100 %, fuente del libro; "Restablecer" apagado. */
@SettingsPreviewSet
@Composable
private fun AjustesDeFabrica() = SettingsSample(ReadingSettings())

/** Todo cambiado: sepia, 120 %, Literata, amplio, anchos y justificado. */
@SettingsPreviewSet
@Composable
private fun AjustesCambiados() = SettingsSample(
    ReadingSettings(
        theme = PageTheme.SEPIA,
        fontScale = 1.2,
        font = ReadingFont.LITERATA,
        lineHeight = LineHeightLevel.WIDE,
        margins = MarginLevel.WIDE,
        align = TextAlignChoice.JUSTIFY,
    ),
)

/** En los topes: negro y 250 % (A+ apagado); con 75 % se apaga A−. */
@SettingsPreviewSet
@Composable
private fun AjustesEnElTope() = SettingsSample(ReadingSettings(theme = PageTheme.BLACK, fontScale = 2.5, font = ReadingFont.ATKINSON))

/*
 * Simulación en Compose de la tarjeta de tarjeta.css (la página real es un WebView). Mismos colores: la página es
 * blanca hasta la 3c en claro y en oscuro, así que la simulación también.
 */
private val PageWhite = Color(0xFFFFFFFF)
private val PageInk = Color(0xFF000000)
private val CardBg = Color(0xFFEEF2FF)
private val CardRule = Color(0xFF3B5BDB)
private val CardInk = Color(0xFF1F2937)
private val CardLink = Color(0xFF364FC7)
private val CardStripe = Color(0xFFC7D0EA)
private val WarmBg = Color(0xFFFEF3F2)
private val WarmRule = Color(0xFFB42318)
private val WarmLink = Color(0xFF912018)

private enum class SampleCard { Esqueleto, Preparando, Texto, FaltaModelo, Fallo, FalloPreparar, DemasiadoLargo }

@Composable
private fun CardSimulation(card: SampleCard) {
    val warm = card == SampleCard.FaltaModelo || card == SampleCard.Fallo || card == SampleCard.FalloPreparar ||
        card == SampleCard.DemasiadoLargo
    val fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.92f
    Row(
        Modifier
            .padding(top = 6.dp, bottom = 16.dp)
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(if (warm) WarmBg else CardBg),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (warm) WarmRule else CardRule))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            val style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingFontFamily, fontSize = fontSize, lineHeight = 1.45.em, color = CardInk)
            when (card) {
                SampleCard.Esqueleto, SampleCard.Preparando -> {
                    Text(if (card == SampleCard.Esqueleto) "Traduciendo…" else "Preparando el traductor…", style = style)
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)).background(CardStripe))
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.62f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(CardStripe))
                }
                SampleCard.Texto -> Text("Era una noche oscura y la lluvia no dejaba de caer sobre el tejado.", style = style)
                // Sin enlace: reintentar daría lo mismo.
                SampleCard.DemasiadoLargo -> Text("Este párrafo es demasiado largo para traducirlo", style = style)
                else -> {
                    val (label, action) = when (card) {
                        SampleCard.FaltaModelo -> "Falta el idioma inglés → español (227 MB)" to "Descargar"
                        SampleCard.Fallo -> "No se pudo traducir este párrafo" to "Reintentar"
                        else -> "No se pudo preparar el traductor" to "Reintentar"
                    }
                    Text(
                        buildAnnotatedString {
                            append("$label · ")
                            withStyle(SpanStyle(color = if (warm) WarmLink else CardLink, textDecoration = TextDecoration.Underline)) { append(action) }
                        },
                        style = style,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** Cada estado de la tarjeta bajo su párrafo, en dos tandas para que ninguno quede recortado. */
@Composable
private fun CardStates(cards: List<SampleCard>) = LectorTheme {
    Column(Modifier.fillMaxSize().background(PageWhite).verticalScroll(rememberScrollState()).padding(Spacing.l)) {
        for (card in cards) {
            Text(SampleText, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingFontFamily), color = PageInk)
            CardSimulation(card)
        }
        Spacer(Modifier.height(Spacing.l))
    }
}

/** Mientras traduce y con la traducción. */
@CardPreviewSet
@Composable
private fun TarjetasEstados() = CardStates(listOf(SampleCard.Esqueleto, SampleCard.Preparando, SampleCard.Texto))

/** Los avisos: falta el modelo, los dos fallos y el párrafo demasiado largo. */
@CardPreviewSet
@Composable
private fun TarjetasAvisos() =
    CardStates(listOf(SampleCard.FaltaModelo, SampleCard.Fallo, SampleCard.FalloPreparar, SampleCard.DemasiadoLargo))
