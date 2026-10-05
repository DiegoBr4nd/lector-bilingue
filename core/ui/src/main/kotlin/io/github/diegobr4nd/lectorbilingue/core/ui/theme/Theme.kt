package io.github.diegobr4nd.lectorbilingue.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Tema de la app: Tinta y papel, claro u oscuro según el sistema. Sin color dinámico. */
@Composable
fun LectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) TintaDark else TintaLight,
        typography = LectorTypography,
        shapes = LectorShapes,
        content = content,
    )
}
