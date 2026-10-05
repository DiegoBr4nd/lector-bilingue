package io.github.diegobr4nd.lectorbilingue.core.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContrastTest {
    @Test fun blancoSobreNegroEs21() = assertEquals(21.0, Contrast.ratio(0xFFFFFFFF, 0xFF000000), 0.01)
    @Test fun mismoColorEs1() = assertEquals(1.0, Contrast.ratio(0xFF2E4A7D, 0xFF2E4A7D), 0.001)

    @Test fun temasDePaginaCumplenAA() {
        for (t in ReaderTheme.entries) {
            assertTrue(Contrast.ratio(t.text.argbLong(), t.background.argbLong()) >= 4.5, "${t.name} texto")
            assertTrue(Contrast.ratio(t.translation.argbLong(), t.background.argbLong()) >= 4.5, "${t.name} traducción")
        }
    }

    @Test fun esquemasDeInterfazCumplenAA() {
        for ((name, s) in listOf("claro" to TintaLight, "oscuro" to TintaDark)) {
            val pares = listOf(
                "onBackground" to (s.onBackground to s.background),
                "onSurface" to (s.onSurface to s.surface),
                "onSurfaceVariant" to (s.onSurfaceVariant to s.surface),
                "onPrimary" to (s.onPrimary to s.primary),
                "primary/fondo" to (s.primary to s.background),
                "onPrimaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                "onSecondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                "onError" to (s.onError to s.error),
                "error/fondo" to (s.error to s.background),
            )
            for ((label, p) in pares) {
                assertTrue(Contrast.ratio(p.first.argbLong(), p.second.argbLong()) >= 4.5, "$name $label")
            }
            // Bordes e íconos: elementos gráficos, mínimo 3:1.
            assertTrue(Contrast.ratio(s.outline.argbLong(), s.surface.argbLong()) >= 3.0, "$name outline")
        }
    }
}
