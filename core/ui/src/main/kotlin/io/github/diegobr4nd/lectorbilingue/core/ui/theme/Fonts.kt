package io.github.diegobr4nd.lectorbilingue.core.ui.theme

import android.content.res.AssetManager
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** Carpeta de assets con las fuentes: la misma que Readium sirve al libro. Una sola copia en el APK. */
const val FONTS_DIR = "lector/fuentes/"

/**
 * Las fuentes de la interfaz, leídas de assets (antes había una segunda copia en res/font: ~1 MB más).
 * [install] se llama al arrancar la app y al empezar [LectorTheme] (también en previews y pruebas); la primera gana.
 * Las familias se crean la primera vez que se piden.
 */
object LectorFonts {
    @Volatile private var assets: AssetManager? = null

    fun install(assets: AssetManager) {
        if (this.assets == null) this.assets = assets
    }

    private fun assets(): AssetManager = checkNotNull(assets) { "LectorFonts.install() no se llamó antes de usar una fuente" }

    private fun font(file: String, weight: FontWeight, style: FontStyle = FontStyle.Normal): Font =
        Font(FONTS_DIR + file, assets(), weight, style)

    /** Inter variable: un solo archivo; el peso se elige con el eje "wght". */
    @OptIn(ExperimentalTextApi::class)
    val inter: FontFamily by lazy {
        FontFamily(
            listOf(400, 500, 600, 700).map { w ->
                Font(
                    FONTS_DIR + "inter_variable.ttf",
                    assets(),
                    FontWeight(w),
                    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
                )
            },
        )
    }

    val literata: FontFamily by lazy {
        FontFamily(
            font("literata_regular.ttf", FontWeight.Normal),
            font("literata_semibold.ttf", FontWeight.SemiBold),
            font("literata_italic.ttf", FontWeight.Normal, FontStyle.Italic),
        )
    }

    val atkinson: FontFamily by lazy {
        FontFamily(
            font("atkinson_hyperlegible_regular.ttf", FontWeight.Normal),
            font("atkinson_hyperlegible_bold.ttf", FontWeight.Bold),
            font("atkinson_hyperlegible_italic.ttf", FontWeight.Normal, FontStyle.Italic),
        )
    }
}
