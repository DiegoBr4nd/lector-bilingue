package io.github.diegobr4nd.lectorbilingue.ui.reader

import io.github.diegobr4nd.lectorbilingue.data.LineHeightLevel
import io.github.diegobr4nd.lectorbilingue.data.MarginLevel
import io.github.diegobr4nd.lectorbilingue.data.PageTheme
import io.github.diegobr4nd.lectorbilingue.data.ReadingFont
import io.github.diegobr4nd.lectorbilingue.data.ReadingSettings
import io.github.diegobr4nd.lectorbilingue.data.TextAlignChoice
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Traduce los ajustes de la persona a preferencias de Readium. Sin lógica de Android propia: se prueba en la JVM
 * (los tipos de Readium cargan `android.graphics.Color`, ver `isReturnDefaultValues` en build.gradle.kts).
 * Ojo: `Color`, `TextAlign` y `Theme` son los de Readium, no los de Compose.
 */
@OptIn(ExperimentalReadiumApi::class) // EpubPreferences usa API experimental de Readium
object ReadingRules {
    /** Interlineado por nivel (1,0 a 2,0 en Readium). */
    val LINE_HEIGHT = mapOf(
        LineHeightLevel.COMPACT to 1.3,
        LineHeightLevel.NORMAL to 1.5,
        LineHeightLevel.WIDE to 1.8,
    )

    /** Márgenes por nivel: multiplican 20 px a cada lado (0,0 a 4,0 en Readium). */
    val MARGINS = mapOf(
        MarginLevel.NARROW to 0.5,
        MarginLevel.NORMAL to 1.0,
        MarginLevel.WIDE to 1.6,
    )

    // Paletas finales (boceto 4a §3.1). "Oscuro" es un gris muy oscuro (12,63:1). "Negro" es negro puro con texto
    // #E6E6E6 (16,83:1) en vez del blanco casi puro de Readium: deslumbra menos en pantallas OLED.
    private val DARK_BACKGROUND = Color(0xFF1E1E1E.toInt())
    private val DARK_TEXT = Color(0xFFE0E0E0.toInt())
    private val BLACK_BACKGROUND = Color(0xFF000000.toInt())
    private val BLACK_TEXT = Color(0xFFE6E6E6.toInt())

    /** Familias propias: ReaderActivity las declara en Readium con estos mismos nombres. */
    val LITERATA = FontFamily("Literata")
    val INTER = FontFamily("Inter")
    val ATKINSON = FontFamily("Atkinson Hyperlegible")

    private val FAMILIES = mapOf(
        ReadingFont.LITERATA to LITERATA,
        ReadingFont.INTER to INTER,
        ReadingFont.ATKINSON to ATKINSON,
    )

    fun preferences(s: ReadingSettings, systemDark: Boolean): EpubPreferences {
        val justify = s.align == TextAlignChoice.JUSTIFY
        // "Como el teléfono" en modo oscuro es exactamente "Oscuro", para que página y tarjeta coincidan.
        val ownColors = s.theme == PageTheme.DARK || (s.theme == PageTheme.SYSTEM && systemDark)
        return EpubPreferences(
            scroll = true,
            theme = when (s.theme) {
                PageTheme.SYSTEM -> if (systemDark) Theme.DARK else Theme.LIGHT
                PageTheme.LIGHT -> Theme.LIGHT
                PageTheme.SEPIA -> Theme.SEPIA
                PageTheme.DARK, PageTheme.BLACK -> Theme.DARK
            },
            backgroundColor = when {
                ownColors -> DARK_BACKGROUND
                s.theme == PageTheme.BLACK -> BLACK_BACKGROUND
                else -> null
            },
            textColor = when {
                ownColors -> DARK_TEXT
                s.theme == PageTheme.BLACK -> BLACK_TEXT
                else -> null
            },
            fontFamily = FAMILIES[s.font], // ORIGINAL no está: null = la del libro
            fontSize = ReadingSettings.normalizeScale(s.fontScale),
            lineHeight = LINE_HEIGHT.getValue(s.lineHeight),
            pageMargins = MARGINS.getValue(s.margins),
            textAlign = if (justify) TextAlign.JUSTIFY else null,
            hyphens = if (justify) true else null,
            // Interlineado, alineación y guiones solo se aplican con esto en false; en fábrica se respeta el libro.
            publisherStyles = s.isFactory,
        )
    }

    /** Tema de la tarjeta de traducción: "claro" | "sepia" | "oscuro" | "negro". */
    fun cardTheme(s: ReadingSettings, systemDark: Boolean): String = when (s.theme) {
        PageTheme.SYSTEM -> if (systemDark) "oscuro" else "claro"
        PageTheme.LIGHT -> "claro"
        PageTheme.SEPIA -> "sepia"
        PageTheme.DARK -> "oscuro"
        PageTheme.BLACK -> "negro"
    }

    /**
     * Tras mandar ajustes a Readium, ¿se vuelve a la posición guardada antes ([savedHref])? No mientras un paso de
     * capítulo nuestro hacia [turningTo] está en curso (pedido pero la posición aún es del capítulo de antes): volver
     * desharía el paso. La posición y el capítulo visible salen del mismo `currentLocator`, así que solo el paso pedido
     * dice si se está cambiando. Tras [ReaderRules.TURN_TIMEOUT_MS] el paso que nunca llegó ya no cuenta.
     */
    fun restoreAfterSubmit(savedHref: String, turningTo: String?, msSinceTurn: Long): Boolean = when {
        turningTo == null -> true
        msSinceTurn >= ReaderRules.TURN_TIMEOUT_MS -> true
        else -> savedHref.substringBefore('#') == turningTo.substringBefore('#')
    }

    /** Un paso de tamaño (± 0,1), acotado a 0,75..2,5. */
    fun step(scale: Double, up: Boolean): Double = ReadingSettings.stepScale(scale, up)

    /** El tamaño como porcentaje para mostrar ("110"). */
    fun percent(scale: Double): Int = Math.round(scale * 100).toInt()
}
