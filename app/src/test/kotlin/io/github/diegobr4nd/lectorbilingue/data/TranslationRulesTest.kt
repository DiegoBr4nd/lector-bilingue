package io.github.diegobr4nd.lectorbilingue.data

import io.github.diegobr4nd.lectorbilingue.core.text.SentenceSplitter
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.opus.NativeBridge as OpusNativeBridge
import io.github.diegobr4nd.lectorbilingue.engine.firefox.NativeBridge as FirefoxNativeBridge
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TranslationRulesTest {
    private val enEs = LanguagePair("en", "es"); private val esEn = LanguagePair("es", "en")

    @Test fun `ingles traduce a espanol y espanol a ingles`() {
        assertEquals(enEs, TranslationRules.direction(listOf("en-US"), null))
        assertEquals(esEn, TranslationRules.direction(listOf("es"), null))
        assertEquals(esEn, TranslationRules.direction(listOf("ES-419"), null))
    }
    @Test fun `sin idioma u otro idioma es ingles a espanol`() {
        assertEquals(enEs, TranslationRules.direction(emptyList(), null))
        assertEquals(enEs, TranslationRules.direction(listOf("fr"), null))
    }
    @Test fun `la eleccion del libro manda y una invalida se ignora`() {
        assertEquals(esEn, TranslationRules.direction(listOf("en"), "es-en"))
        assertEquals(enEs, TranslationRules.direction(listOf("en"), "../x"))
    }
    @Test fun `normalizar colapsa espacios y recorta`() {
        assertEquals("Hola mundo.", TranslationRules.normalize("  Hola \n\t mundo.  "))
        assertEquals("a b", TranslationRules.normalize("a b"))
    }
    @Test fun `la huella cambia con el modelo el par o el texto y es hex de 64`() {
        val k = TranslationRules.cacheKey("opus:opus-en-es:1", enEs, "Hi.")
        assertTrue(Regex("^[0-9a-f]{64}$").matches(k))
        assertNotEquals(k, TranslationRules.cacheKey("opus:opus-en-es:2", enEs, "Hi."))
        assertNotEquals(k, TranslationRules.cacheKey("opus:opus-en-es:1", esEn, "Hi."))
        assertNotEquals(k, TranslationRules.cacheKey("opus:opus-en-es:1", enEs, "Hi!"))
        assertEquals(k, TranslationRules.cacheKey("opus:opus-en-es:1", enEs, "Hi."))
    }
    @Test fun `un parrafo normalizado de mas de 20000 caracteres es demasiado largo`() {
        assertEquals(20_000, TranslationRules.MAX_PARAGRAPH_CHARS)
        assertFalse(TranslationRules.tooLong("a".repeat(20_000)))
        assertTrue(TranslationRules.tooLong("a".repeat(20_001)))
    }
    @Test fun `tandas de oraciones de hasta 4000 caracteres sin partir oraciones`() {
        val s = "Una oración de prueba. ".repeat(400).trim()
        val b = TranslationRules.batches(s)
        assertTrue(b.size > 1); assertTrue(b.all { batch -> batch.sumOf { it.length } <= 4000 || batch.size == 1 })
        assertEquals(SentenceSplitter.split(s), b.flatten())
    }
    @Test fun `una oracion enorme se parte antes del tope del motor`() {
        // Caso 1: oración muy larga con comas (debe partir por coma)
        val withCommas = "palabra, ".repeat(200).trimEnd().dropLastWhile { it != ',' } + " fin."
        val batchesCommas = TranslationRules.batches(withCommas)
        val piecesCommas = batchesCommas.flatten()
        assertTrue(piecesCommas.all { it.length <= 1000 }, "Todas las piezas deben ser ≤ 1000 caracteres (con comas)")
        assertEquals(TranslationRules.normalize(withCommas), TranslationRules.join(piecesCommas), "Piezas unidas deben reconstruir el texto normalizado (con comas)")

        // Caso 2: oración muy larga con solo espacios (debe partir por espacio)
        val withSpaces = "palabra ".repeat(300).trimEnd()
        val batchesSpaces = TranslationRules.batches(withSpaces)
        val piecesSpaces = batchesSpaces.flatten()
        assertTrue(piecesSpaces.all { it.length <= 1000 }, "Todas las piezas deben ser ≤ 1000 caracteres (con espacios)")
        assertEquals(TranslationRules.normalize(withSpaces), TranslationRules.join(piecesSpaces), "Piezas unidas deben reconstruir el texto normalizado (con espacios)")

        // Caso 3: oración enorme sin separadores (hard-cut, sin comas ni espacios)
        val noSeparators = "a".repeat(2000)
        val batchesHardCut = TranslationRules.batches(noSeparators)
        val piecesHardCut = batchesHardCut.flatten()
        assertTrue(piecesHardCut.all { it.length <= 1000 }, "Todas las piezas deben ser ≤ 1000 caracteres (hard-cut)")
        assertEquals(noSeparators, piecesHardCut.joinToString(""), "Piezas concatenadas deben reconstruir el texto original")
    }
    @Test fun `limites de TranslationRules coinciden con los motores`() {
        // Verifica que MAX_SENTENCE_CHARS sea consistente entre módulos.
        // OpusEngine.kt:50 y FirefoxEngine.kt:57 requieren que s.length <= MAX_SENTENCE_CHARS
        // Los valores están en OpusEngine/NativeBridge.kt:15 y FirefoxEngine/NativeBridge.kt:23
        assertEquals(OpusNativeBridge.Companion.MAX_SENTENCE_CHARS, FirefoxNativeBridge.Companion.MAX_SENTENCE_CHARS,
            "Opus y Firefox deben tener el mismo MAX_SENTENCE_CHARS")
        assertEquals(1000, OpusNativeBridge.Companion.MAX_SENTENCE_CHARS,
            "MAX_SENTENCE_CHARS en Opus debe ser 1000")
        assertEquals(1000, FirefoxNativeBridge.Companion.MAX_SENTENCE_CHARS,
            "MAX_SENTENCE_CHARS en Firefox debe ser 1000")
    }
    @Test fun `unir con un espacio`() = assertEquals("Hola. Adiós.", TranslationRules.join(listOf("Hola.", "Adiós.")))
}
