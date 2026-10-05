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
                "onSecondary" to (s.onSecondary to s.secondary),
                "onTertiary" to (s.onTertiary to s.tertiary),
                "onTertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
                "onErrorContainer" to (s.onErrorContainer to s.errorContainer),
                "inverseOnSurface" to (s.inverseOnSurface to s.inverseSurface),
                "inversePrimary" to (s.inversePrimary to s.inverseSurface),
                "secondary/fondo" to (s.secondary to s.background),
                "tertiary/fondo" to (s.tertiary to s.background),
                "error/surface" to (s.error to s.surface),
                "primary sobre surfaceContainer" to (s.primary to s.surfaceContainer),
                "error sobre surfaceContainer" to (s.error to s.surfaceContainer),
            ) + superficies(s).flatMap { (n, c) ->
                listOf("onSurface sobre $n" to (s.onSurface to c), "onSurfaceVariant sobre $n" to (s.onSurfaceVariant to c))
            }
            for ((label, p) in pares) {
                assertTrue(Contrast.ratio(p.first.argbLong(), p.second.argbLong()) >= 4.5, "$name $label")
            }
            // Bordes e íconos: elementos gráficos, mínimo 3:1.
            for ((n, c) in superficies(s) + listOf("background" to s.background, "surface" to s.surface)) {
                assertTrue(Contrast.ratio(s.outline.argbLong(), c.argbLong()) >= 3.0, "$name outline sobre $n")
            }
        }
    }

    private fun superficies(s: androidx.compose.material3.ColorScheme) = listOf(
        "surfaceVariant" to s.surfaceVariant,
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
    )
}
