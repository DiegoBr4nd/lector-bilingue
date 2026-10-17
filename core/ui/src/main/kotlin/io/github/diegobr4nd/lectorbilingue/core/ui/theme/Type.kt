package io.github.diegobr4nd.lectorbilingue.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/** Interfaz: Inter variable. */
val InterFamily: FontFamily get() = LectorFonts.inter

/** Lectura y títulos grandes: Literata. */
val ReadingFontFamily: FontFamily get() = LectorFonts.literata

/**
 * Atkinson Hyperlegible (Braille Institute, OFL 1.1): letra pensada para baja visión. Aquí solo para mostrarla en la
 * hoja de ajustes; en el libro la sirve Readium desde los mismos assets.
 */
val AtkinsonFamily: FontFamily get() = LectorFonts.atkinson

private fun TextStyle.inter() = copy(fontFamily = InterFamily)

private val typography: Typography by lazy {
    Typography().let { b ->
        Typography(
            displayLarge = b.displayLarge.inter(),
            displayMedium = b.displayMedium.inter(),
            displaySmall = b.displaySmall.inter(),
            headlineLarge = b.headlineLarge.inter(),
            headlineMedium = b.headlineMedium.inter(),
            headlineSmall = b.headlineSmall.inter(),
            titleLarge = b.titleLarge.inter(),
            titleMedium = b.titleMedium.inter(),
            titleSmall = b.titleSmall.inter(),
            bodyLarge = b.bodyLarge.inter(),
            bodyMedium = b.bodyMedium.inter(),
            bodySmall = b.bodySmall.inter(),
            labelLarge = b.labelLarge.inter(),
            labelMedium = b.labelMedium.inter(),
            labelSmall = b.labelSmall.inter(),
        )
    }
}

val LectorTypography: Typography get() = typography
