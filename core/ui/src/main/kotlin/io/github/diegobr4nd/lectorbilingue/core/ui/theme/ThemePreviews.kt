package io.github.diegobr4nd.lectorbilingue.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp

@Composable
private fun MuestraPagina(tema: ReaderTheme) {
    Column(Modifier.background(tema.background).padding(Spacing.l)) {
        Text(
            "La lluvia golpeaba los cristales mientras ella repasaba, una vez más, la carta del abuelo.",
            color = tema.text,
            fontFamily = ReadingFontFamily,
            fontSize = 18.sp,
            lineHeight = 28.sp,
        )
        Text(
            "The rain tapped on the windowpanes as she read her grandfather's letter once more.",
            color = tema.translation,
            fontFamily = ReadingFontFamily,
            fontStyle = FontStyle.Italic,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
}

@Preview(name = "Página clara", showBackground = true)
@Composable
private fun PaginaClara() = MuestraPagina(ReaderTheme.LIGHT)

@Preview(name = "Página sepia", showBackground = true)
@Composable
private fun PaginaSepia() = MuestraPagina(ReaderTheme.SEPIA)

@Preview(name = "Página oscura", showBackground = true)
@Composable
private fun PaginaOscura() = MuestraPagina(ReaderTheme.DARK)

@Preview(name = "Página negra", showBackground = true)
@Composable
private fun PaginaNegra() = MuestraPagina(ReaderTheme.BLACK)
