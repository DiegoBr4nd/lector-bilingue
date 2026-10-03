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

    @Test
    fun `rechaza codigos que no son letras minusculas de 2 o 3`() {
        for (bad in listOf("../x", "EN", "e", "engl")) {
            assertFailsWith<IllegalArgumentException> { LanguagePair(bad, "es") }
            assertFailsWith<IllegalArgumentException> { LanguagePair("en", bad) }
        }
    }

    @Test
    fun `acepta codigos de 2 y 3 letras`() {
        LanguagePair("en", "es")
        LanguagePair("en", "spa")
    }
}
