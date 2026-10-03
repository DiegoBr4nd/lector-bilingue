package io.github.diegobr4nd.lectorbilingue.core.text

/**
 * Parte un párrafo en oraciones con reglas explícitas.
 * Es Kotlin puro a propósito: se comporta igual en la JVM (pruebas) y en Android.
 */
object SentenceSplitter {
    private val TERMINATORS = setOf('.', '!', '?', '…')
    private val CLOSERS = setOf('"', '\'', '”', '’', ')', ']', '»')
    private val OPENERS = setOf('"', '\'', '“', '‘', '(', '[', '«', '¿', '¡')
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "sr", "jr", "vs", "etc", "fig",
        "inc", "ltd", "co", "mt", "e.g", "i.e", "u.s", "u.k", "a.m", "p.m",
    )

    fun split(paragraph: String): List<String> {
        val text = paragraph.trim()
        if (text.isEmpty()) return emptyList()
        val sentences = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] !in TERMINATORS) {
                i++
                continue
            }
            val terminatorAt = i
            var end = i + 1
            while (end < text.length && text[end] in TERMINATORS) end++
            while (end < text.length && text[end] in CLOSERS) end++
            if (end < text.length &&
                text[end].isWhitespace() &&
                nextStartsSentence(text, end) &&
                !isAbbreviationOrInitial(text, terminatorAt, end)
            ) {
                sentences += text.substring(start, end).trim()
                start = end
            }
            i = end
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { sentences += it }
        return sentences
    }

    private fun nextStartsSentence(text: String, from: Int): Boolean {
        var j = from
        while (j < text.length && text[j].isWhitespace()) j++
        if (j >= text.length) return false
        val c = text[j]
        return c.isUpperCase() || c.isDigit() || c in OPENERS
    }

    /** Solo aplica a un punto simple: "Dr.", "e.g.", "J." (inicial). */
    private fun isAbbreviationOrInitial(text: String, dot: Int, end: Int): Boolean {
        if (text[dot] != '.' || end != dot + 1) return false
        var k = dot - 1
        while (k >= 0 && (text[k].isLetter() || text[k] == '.')) k--
        val word = text.substring(k + 1, dot).lowercase()
        if (word.length == 1 && word[0].isLetter()) return true
        return word in ABBREVIATIONS
    }
}
