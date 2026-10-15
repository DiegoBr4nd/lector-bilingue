package io.github.diegobr4nd.lectorbilingue

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.ui.tips.TipCard
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pruebas en el teléfono de los consejos (3c-3). Con `-e shots true` guardan capturas en la caché de la app
 * (`tips_shots`); sin esa opción las borran.
 */
@RunWith(AndroidJUnit4::class)
class TipsOnDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LectorApp
    private val shotsDir = File(app.cacheDir, "tips_shots")
    private val shots = InstrumentationRegistry.getArguments().getString("shots") == "true"

    private val text = app.getString(R.string.tip_tap)
    private val gotIt = app.getString(R.string.tip_got_it)

    @After fun cleanUp() {
        if (!shots) shotsDir.deleteRecursively()
    }

    private fun saveShot(name: String) {
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        shotsDir.mkdirs()
        File(shotsDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** La tarjeta: región viva con el texto, "Entendido" ≥ 48 dp; a letra normal en una fila; al 200 % botón debajo. */
    @Test fun tarjetaSeOyeYSuBotonMide48() {
        var config by mutableStateOf(Triple(false, 1f, text)) // oscuro, letra, texto
        var dismissed = 0
        ActivityScenario.launch(ComponentActivity::class.java).use { s ->
            var px = 0
            s.onActivity { a -> px = a.resources.displayMetrics.widthPixels }
            s.onActivity { a ->
                a.setContent {
                    val (dark, f, shown) = config
                    CompositionLocalProvider(LocalDensity provides Density(px / 360f, fontScale = f)) {
                        LectorTheme(darkTheme = dark) {
                            Box(Modifier.size(360.dp, 500.dp), contentAlignment = Alignment.BottomCenter) {
                                Column(Modifier.wrapContentHeight().testTag("shot")) { TipCard(shown, onDismiss = { dismissed++ }) }
                            }
                        }
                    }
                }
            }
            for (dark in listOf(false, true)) for (f in listOf(1f, 2f)) {
                config = Triple(dark, f, text)
                rule.waitForIdle()
                val where = "${if (dark) "oscuro" else "claro"} letra ${(f * 100).toInt()}"
                val message = rule.onNode(hasContentDescription(text)).fetchSemanticsNode()
                assertEquals(LiveRegionMode.Polite, message.config[SemanticsProperties.LiveRegion], "$where: región viva")
                val button = rule.onNode(hasText(gotIt) and hasClickAction())
                button.assertHeightIsAtLeast(48.dp)
                val b = button.fetchSemanticsNode().boundsInRoot
                if (f == 1f) assertTrue(b.top < message.boundsInRoot.bottom, "$where: el botón no está al lado")
                else assertTrue(b.top >= message.boundsInRoot.bottom, "$where: el botón no está debajo")
                if (shots) saveShot("tarjeta-${if (dark) "oscuro" else "claro"}${if (f == 2f) "-letra200" else ""}.png")
            }
            rule.onNode(hasText(gotIt) and hasClickAction()).performClick()
            assertEquals(1, dismissed)
        }
    }
}
