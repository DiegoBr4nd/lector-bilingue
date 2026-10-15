package io.github.diegobr4nd.lectorbilingue.ui.reader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Diseño T6: «227 MB» no se parte entre dos líneas: entre el número y «MB» va un espacio no separable (U+00A0). */
class MegabytesTextTest {
    @Test fun `el numero y MB van unidos en los textos con tamano de modelo`() {
        val xml = File("src/main/res/values/strings.xml").readText()
        for (name in listOf("reader_card_missing", "languages_cache_label", "languages_cache_delete_description")) {
            val line = xml.lines().first { "name=\"$name\"" in it }
            assertTrue(Regex("%[0-9][$][ds] MB").containsMatchIn(line), "$name: $line")
        }
        assertTrue(!Regex("%[0-9][$][ds] MB").containsMatchIn(xml.lines().first { "name=\"reader_card_missing\"" in it }))
    }
}
