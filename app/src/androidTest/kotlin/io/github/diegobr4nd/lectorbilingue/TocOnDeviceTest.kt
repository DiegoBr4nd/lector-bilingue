package io.github.diegobr4nd.lectorbilingue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.ui.reader.TocContent
import io.github.diegobr4nd.lectorbilingue.ui.reader.TocEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** El Índice con letra grande (diseño, puerta 3a): al abrirlo no queda ninguna entrada cortada bajo el título. */
@RunWith(AndroidJUnit4::class)
class TocOnDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val entries = listOf(
        TocEntry("Portada", 0, "portada.xhtml"),
        TocEntry("Primera parte: el puerto", 0, "p1.xhtml"),
        TocEntry("La tormenta", 1, "c1.xhtml"),
        TocEntry("El faro que no se apagaba nunca, ni siquiera en las noches más largas del invierno", 1, "c2.xhtml"),
        TocEntry("Segunda parte: el regreso", 0, "p2.xhtml"),
        TocEntry("Epílogo", 1, "c3.xhtml"),
    )

    // Varias alturas: el corte sale cuando lo que hay debajo del capítulo actual no llena la hoja y la lista
    // retrocede para no dejar hueco (con 720 dp al 200 %, "Portada" quedaba a medias).
    @Test fun conLetraAl200NingunaEntradaQuedaCortadaArriba() = assertNoEntryCutAtTop(fontScale = 2f, heights = 560..900 step 20)

    @Test fun conLetraNormalNingunaEntradaQuedaCortadaArriba() = assertNoEntryCutAtTop(fontScale = 1f, heights = 200..480 step 20)

    /**
     * En una hoja de 360 dp de ancho y cada alto de [heights], con el capítulo actual (el 3.º) cerca del final:
     * cada entrada compuesta queda entera debajo del título o del todo oculta, y el capítulo actual se ve entero.
     * El Índice se vuelve a crear en cada alto (`key`), como cuando se abre la hoja. Se usa la posición sin
     * recortar (la lista recorta `boundsInRoot`).
     */
    private fun assertNoEntryCutAtTop(fontScale: Float, heights: IntProgression) {
        var height by mutableIntStateOf(heights.first)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                LectorTheme {
                    Box(Modifier.size(360.dp, height.dp)) {
                        key(height) { TocContent(entries, currentIndex = 2, onSelect = {}) }
                    }
                }
            }
        }
        for (h in heights) {
            height = h
            rule.waitForIdle()
            with(rule.density) {
                val heading = rule.onNodeWithText("Índice").fetchSemanticsNode()
                val listTop = (heading.positionInRoot.y + heading.size.height).toDp()
                val where = "al ${(fontScale * 100).toInt()} % y $h dp"
                for (entry in entries) {
                    for (node in rule.onAllNodesWithText(entry.title!!).fetchSemanticsNodes()) {
                        val top = node.positionInRoot.y.toDp()
                        val bottom = (node.positionInRoot.y + node.size.height).toDp()
                        assertTrue(top >= listTop - 0.5.dp || bottom <= listTop + 0.5.dp, "$where \"${entry.title}\" queda cortada: va de $top a $bottom y la lista empieza en $listTop")
                    }
                }
                val current = rule.onNodeWithText("La tormenta").fetchSemanticsNode()
                val top = current.positionInRoot.y.toDp()
                val bottom = (current.positionInRoot.y + current.size.height).toDp()
                assertTrue(top >= listTop - 0.5.dp && bottom <= h.dp + 0.5.dp, "$where el capítulo actual no se ve entero: $top a $bottom")
            }
        }
    }
}
