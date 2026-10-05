package io.github.diegobr4nd.lectorbilingue.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.diegobr4nd.lectorbilingue.core.ui.R

/** Interfaz: Inter variable (un solo archivo; el peso se escoge al declararla). */
@OptIn(ExperimentalTextApi::class)
val InterFamily = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(
            R.font.inter_variable,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)

/** Lectura y títulos grandes: Literata. */
val ReadingFontFamily = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal),
    Font(R.font.literata_semibold, FontWeight.SemiBold),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
)

private fun TextStyle.inter() = copy(fontFamily = InterFamily)

val LectorTypography: Typography = Typography().let { b ->
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
