package io.github.diegobr4nd.lectorbilingue.engine.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LanguagePairTest {
    @Test
    fun `acepta un par valido`() {
        val pair = LanguagePair("en", "es")
        assertEquals("en", pair.source)
        assertEquals("es", pair.target)
    }

    @Test
    fun `rechaza origen vacio`() {
        assertFailsWith<IllegalArgumentException> { LanguagePair(" ", "es") }
    }

    @Test
    fun `rechaza destino vacio`() {
        assertFailsWith<IllegalArgumentException> { LanguagePair("en", "") }
    }

    @Test
    fun `rechaza origen igual a destino`() {
        assertFailsWith<IllegalArgumentException> { LanguagePair("en", "en") }
    }
}
