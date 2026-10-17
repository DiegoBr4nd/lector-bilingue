package io.github.diegobr4nd.lectorbilingue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Auditoría M3 (I3): Idiomas, Cómo se usa, Acerca de y Licencias comparten la barra superior de la Biblioteca.
 * En un teléfono pequeño (360 × 640 dp) con letra al 200 %: el título es encabezado, «Volver» mide 48 dp, la barra
 * en reposo es del color del fondo y, al desplazar el contenido con el dedo, cambia de tono (se nota el borde).
 */
internal fun ComposeContentTestRule.assertSharedTopBar(title: String, content: @Composable () -> Unit) {
    var background = Color.Unspecified
    setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
            LectorTheme {
                background = MaterialTheme.colorScheme.background
                Box(Modifier.size(360.dp, 640.dp)) { content() }
            }
        }
    }
    waitUntil(5_000) { onAllNodes(hasText(title) and isHeading()).fetchSemanticsNodes().isNotEmpty() }
    val heading = onAllNodes(hasText(title) and isHeading()).onFirst().assertIsDisplayed()
    onNodeWithContentDescription("Volver").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    val y = heading.fetchSemanticsNode().boundsInRoot.center.y.toInt()
    // Un punto de la barra a la derecha, a la altura del título (dentro de la caja de 360 dp).
    val x = with(density) { 352.dp.roundToPx() }
    fun barPixel(): Int = onRoot().captureToImage().asAndroidBitmap().getPixel(x, y)
    assertEquals(background.toArgb(), barPixel(), "en reposo la barra no es del color del fondo")
    onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp() }
    waitForIdle()
    assertNotEquals(background.toArgb(), barPixel(), "al desplazar, la barra no cambió de tono")
}
