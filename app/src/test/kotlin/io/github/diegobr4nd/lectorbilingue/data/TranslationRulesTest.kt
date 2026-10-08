package io.github.diegobr4nd.lectorbilingue.data

import io.github.diegobr4nd.lectorbilingue.core.text.SentenceSplitter
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import org.junit.Test
import kotlin.test.assertEquals
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
    @Test fun `tandas de oraciones de hasta 4000 caracteres sin partir oraciones`() {
        val s = "Una oración de prueba. ".repeat(400).trim()
        val b = TranslationRules.batches(s)
        assertTrue(b.size > 1); assertTrue(b.all { batch -> batch.sumOf { it.length } <= 4000 || batch.size == 1 })
        assertEquals(SentenceSplitter.split(s), b.flatten())
    }
    @Test fun `unir con un espacio`() = assertEquals("Hola. Adiós.", TranslationRules.join(listOf("Hola.", "Adiós.")))
}
