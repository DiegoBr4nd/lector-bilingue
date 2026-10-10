package io.github.diegobr4nd.lectorbilingue.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class ReadingSettingsTest {
    private val custom = ReadingSettings(
        theme = PageTheme.SEPIA,
        fontScale = 1.3,
        font = ReadingFont.INTER,
        lineHeight = LineHeightLevel.WIDE,
        margins = MarginLevel.NARROW,
        align = TextAlignChoice.JUSTIFY,
    )

    @Test fun `por defecto son los de fabrica`() {
        assertEquals(ReadingSettings(), AppSettings(MemoryPrefs()).readingSettings)
        assertEquals(ReadingSettings(), AppSettings(MemoryPrefs()).readingSettingsFlow.value)
    }

    @Test fun `se guarda y se lee cada campo con su valor guardado`() {
        val prefs = MemoryPrefs()
        AppSettings(prefs).readingSettings = custom
        assertEquals("sepia", prefs.values["reading_theme"])
        assertEquals(1.3f, prefs.values["reading_scale"])
        assertEquals("inter", prefs.values["reading_font"])
        assertEquals("wide", prefs.values["reading_line_height"])
        assertEquals("narrow", prefs.values["reading_margins"])
        assertEquals("justify", prefs.values["reading_align"])
        assertEquals(custom, AppSettings(prefs).readingSettings)
    }

    @Test fun `escribir actualiza el flujo al instante`() {
        val s = AppSettings(MemoryPrefs())
        s.readingSettings = custom
        assertEquals(custom, s.readingSettingsFlow.value)
    }

    @Test fun ajustesCorruptosVuelvenAFabrica() {
        val prefs = MemoryPrefs()
        AppSettings(prefs).readingSettings = custom
        prefs.values["reading_theme"] = "ultra"
        prefs.values["reading_scale"] = 9.0f
        prefs.values["reading_font"] = 7 // tipo equivocado
        val read = AppSettings(prefs).readingSettings
        assertEquals(PageTheme.SYSTEM, read.theme)
        assertEquals(1.0, read.fontScale)
        assertEquals(ReadingFont.ORIGINAL, read.font)
        // Los demás campos siguen intactos.
        assertEquals(LineHeightLevel.WIDE, read.lineHeight)
        assertEquals(MarginLevel.NARROW, read.margins)
        assertEquals(TextAlignChoice.JUSTIFY, read.align)
    }

    @Test fun `una escala de tipo equivocado o no numerica vuelve a fabrica`() {
        val prefs = MemoryPrefs()
        prefs.values["reading_scale"] = "grande"
        assertEquals(1.0, AppSettings(prefs).readingSettings.fontScale)
        prefs.values["reading_scale"] = Float.NaN
        assertEquals(1.0, AppSettings(prefs).readingSettings.fontScale)
    }

    @Test fun `la escala se redondea a pasos de 0,1 y se acota al guardar`() {
        val s = AppSettings(MemoryPrefs())
        s.readingSettings = ReadingSettings(fontScale = 1.34)
        assertEquals(1.3, s.readingSettings.fontScale)
        s.readingSettings = ReadingSettings(fontScale = 9.0)
        assertEquals(2.5, s.readingSettings.fontScale)
        assertEquals(2.5, s.readingSettingsFlow.value.fontScale)
        s.readingSettings = ReadingSettings(fontScale = 0.1)
        assertEquals(0.75, s.readingSettings.fontScale)
    }

    @Test fun loadReadingSettings_publica_lo_guardado_y_no_pisa_una_escritura_nueva() = runTest {
        val prefs = MemoryPrefs()
        AppSettings(prefs).readingSettings = custom
        val fresh = AppSettings(prefs)
        assertTrue(fresh.readingSettingsFlow.value.isFactory)
        fresh.loadReadingSettings(io = Dispatchers.Unconfined)
        assertEquals(custom, fresh.readingSettingsFlow.value)
        fresh.readingSettings = ReadingSettings()
        fresh.loadReadingSettings(io = Dispatchers.Unconfined)
        assertTrue(fresh.readingSettingsFlow.value.isFactory)
    }

    @Test fun `normalizar una escala NaN da la de fabrica`() {
        assertEquals(1.0, ReadingSettings.normalizeScale(Double.NaN))
        val s = AppSettings(MemoryPrefs())
        s.readingSettings = ReadingSettings(fontScale = Double.NaN)
        assertEquals(1.0, s.readingSettings.fontScale)
        assertEquals(1.0, s.readingSettingsFlow.value.fontScale)
    }

    @Test fun `solo la fabrica es fabrica`() {
        assertTrue(ReadingSettings().isFactory)
        assertFalse(ReadingSettings(fontScale = 1.1).isFactory)
    }
}
