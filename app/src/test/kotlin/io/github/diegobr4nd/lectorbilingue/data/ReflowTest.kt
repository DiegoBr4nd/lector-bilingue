package io.github.diegobr4nd.lectorbilingue.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** [reflowLicense]: los textos de licencia vienen cortados a ~80 columnas; en pantalla se unen las líneas de un párrafo. */
class ReflowTest {
    @Test fun `une las lineas de un parrafo con un espacio`() {
        assertEquals(
            "Permission is hereby granted, free of charge, to any person obtaining a copy of this software.",
            reflowLicense("Permission is hereby granted, free of charge,\nto any person obtaining a copy\nof this software."),
        )
    }

    @Test fun `mantiene las lineas en blanco entre parrafos`() {
        assertEquals("Uno dos.\n\nTres cuatro.\n\n\nCinco.", reflowLicense("Uno\ndos.\n\nTres\ncuatro.\n\n\nCinco."))
    }

    @Test fun `convierte CRLF a LF`() {
        assertEquals("Uno dos.\n\nTres.", reflowLicense("Uno\r\ndos.\r\n\r\nTres."))
    }

    @Test fun `no une las vinetas ni las lineas con sangria`() {
        val text = "Conditions:\n- first item\n* second item\n• third item\n  indented line\n\tline with tab"
        assertEquals(text, reflowLicense(text))
    }

    @Test fun `no une las secciones numeradas ni con letras`() {
        val text = "Terms:\n1. Definitions.\n2) Grant.\n2.1. Grants\na) first\n(a) second\n(iv) fourth"
        assertEquals(text, reflowLicense(text))
    }

    @Test fun `la continuacion de una seccion numerada si se une`() {
        assertEquals("1. Definitions of the terms used in this License.", reflowLicense("1. Definitions of the terms\nused in this License."))
    }

    @Test fun `un titulo en mayusculas queda en su linea`() {
        assertEquals("See below.\nTERMS AND CONDITIONS\nFor use.", reflowLicense("See below.\nTERMS AND CONDITIONS\nFor use."))
    }

    @Test fun `un parrafo todo en mayusculas si se une`() {
        assertEquals(
            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED. SOFTWARE.",
            reflowLicense("THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\nIMPLIED.\nSOFTWARE."),
        )
    }

    @Test fun `cada linea de copyright queda en su linea`() {
        val text = "Copyright (c) 2005 Alguien\nCopyright (c) 2010 Otra persona\n© 2016 Tercera"
        assertEquals(text, reflowLicense(text))
    }

    @Test fun `las lineas de comentario de codigo quedan en su linea`() {
        val text = "// https://example.org/articulo\n// BSD license"
        assertEquals(text, reflowLicense(text))
    }

    @Test fun `quita los espacios del final al unir`() {
        assertEquals("Uno dos.", reflowLicense("Uno   \ndos."))
    }

    @Test fun `aplicarla dos veces da lo mismo`() {
        val text = "MIT License\n\nCopyright (c) 2016 Jorik Tangelder\n\nPermission is hereby granted,\nfree of charge.\n\n" +
            "1. Definitions\nof terms.\n- item\n  more\n\nTHE SOFTWARE IS\nPROVIDED.\n"
        val once = reflowLicense(text)
        assertEquals(once, reflowLicense(once))
    }
}
