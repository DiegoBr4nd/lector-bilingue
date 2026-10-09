package io.github.diegobr4nd.lectorbilingue.data

import io.github.diegobr4nd.lectorbilingue.core.text.SentenceSplitter
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import java.security.MessageDigest

/** Reglas puras de la traducción (sin Android): dirección, huella del caché y tandas de oraciones. */
object TranslationRules {
    private val EN_ES = LanguagePair("en", "es")
    private val ES_EN = LanguagePair("es", "en")
    private val SPACES = Regex("[\\s\\u00A0]+")
    // Máximo de caracteres por oración en los motores (OpusEngine.kt y FirefoxEngine.kt)
    private const val MAX_SENTENCE_CHARS = 1000

    /**
     * Tope de un párrafo (ya normalizado). Uno más largo ocuparía el motor minutos (y el toque siguiente esperaría
     * detrás): se responde enseguida "demasiado largo", sin traducirlo.
     */
    const val MAX_PARAGRAPH_CHARS = 20_000

    /** [override] (columna `books.direction`) manda si es un par válido; si no, el primer idioma del libro. */
    fun direction(bookLanguages: List<String>, override: String?): LanguagePair {
        parseWire(override)?.let { return it }
        return when (bookLanguages.firstOrNull()?.lowercase()?.substringBefore('-')) {
            "es" -> ES_EN
            else -> EN_ES
        }
    }

    fun wire(pair: LanguagePair): String = "${pair.source}-${pair.target}"

    fun parseWire(s: String?): LanguagePair? {
        val parts = s?.split('-') ?: return null
        if (parts.size != 2) return null
        return runCatching { LanguagePair(parts[0], parts[1]) }.getOrNull()
    }

    fun normalize(text: String): String = text.replace(SPACES, " ").trim()

    /** true si [normalizedText] pasa de [MAX_PARAGRAPH_CHARS]. */
    fun tooLong(normalizedText: String): Boolean = normalizedText.length > MAX_PARAGRAPH_CHARS

    fun cacheKey(modelTag: String, pair: LanguagePair, normalizedText: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$modelTag\n${wire(pair)}\n$normalizedText".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun batches(normalizedText: String, maxChars: Int = 4000): List<List<String>> {
        val sentences = SentenceSplitter.split(normalizedText).flatMap { splitLongSentence(it) }
        val out = mutableListOf<MutableList<String>>()
        var size = 0
        for (s in sentences) {
            if (out.isEmpty() || size + s.length > maxChars) {
                out += mutableListOf<String>()
                size = 0
            }
            out.last() += s
            size += s.length
        }
        return out
    }

    fun join(translatedSentences: List<String>): String = translatedSentences.joinToString(" ") { it.trim() }.trim()

    /**
     * Parte una oración muy larga en fragmentos de hasta [MAX_SENTENCE_CHARS] caracteres.
     * Intenta partir por la última coma o espacio antes del tope.
     */
    private fun splitLongSentence(sentence: String): List<String> {
        if (sentence.length <= MAX_SENTENCE_CHARS) {
            return listOf(sentence)
        }
        val result = mutableListOf<String>()
        var remaining = sentence
        while (remaining.length > MAX_SENTENCE_CHARS) {
            val chunk = remaining.substring(0, MAX_SENTENCE_CHARS)
            val lastCommaIdx = chunk.lastIndexOf(',')
            val lastSpaceIdx = chunk.lastIndexOf(' ')
            val splitIdx = when {
                lastCommaIdx > 0 -> lastCommaIdx + 1
                lastSpaceIdx > 0 -> lastSpaceIdx + 1
                else -> MAX_SENTENCE_CHARS
            }
            result += remaining.substring(0, splitIdx).trim()
            remaining = remaining.substring(splitIdx).trim()
        }
        if (remaining.isNotEmpty()) {
            result += remaining
        }
        return result
    }
}
