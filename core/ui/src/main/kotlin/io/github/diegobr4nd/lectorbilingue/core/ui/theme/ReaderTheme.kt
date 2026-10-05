package io.github.diegobr4nd.lectorbilingue.core.ui.theme

import androidx.compose.ui.graphics.Color

/** Temas de la página del lector: fondo, texto y color de la traducción. Todos cumplen contraste AA. */
enum class ReaderTheme(val background: Color, val text: Color, val translation: Color) {
    LIGHT(Color(0xFFFBF8F1), Color(0xFF1C1B1F), Color(0xFF2E4A7D)),
    SEPIA(Color(0xFFF4ECD8), Color(0xFF3B2F1E), Color(0xFF6B4A1F)),
    DARK(Color(0xFF121418), Color(0xFFE6E3DD), Color(0xFFA9C1F0)),
    BLACK(Color(0xFF000000), Color(0xFFD9D9D9), Color(0xFF9DB4E0)),
}
