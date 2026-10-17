package io.github.diegobr4nd.lectorbilingue.ui.about

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.data.LicenseEntry
import io.github.diegobr4nd.lectorbilingue.data.LicenseGroup

private val sampleInfo = DeviceInfo("0.1.0", "play", "14", "Google", "Pixel 7")

private val sampleEntries = listOf(
    LicenseEntry("lector-bilingue", "Lector bilingüe", "GPL-3.0-or-later", LicenseGroup.APP, emptyList(), null),
    LicenseEntry("muestra:libreria", "Librería de muestra", "Apache-2.0", LicenseGroup.LIBRARIES, emptyList(), null),
    LicenseEntry("muestra-nativo", "Motor de muestra", "MIT", LicenseGroup.NATIVE, emptyList(), null),
    LicenseEntry("muestra-fuente", "Fuente de muestra", "OFL-1.1", LicenseGroup.FONTS_ICONS, emptyList(), null),
)

/** «Acerca de»: claro, oscuro y letra al 200 %. */
@Preview(name = "Acerca de claro", widthDp = 360, heightDp = 720, showBackground = true)
@Preview(name = "Acerca de oscuro", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Acerca de letra 200", widthDp = 360, heightDp = 720, fontScale = 2f, showBackground = true)
@Composable
private fun AboutScreenPreview() {
    LectorTheme {
        AboutScreen(onBack = {}, onLicenses = {}, info = sampleInfo, startMail = { true })
    }
}

/** «Licencias» con cuatro filas de muestra (una por grupo): claro, oscuro y letra al 200 %. */
@Preview(name = "Licencias claro", widthDp = 360, heightDp = 720, showBackground = true)
@Preview(name = "Licencias oscuro", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Licencias letra 200", widthDp = 360, heightDp = 720, fontScale = 2f, showBackground = true)
@Composable
private fun LicensesContentPreview() {
    LectorTheme {
        LicensesContent(LicensesState.Ready(sampleEntries), {}, {})
    }
}
