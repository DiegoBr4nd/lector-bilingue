package io.github.diegobr4nd.lectorbilingue

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.data.LineHeightLevel
import io.github.diegobr4nd.lectorbilingue.data.MarginLevel
import io.github.diegobr4nd.lectorbilingue.data.PageTheme
import io.github.diegobr4nd.lectorbilingue.data.ReadingFont
import io.github.diegobr4nd.lectorbilingue.data.ReadingSettings
import io.github.diegobr4nd.lectorbilingue.data.TextAlignChoice
import io.github.diegobr4nd.lectorbilingue.ui.reader.ReaderActivity
import io.github.diegobr4nd.lectorbilingue.ui.reader.ReaderDirections
import io.github.diegobr4nd.lectorbilingue.ui.reader.ReaderTopBar
import io.github.diegobr4nd.lectorbilingue.ui.reader.ReadingSettingsContent
import io.github.diegobr4nd.lectorbilingue.ui.reader.ReadingRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La hoja "Ajustes de lectura" (3c-1, Tarea 4): desde el "Aa" del Lector de verdad se elige y se guarda; con letra al
 * 200 % todos los controles se alcanzan y miden 48 dp o más. Los ajustes de la persona se guardan al empezar y se
 * devuelven al terminar; el libro es inventado ([ReaderTestBook]) y se borra.
 */
@RunWith(AndroidJUnit4::class)
class ReadingSettingsSheetOnDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LectorApp
    private val dir = File(app.cacheDir, "reading_sheet_test").apply { deleteRecursively(); mkdirs() }
    private val created = mutableListOf<String>()
    private var hosted: ActivityScenario<ComponentActivity>? = null

    /** Los ajustes de Juan: se devuelven tal cual en [cleanUp]. */
    private val saved = app.settings.readingSettings

    @After fun cleanUp() = runBlocking<Unit> {
        hosted?.close()
        app.settings.readingSettings = saved
        for (id in created) {
            app.openBooks.close(id)
            app.books.delete(id)
        }
        dir.deleteRecursively()
    }

    @Test fun desdeElLectorSeEligeSepiaSeAgrandaYSeRestablece() = runBlocking<Unit> {
        app.settings.readingSettings = ReadingSettings()
        val id = ReaderTestBook.importAndOpen(app, dir).also { created += it }
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id)).use {
            rule.waitUntil("botón Aa", 10_000) { exists(hasContentDescription("Ajustes de lectura")) }
            rule.onNodeWithContentDescription("Ajustes de lectura").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
            rule.waitUntil("hoja abierta", 5_000) { exists(hasText("Tamaño de letra")) }

            // De fábrica: "Como el teléfono" elegido y "Restablecer" apagado.
            rule.onNodeWithText("Como el teléfono").assertIsSelected().assert(state("Elegido"))
            rule.onNodeWithText("Restablecer").performScrollTo().assertIsNotEnabled()

            rule.onNodeWithContentDescription("Tema Sepia").performScrollTo().performClick()
            rule.onNodeWithContentDescription("Letra más grande").performScrollTo().performClick()
            rule.onNodeWithContentDescription("Letra más grande").performClick()
            rule.waitForIdle()
            assertEquals(ReadingSettings(theme = PageTheme.SEPIA, fontScale = 1.2), app.settings.readingSettings)
            rule.onNodeWithContentDescription("Tema Sepia").assertIsSelected().assert(state("Elegido"))
            rule.onNodeWithText("Como el teléfono").assertIsNotSelected()
            rule.onNodeWithContentDescription("Tamaño de letra, 120 por ciento").assertIsDisplayed()

            rule.onNodeWithText("Restablecer").performScrollTo().assertIsEnabled().performClick()
            rule.waitForIdle()
            assertTrue(app.settings.readingSettings.isFactory)
            rule.onNodeWithText("Restablecer").assertIsNotEnabled()
            rule.onNodeWithText("Como el teléfono").performScrollTo().assertIsSelected()
            rule.onNodeWithContentDescription("Tamaño de letra, 100 por ciento").performScrollTo().assertIsDisplayed()
        }
    }

    /** 360 dp de ancho y 640 de alto: cada control (20) se alcanza desplazando y mide al menos 48 x 48 dp. */
    @Test fun conLetraAl200TodosLosControlesSeVenYMiden48() = assertControls(fontScale = 2f, widthDp = 360)

    @Test fun conLetraNormalTodosLosControlesSeVenYMiden48() = assertControls(fontScale = 1f, widthDp = 360)

    /**
     * Junto al corte de los segmentados (SegmentedRowMin): con las opciones largas elegidas (llevan ✓ y negrita),
     * al 100 % y al 109 % en 360 dp siguen en fila y ningún nombre se corta.
     */
    @Test fun segmentadosElegidosLargosNoSeCortanAl100() = assertControls(1f, 360, longSegments, segmentLabels, segmented = true)

    @Test fun segmentadosElegidosLargosNoSeCortanAl109() = assertControls(1.09f, 360, longSegments, segmentLabels, segmented = true)

    private val longSegments = ReadingSettings(
        lineHeight = LineHeightLevel.COMPACT, margins = MarginLevel.NARROW, align = TextAlignChoice.JUSTIFY,
    )
    private val segmentLabels =
        listOf("Compacto", "Normal", "Amplio", "Estrechos", "Normales", "Anchos", "Izquierda", "Justificado")

    @Test fun enLosTopesSeApaganAMenosYAMas() {
        var settings by mutableStateOf(ReadingSettings(fontScale = ReadingSettings.MAX_SCALE))
        show(fontScale = 1f, widthDp = 360) { ReadingSettingsContent(settings, {}, {}, {}) }
        rule.onNodeWithContentDescription("Letra más grande").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Letra más pequeña").assertIsEnabled()
        settings = ReadingSettings(fontScale = ReadingSettings.MIN_SCALE)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Letra más pequeña").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Letra más grande").assertIsEnabled()
    }

    /** [start] distinto de fábrica para que "Restablecer" esté encendido. [segmented]: se exige la fila segmentada. */
    /** TalkBack: la fila "Original del libro" dice también su ayuda; "Restablecer" dice qué hace al tocarlo. */
    @Test fun talkBackOyeLaAyudaDeOriginalYLaAccionDeRestablecer() {
        show(fontScale = 1f, widthDp = 360) { ReadingSettingsContent(ReadingSettings(theme = PageTheme.SEPIA), {}, {}, {}) }
        rule.onNodeWithContentDescription("Fuente Original del libro. Respeta la letra del libro").assertExists()
        rule.onNodeWithText("Restablecer").assert(
            SemanticsMatcher("acción con nombre") {
                it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label == "restablecer los ajustes de fábrica"
            },
        )
    }

    private fun assertControls(
        fontScale: Float,
        widthDp: Int,
        start: ReadingSettings = ReadingSettings(theme = PageTheme.SEPIA),
        labels: List<String> = listOf("Claro", "Sepia", "Oscuro", "Negro", "Estrechos", "Justificado", "Atkinson Hyperlegible"),
        segmented: Boolean = false,
    ) {
        var settings by mutableStateOf(start)
        show(fontScale, widthDp) {
            ReadingSettingsContent(
                settings,
                onChange = { settings = it },
                onStepScale = { up -> settings = settings.copy(fontScale = ReadingRules.step(settings.fontScale, up)) },
                onReset = { settings = ReadingSettings() },
            )
        }
        val where = "al ${(fontScale * 100).toInt()} % en $widthDp dp"
        val nodes = rule.onAllNodes(hasClickAction())
        val count = nodes.fetchSemanticsNodes().size
        // Como el teléfono + 4 muestras + A− y A+ + 4 fuentes + 3 + 3 + 2 opciones + Restablecer.
        assertEquals(20, count, "$where: controles")
        for (i in 0 until count) {
            nodes[i].performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        // Los nombres de las muestras y de las opciones no se cortan (ninguno queda con elipsis ni fuera).
        if (segmented) {
            // En fila, cada segmento es un tercio del ancho (en lista, cada fila ocupa el ancho entero).
            val w = with(rule.density) { rule.onNode(hasContentDescription("Márgenes Estrechos")).fetchSemanticsNode().size.width.toDp() }
            assertTrue(w < (widthDp / 2).dp, "$where: los segmentados pasaron a lista ($w)")
        }
        for (text in labels) {
            rule.onNode(hasText(text), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            // Ni elipsis ni una línea más ancha que el texto en pantalla (hasVisualOverflow no sirve: en FlowRow
            // devuelve el ancho de la medida intrínseca).
            val r = layout.single()
            val cut = (0 until r.lineCount).any { r.isLineEllipsized(it) || r.getLineRight(it) - r.getLineLeft(it) > r.size.width + 1 }
            assertTrue(!cut, "$where: \"$text\" se corta (${r.lineCount} líneas, ${r.size})")
        }
    }

    // Uso: am instrument -w -e shots true -e class …ReadingSettingsSheetOnDeviceTest#capturas (y luego -e shots clean).
    /**
     * Capturas para el informe (solo con `-e shots true`; con `-e shots clean` se borran): claro/oscuro, 360/840,
     * letra 1 y 2, de fábrica y con todo cambiado. Se dibujan con menos px por dp para que la hoja entera quepa.
     */
    @Test fun capturas() {
        val mode = InstrumentationRegistry.getArguments().getString("shots")
        val out = File(app.cacheDir, "reading_sheet_shots")
        if (mode == "clean") out.deleteRecursively()
        assumeTrue(mode == "true")
        out.mkdirs()
        val changed = ReadingSettings(
            theme = PageTheme.SEPIA, fontScale = 1.2, font = ReadingFont.LITERATA,
            lineHeight = LineHeightLevel.WIDE, margins = MarginLevel.WIDE, align = TextAlignChoice.JUSTIFY,
        )
        var config by mutableStateOf(Shot(false, 360, 1f, ReadingSettings(), 1300))
        ActivityScenario.launch(ComponentActivity::class.java).use { s ->
            var px = 0 to 0
            s.onActivity { a -> px = a.resources.displayMetrics.let { it.widthPixels to it.heightPixels } }
            s.onActivity { a ->
                a.setContent {
                    val c = config
                    val d = min(px.first / c.widthDp.toFloat(), px.second / c.heightDp.toFloat()) * 0.88f
                    CompositionLocalProvider(LocalDensity provides Density(d, fontScale = c.fontScale)) {
                        LectorTheme(darkTheme = c.dark) {
                            Box(Modifier.size(c.widthDp.dp, c.heightDp.dp), contentAlignment = Alignment.TopCenter) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = MaterialTheme.shapes.extraLarge,
                                    modifier = Modifier.widthIn(max = 560.dp).wrapContentHeight().testTag("shot"),
                                ) {
                                    if (c.bar) {
                                        ReaderTopBar("La ciudad de los faros apagados", ReaderDirections[0], {}, {}, {}, {})
                                    } else {
                                        Column(Modifier.padding(top = Spacing.l)) { ReadingSettingsContent(c.settings, {}, {}, {}) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (dark in listOf(false, true)) for (w in listOf(360, 840)) for (f in listOf(1f, 2f)) {
                for ((name, st) in listOf("fabrica" to ReadingSettings(), "cambiados" to changed)) {
                    val h = if (f == 2f) (if (w == 360) 2700 else 2000) else 1300
                    config = Shot(dark, w, f, st, h)
                    rule.waitForIdle()
                    val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
                    val file = File(out, "ajustes-$name-${if (dark) "oscuro" else "claro"}-$w${if (f == 2f) "-letra200" else ""}.png")
                    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            // La barra del Lector con "Aa", en el teléfono.
            for (dark in listOf(false, true)) for (f in listOf(1f, 2f)) {
                config = Shot(dark, 360, f, ReadingSettings(), 400, bar = true)
                rule.waitForIdle()
                val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
                val file = File(out, "barra-aa-${if (dark) "oscuro" else "claro"}-360${if (f == 2f) "-letra200" else ""}.png")
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    private data class Shot(
        val dark: Boolean, val widthDp: Int, val fontScale: Float, val settings: ReadingSettings, val heightDp: Int,
        val bar: Boolean = false,
    )

    /** La hoja sola en una caja de [widthDp] x 640 dp con la letra a [fontScale] (simulada en Compose). */
    private fun show(fontScale: Float, widthDp: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java).also { hosted = it }
        scenario.onActivity { a ->
            a.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                    LectorTheme { Box(Modifier.size(widthDp.dp, 640.dp)) { content() } }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher) = rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun state(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)
}
