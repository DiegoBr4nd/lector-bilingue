package io.github.diegobr4nd.lectorbilingue

import android.content.Intent
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.data.Licenses
import io.github.diegobr4nd.lectorbilingue.ui.about.AboutScreen
import io.github.diegobr4nd.lectorbilingue.ui.about.DeviceInfo
import io.github.diegobr4nd.lectorbilingue.ui.about.FEEDBACK_EMAIL
import io.github.diegobr4nd.lectorbilingue.ui.about.LicenseTextScreen
import io.github.diegobr4nd.lectorbilingue.ui.about.LicensesScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** «Acerca de» y «Licencias» con el catálogo real del APK. No toca datos de Juan (sin ajustes, sin libros). */
@RunWith(AndroidJUnit4::class)
class AboutOnDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val info = DeviceInfo("0.1.0", "fdroid", "14", "Google", "Pixel 7")

    @Test fun elCorreoSaleConElMensajePreparado() {
        val sent = mutableListOf<Intent>()
        compose.setContent { LectorTheme { AboutScreen(onBack = {}, onLicenses = {}, info = info, startMail = { sent += it; true }) } }
        compose.onNodeWithText("Enviar comentarios").assertHeightIsAtLeast(48.dp).performClick()
        val intent = sent.single()
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data?.scheme)
        assertEquals("Lector bilingüe · comentarios (0.1.0)", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT)!!.endsWith("Teléfono: Google Pixel 7"))
        compose.onNodeWithText(FEEDBACK_EMAIL, substring = true).assertDoesNotExist()
    }

    @Test fun sinAppDeCorreoLaDireccionQuedaEscrita() {
        compose.setContent { LectorTheme { AboutScreen(onBack = {}, onLicenses = {}, info = info, startMail = { false }) } }
        compose.onNodeWithText("Enviar comentarios").performClick()
        compose.onNodeWithText(FEEDBACK_EMAIL, substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun atribucionDeLosModelosVisible() {
        compose.setContent { LectorTheme { AboutScreen(onBack = {}, onLicenses = {}, info = info, startMail = { true }) } }
        compose.onNodeWithText("CC-BY-4.0", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("MPL-2.0", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("Abrir Modelo inglés", substring = true)).performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Versión 0.1.0").assertIsDisplayed()
    }

    @Test fun elCatalogoDelApkCargaYEmpiezaPorLaApp() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val entries = assertNotNull(Licenses.load(assets))
        assertEquals("lector-bilingue", entries.first().id)
        for (e in entries) assertNotNull(Licenses.readTexts(assets, e), e.id) // cada texto existe en el APK
        compose.setContent { LectorTheme { LicensesScreen(onBack = {}, onOpen = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("GPL-3.0-or-later")).fetchSemanticsNodes().isNotEmpty() }
        // La lista es perezosa: la fila de ese grupo aún no existe hasta desplazarse hasta ella.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Motores de traducción (código nativo)"))
        compose.onNode(hasText("Motores de traducción (código nativo)")).assertIsDisplayed()
    }

    @Test fun laLicenciaDeLaAppTraeElCodigoFuente() {
        compose.setContent { LectorTheme { LicenseTextScreen(id = "lector-bilingue", onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("https://github.com/DiegoBr4nd/lector-bilingue")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("GNU GENERAL PUBLIC LICENSE", substring = true)).assertExists()
    }

    @Test fun unaLicenciaQueYaNoExisteMuestraElError() {
        compose.setContent { LectorTheme { LicenseTextScreen(id = "ya-no-existe", onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("No se pudo cargar la lista de licencias.")).fetchSemanticsNodes().isNotEmpty() }
    }
}
