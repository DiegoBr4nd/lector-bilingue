package io.github.diegobr4nd.lectorbilingue.core.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SustitutosTest {
    private val paragraphs: List<List<String>> =
        File(System.getProperty("bench.dir"), "sustitutos.txt")
            .readText(Charsets.UTF_8)
            .lines()
            .filterNot { it.startsWith("#") }
            .joinToString("\n")
            .split(Regex("\n\\s*\n"))
            .map { block -> block.lines().map(String::trim).filter(String::isNotEmpty) }
            .filter { it.isNotEmpty() }

    @Test
    fun `hay 25 parrafos`() = assertEquals(25, paragraphs.size)

    @Test
    fun `cada parrafo se parte en sus oraciones esperadas`() {
        paragraphs.forEachIndexed { index, expected ->
            assertEquals(expected, SentenceSplitter.split(expected.joinToString(" ")), "párrafo $index")
        }
    }
}
