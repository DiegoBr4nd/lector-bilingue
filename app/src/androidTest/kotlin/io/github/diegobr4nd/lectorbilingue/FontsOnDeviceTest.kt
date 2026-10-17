package io.github.diegobr4nd.lectorbilingue

import android.graphics.Typeface
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.AtkinsonFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.FONTS_DIR
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.InterFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Las fuentes de la interfaz salen de assets (una sola copia en el APK) y se ven igual que el archivo original:
 * se compara el dibujo con la misma fuente leída directo del asset, y se comprueba que no es la del sistema.
 */
@RunWith(AndroidJUnit4::class)
class FontsOnDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val sample = "Hamburgefonstiv ñ ü 0123"

    private fun ImageBitmap.pixels(): IntArray {
        val b = asAndroidBitmap()
        return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    }

    private fun shot(tag: String) = compose.onNodeWithTag(tag).captureToImage().pixels()

    @Test fun lasFuentesDeLaInterfazSonLasDeAssets() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val cases = listOf(
            "literata" to "literata_regular.ttf",
            "atkinson" to "atkinson_hyperlegible_regular.ttf",
            "inter" to "inter_variable.ttf",
        )
        compose.setContent {
            LectorTheme {
                Column {
                    for ((name, file) in cases) {
                        val family = when (name) {
                            "literata" -> ReadingFontFamily
                            "atkinson" -> AtkinsonFamily
                            else -> InterFamily
                        }
                        Text(sample, fontFamily = family, fontSize = 24.sp, modifier = Modifier.testTag("$name-app"))
                        Text(
                            sample,
                            fontFamily = FontFamily(Typeface.createFromAsset(assets, FONTS_DIR + file)),
                            fontSize = 24.sp,
                            modifier = Modifier.testTag("$name-ref"),
                        )
                    }
                    Text(sample, fontFamily = FontFamily.SansSerif, fontSize = 24.sp, modifier = Modifier.testTag("system"))
                }
            }
        }
        val system = shot("system")
        for ((name) in cases) {
            val app = shot("$name-app")
            assertTrue(app.contentEquals(shot("$name-ref")), "$name no coincide con su archivo en assets")
            assertFalse(app.contentEquals(system), "$name se ve con la fuente del sistema")
        }
    }
}
