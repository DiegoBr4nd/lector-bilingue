package io.github.diegobr4nd.lectorbilingue.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals

class BenchTextTest {
    @Test fun `une lineas y separa por linea en blanco`() =
        assertEquals(listOf("One. Two.", "Three."), BenchText.parse("One.\nTwo.\n\nThree.\n"))
    @Test fun `ignora comentarios y blancos extra`() =
        assertEquals(listOf("A."), BenchText.parse("# c\n\n\n  A.  \n\n# d\n"))
    @Test fun `acepta saltos de linea de Windows`() =
        assertEquals(listOf("A. B.", "C."), BenchText.parse("A.\r\nB.\r\n\r\nC.\r\n"))
    @Test fun `ignora la marca BOM al inicio`() =
        assertEquals(listOf("A."), BenchText.parse("\uFEFF# c\nA."))
    @Test fun `cuenta palabras`() = assertEquals(4, BenchText.countWords("  It's a  fine day. "))
}
