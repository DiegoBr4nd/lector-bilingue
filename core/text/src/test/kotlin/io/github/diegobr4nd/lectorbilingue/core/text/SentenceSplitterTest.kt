package io.github.diegobr4nd.lectorbilingue.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

class SentenceSplitterTest {
    private fun split(text: String) = SentenceSplitter.split(text)

    @Test fun `texto vacio devuelve lista vacia`() = assertEquals(emptyList(), split(""))
    @Test fun `solo espacios devuelve lista vacia`() = assertEquals(emptyList(), split("  \n\t "))
    @Test fun `una oracion sin punto final`() = assertEquals(listOf("Hello world"), split("Hello world"))
    @Test fun `dos oraciones simples`() =
        assertEquals(listOf("It rained.", "We stayed home."), split("It rained. We stayed home."))
    @Test fun `pregunta y exclamacion`() =
        assertEquals(listOf("Are you sure?", "Yes!", "Go."), split("Are you sure? Yes! Go."))
    @Test fun `signos combinados`() =
        assertEquals(listOf("You did what?!", "Unbelievable."), split("You did what?! Unbelievable."))
    @Test fun `tratamientos no cortan`() =
        assertEquals(listOf("Mr. Smith met Dr. Jones.", "They talked."), split("Mr. Smith met Dr. Jones. They talked."))
    @Test fun `eg e ie no cortan`() =
        assertEquals(listOf("Use a tool, e.g. Gradle, i.e. a builder."), split("Use a tool, e.g. Gradle, i.e. a builder."))
    @Test fun `decimales no cortan`() =
        assertEquals(listOf("The rate rose 3.5 percent.", "Prices fell."), split("The rate rose 3.5 percent. Prices fell."))
    @Test fun `versiones no cortan`() =
        assertEquals(listOf("Install v1.2.3 now."), split("Install v1.2.3 now."))
    @Test fun `iniciales no cortan`() =
        assertEquals(listOf("J. R. R. Tolkien wrote it.", "It sold well."), split("J. R. R. Tolkien wrote it. It sold well."))
    @Test fun `am y pm no cortan`() =
        assertEquals(listOf("We met at 9 a.m. today."), split("We met at 9 a.m. today."))
    @Test fun `US no corta antes de minuscula`() =
        assertEquals(listOf("She moved to the U.S. last year."), split("She moved to the U.S. last year."))
    @Test fun `puntos suspensivos con minuscula siguen`() =
        assertEquals(listOf("I thought... maybe not."), split("I thought... maybe not."))
    @Test fun `puntos suspensivos con mayuscula cortan`() =
        assertEquals(listOf("Wait...", "Who is there?"), split("Wait... Who is there?"))
    @Test fun `elipsis unicode`() =
        assertEquals(listOf("Well…", "Fine."), split("Well… Fine."))
    @Test fun `dialogo con comillas`() =
        assertEquals(
            listOf("\"You're late,\" Marta said.", "\"I know.\"", "\"That's it?\""),
            split("\"You're late,\" Marta said. \"I know.\" \"That's it?\""),
        )
    @Test fun `comillas tipograficas`() =
        assertEquals(listOf("“Stop.”", "“Why?”"), split("“Stop.” “Why?”"))
    @Test fun `parentesis que cierra queda con su oracion`() =
        assertEquals(listOf("See the appendix (page 4.)", "Then continue."), split("See the appendix (page 4.) Then continue."))
    @Test fun `recorta espacios y saltos de linea`() =
        assertEquals(listOf("One.", "Two."), split("  One.\n\n  Two.  "))
    @Test fun `oracion que empieza con numero`() =
        assertEquals(listOf("It ended.", "2025 was hard."), split("It ended. 2025 was hard."))
}
