package io.github.diegobr4nd.lectorbilingue.ui.reader

import io.github.diegobr4nd.lectorbilingue.data.LineHeightLevel
import io.github.diegobr4nd.lectorbilingue.data.MarginLevel
import io.github.diegobr4nd.lectorbilingue.data.PageTheme
import io.github.diegobr4nd.lectorbilingue.data.ReadingFont
import io.github.diegobr4nd.lectorbilingue.data.ReadingSettings
import io.github.diegobr4nd.lectorbilingue.data.TextAlignChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

class ReadingRulesTest {
    private val f = ReadingSettings()

    @Test fun `fabrica respeta el libro`() {
        val p = ReadingRules.preferences(f, systemDark = false)
        assertEquals(true, p.scroll); assertEquals(true, p.publisherStyles); assertNull(p.fontFamily)
        assertEquals(Theme.LIGHT, p.theme)
        assertNull(p.backgroundColor); assertNull(p.textColor)
        assertNull(p.textAlign); assertNull(p.hyphens)
    }

    @Test fun `tema del sistema sigue al modo oscuro`() {
        assertEquals(Theme.DARK, ReadingRules.preferences(f, systemDark = true).theme)
        assertEquals("oscuro", ReadingRules.cardTheme(f, systemDark = true))
        assertEquals("claro", ReadingRules.cardTheme(f, systemDark = false))
    }

    @Test fun `negro es el oscuro de Readium con fondo negro puro y texto E6E6E6`() {
        val b = f.copy(theme = PageTheme.BLACK)
        val p = ReadingRules.preferences(b, systemDark = false)
        assertEquals(Theme.DARK, p.theme)
        assertEquals(0xFF000000.toInt(), p.backgroundColor!!.int)
        // Menos brillo que el blanco casi puro de Readium sobre negro (boceto 4a §3.1): 16,83:1.
        assertEquals(0xFFE6E6E6.toInt(), p.textColor!!.int)
        assertEquals("negro", ReadingRules.cardTheme(b, systemDark = false))
    }

    @Test fun `oscuro lleva su propio fondo y texto`() {
        val d = f.copy(theme = PageTheme.DARK)
        val p = ReadingRules.preferences(d, systemDark = false)
        assertEquals(Theme.DARK, p.theme)
        assertEquals(0xFF1E1E1E.toInt(), p.backgroundColor!!.int)
        assertEquals(0xFFE0E0E0.toInt(), p.textColor!!.int)
        assertEquals("oscuro", ReadingRules.cardTheme(d, systemDark = false))
    }

    @Test fun `el tema del sistema con modo oscuro es igual a Oscuro`() {
        val sys = ReadingRules.preferences(f, systemDark = true)
        val dark = ReadingRules.preferences(f.copy(theme = PageTheme.DARK), systemDark = true)
        assertEquals(Theme.DARK, sys.theme)
        assertEquals(0xFF1E1E1E.toInt(), sys.backgroundColor!!.int)
        assertEquals(0xFFE0E0E0.toInt(), sys.textColor!!.int)
        assertEquals(dark.backgroundColor, sys.backgroundColor)
        assertEquals(dark.textColor, sys.textColor)
        assertEquals("oscuro", ReadingRules.cardTheme(f, systemDark = true))
    }

    @Test fun `el tema del sistema con modo claro es Claro sin colores propios`() {
        val p = ReadingRules.preferences(f, systemDark = false)
        assertEquals(Theme.LIGHT, p.theme)
        assertNull(p.backgroundColor); assertNull(p.textColor)
    }

    @Test fun `cambiar solo el tema tambien sobrescribe el estilo del libro`() {
        // Intencional (spec 5.1): cualquier ajuste distinto de fabrica pone publisherStyles = false.
        assertEquals(false, ReadingRules.preferences(f.copy(theme = PageTheme.SEPIA), false).publisherStyles)
    }

    @Test fun `sepia y claro elegidos ignoran el sistema`() {
        assertEquals(Theme.SEPIA, ReadingRules.preferences(f.copy(theme = PageTheme.SEPIA), true).theme)
        assertEquals(Theme.LIGHT, ReadingRules.preferences(f.copy(theme = PageTheme.LIGHT), true).theme)
        assertEquals("sepia", ReadingRules.cardTheme(f.copy(theme = PageTheme.SEPIA), true))
        assertEquals("claro", ReadingRules.cardTheme(f.copy(theme = PageTheme.LIGHT), true))
    }

    @Test fun `cualquier ajuste distinto de fabrica sobrescribe el estilo del libro`() {
        assertEquals(false, ReadingRules.preferences(f.copy(fontScale = 1.2), false).publisherStyles)
        assertEquals(false, ReadingRules.preferences(f.copy(font = ReadingFont.ATKINSON), false).publisherStyles)
        assertEquals(false, ReadingRules.preferences(f.copy(lineHeight = LineHeightLevel.COMPACT), false).publisherStyles)
        assertEquals(false, ReadingRules.preferences(f.copy(margins = MarginLevel.WIDE), false).publisherStyles)
        assertEquals(false, ReadingRules.preferences(f.copy(align = TextAlignChoice.JUSTIFY), false).publisherStyles)
    }

    @Test fun `el tamaño se acota y va en pasos de 10`() {
        assertEquals(2.5, ReadingRules.step(2.5, up = true)); assertEquals(0.75, ReadingRules.step(0.8, up = false))
        assertEquals(1.1, ReadingRules.step(1.0, up = true)); assertEquals(110, ReadingRules.percent(1.1))
        assertEquals(1.0, ReadingRules.step(1.1, up = false))
    }

    @Test fun `el tamaño de las preferencias va acotado`() {
        assertEquals(2.5, ReadingRules.preferences(f.copy(fontScale = 9.0), false).fontSize)
        assertEquals(0.75, ReadingRules.preferences(f.copy(fontScale = 0.1), false).fontSize)
    }

    @Test fun `justificado lleva guiones`() {
        val p = ReadingRules.preferences(f.copy(align = TextAlignChoice.JUSTIFY), false)
        assertEquals(TextAlign.JUSTIFY, p.textAlign); assertEquals(true, p.hyphens)
    }

    @Test fun `interlineado y margenes por nivel`() {
        assertEquals(1.8, ReadingRules.preferences(f.copy(lineHeight = LineHeightLevel.WIDE), false).lineHeight)
        assertEquals(0.5, ReadingRules.preferences(f.copy(margins = MarginLevel.NARROW), false).pageMargins)
    }

    @Test fun `cada fuente usa su familia declarada`() {
        assertEquals(FontFamily("Literata"), ReadingRules.preferences(f.copy(font = ReadingFont.LITERATA), false).fontFamily)
        assertEquals(FontFamily("Inter"), ReadingRules.preferences(f.copy(font = ReadingFont.INTER), false).fontFamily)
        assertEquals(
            FontFamily("Atkinson Hyperlegible"),
            ReadingRules.preferences(f.copy(font = ReadingFont.ATKINSON), false).fontFamily,
        )
    }

    @Test fun `restablecer vuelve a fabrica`() = assertTrue(ReadingSettings().isFactory)

    @Test fun `las familias declaradas son las mismas que usan las preferencias`() {
        assertEquals(FontFamily("Literata"), ReadingRules.LITERATA)
        assertEquals(FontFamily("Inter"), ReadingRules.INTER)
        assertEquals(FontFamily("Atkinson Hyperlegible"), ReadingRules.ATKINSON)
    }

    @Test fun `tras un ajuste se vuelve a la posicion salvo con un paso de capitulo en curso`() {
        // Sin paso de capítulo nuestro: siempre se vuelve.
        assertTrue(ReadingRules.restoreAfterSubmit("OEBPS/c2.xhtml", turningTo = null, msSinceTurn = 0))
        // Paso a c3 pedido y la posición guardada aún es de c2: volver desharía el paso.
        assertEquals(false, ReadingRules.restoreAfterSubmit("OEBPS/c2.xhtml", turningTo = "OEBPS/c3.xhtml", msSinceTurn = 100))
        // El paso ya llegó (la posición guardada es del capítulo nuevo, con o sin #fragmento): se vuelve.
        assertTrue(ReadingRules.restoreAfterSubmit("OEBPS/c3.xhtml#p4", turningTo = "OEBPS/c3.xhtml", msSinceTurn = 100))
        // El paso nunca llegó y pasó el tope: ya no cuenta como en curso.
        assertTrue(ReadingRules.restoreAfterSubmit("OEBPS/c2.xhtml", turningTo = "OEBPS/c3.xhtml", msSinceTurn = 5_000))
    }
}
