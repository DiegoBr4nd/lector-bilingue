package io.github.diegobr4nd.lectorbilingue.benchmark

/** Lee el formato de bench/sustitutos.txt y de private/textos.txt. */
object BenchText {
    private val WHITESPACE = Regex("\\s+")

    fun parse(content: String): List<String> {
        val paragraphs = mutableListOf<String>()
        val current = mutableListOf<String>()
        for (raw in content.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#") -> Unit
                line.isEmpty() -> if (current.isNotEmpty()) {
                    paragraphs += current.joinToString(" ")
                    current.clear()
                }
                else -> current += line
            }
        }
        if (current.isNotEmpty()) paragraphs += current.joinToString(" ")
        return paragraphs
    }

    fun countWords(text: String): Int = text.trim().split(WHITESPACE).count { it.isNotEmpty() }
}
