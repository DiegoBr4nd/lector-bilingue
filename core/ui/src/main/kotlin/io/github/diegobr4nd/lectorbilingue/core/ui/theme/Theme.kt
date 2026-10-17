package io.github.diegobr4nd.lectorbilingue.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Tema de la app: Tinta y papel, claro u oscuro según el sistema. Sin color dinámico. */
@Composable
fun LectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    LectorFonts.install(LocalContext.current.assets) // antes de leer LectorTypography
    MaterialTheme(
        colorScheme = if (darkTheme) TintaDark else TintaLight,
        typography = LectorTypography,
        shapes = LectorShapes,
        content = content,
    )
}
