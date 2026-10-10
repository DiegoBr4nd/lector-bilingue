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

    // "Oscuro" es un gris muy oscuro: el DARK de Readium ya es negro puro (el tema "Negro").
    // Provisional; `diseno` fija la paleta final.
    private val DARK_BACKGROUND = Color(0xFF1E1E1E.toInt())
    private val DARK_TEXT = Color(0xFFE0E0E0.toInt())

    // Nombres exactos con que la Tarea 3 declara las familias en Readium.
    private val FAMILIES = mapOf(
        ReadingFont.LITERATA to FontFamily("Literata"),
        ReadingFont.INTER to FontFamily("Inter"),
        ReadingFont.ATKINSON to FontFamily("Atkinson Hyperlegible"),
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
            backgroundColor = if (ownColors) DARK_BACKGROUND else null,
            textColor = if (ownColors) DARK_TEXT else null,
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

    /** Un paso de tamaño (± 0,1), acotado a 0,75..2,5. */
    fun step(scale: Double, up: Boolean): Double = ReadingSettings.stepScale(scale, up)

    /** El tamaño como porcentaje para mostrar ("110"). */
    fun percent(scale: Double): Int = Math.round(scale * 100).toInt()
}
