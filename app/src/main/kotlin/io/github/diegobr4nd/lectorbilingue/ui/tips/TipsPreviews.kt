package io.github.diegobr4nd.lectorbilingue.ui.tips

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.ui.howto.HowToScreen

/** Claro y oscuro, letra normal y al 200 %. */
@Preview(name = "Claro", widthDp = 360, showBackground = true)
@Preview(name = "Oscuro", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Claro letra 200", widthDp = 360, fontScale = 2f, showBackground = true)
@Preview(name = "Oscuro letra 200", widthDp = 360, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun TipCardPreview() {
    LectorTheme {
        TipCard(text = "Toca un párrafo para ver su traducción.", onDismiss = {})
    }
}

/** "Cómo se usa": claro, oscuro y letra al 200 %. */
@Preview(name = "Cómo se usa claro", widthDp = 360, heightDp = 720, showBackground = true)
@Preview(name = "Cómo se usa oscuro", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Cómo se usa letra 200", widthDp = 360, heightDp = 720, fontScale = 2f, showBackground = true)
@Composable
private fun HowToScreenPreview() {
    LectorTheme {
        HowToScreen(onBack = {}, onResetTips = {})
    }
}
