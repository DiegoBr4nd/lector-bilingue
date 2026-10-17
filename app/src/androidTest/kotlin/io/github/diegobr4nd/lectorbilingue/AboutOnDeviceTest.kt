package io.github.diegobr4nd.lectorbilingue

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.Licenses
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.ui.about.AboutScreen
import io.github.diegobr4nd.lectorbilingue.ui.about.DeviceInfo
import io.github.diegobr4nd.lectorbilingue.ui.about.FEEDBACK_EMAIL
import io.github.diegobr4nd.lectorbilingue.ui.about.LicenseTextScreen
import io.github.diegobr4nd.lectorbilingue.ui.about.LicensesScreen
import io.github.diegobr4nd.lectorbilingue.ui.howto.HowToScreen
import io.github.diegobr4nd.lectorbilingue.ui.nav.AppNav
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** «Acerca de» y «Licencias» con el catálogo real del APK. No toca datos de Juan (ajustes en un archivo de prueba, sin libros). */
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
        compose.onNodeWithText(FEEDBACK_EMAIL).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No hay una app de correo en este teléfono. Escríbenos a:").assertIsDisplayed()
        // Una sola región que TalkBack anuncia, con el aviso y la dirección juntos.
        val live = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes()
        assertEquals(1, live.size)
        assertEquals(
            listOf("No hay una app de correo en este teléfono. Escríbenos a:", FEEDBACK_EMAIL),
            live.single().config[SemanticsProperties.Text].map { it.text },
        )
    }

    @Test fun atribucionDeLosModelosVisible() {
        compose.setContent { LectorTheme { AboutScreen(onBack = {}, onLicenses = {}, info = info, startMail = { true }) } }
        // El párrafo de atribución (el botón «Licencia CC-BY-4.0» de abajo también dice CC-BY-4.0).
        compose.onNodeWithText("Universidad de Helsinki. Licencia CC-BY-4.0.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("MPL-2.0", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("Abrir Modelo inglés", substring = true)).performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNode(hasContentDescription("Abrir Licencia CC-BY-4.0", substring = true)).performScrollTo().assertHeightIsAtLeast(48.dp)
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
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("https://github.com/DiegoBr4nd/lector-bilingue/tree/v0.9.0")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("GNU GENERAL PUBLIC LICENSE", substring = true)).assertExists()
    }

    @Test fun unaLicenciaQueYaNoExisteMuestraElError() {
        compose.setContent { LectorTheme { LicenseTextScreen(id = "ya-no-existe", onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("No se pudo abrir esta licencia.")).fetchSemanticsNodes().isNotEmpty() }
    }

    // Spec 6a §9: Biblioteca → «Acerca de» → «Licencias» → la licencia de la app, y atrás tres veces vuelve a la Biblioteca.
    // Ajustes en un archivo de prueba propio (no los de Juan) con la Bienvenida ya hecha, y un gestor de modelos vacío.
    @Test fun deLaBibliotecaALaLicenciaDeLaAppYDeVuelta() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("about_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = AppSettings(prefs).apply { welcomeDone = true }
        compose.setContent {
            LectorTheme {
                AppNav(settings, EmptyHub(), library = { _, _, _, onAbout -> Button(onClick = onAbout) { Text("Abrir acerca de") } })
            }
        }
        compose.onNodeWithText("Abrir acerca de").performClick()
        compose.onNodeWithText("Licencias de código abierto").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("GPL-3.0-or-later")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Lector bilingüe") and hasClickAction()).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("Abrir Código fuente", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasContentDescription("Abrir Código fuente", substring = true)).assertIsDisplayed()
        repeat(3) {
            Espresso.pressBack()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Abrir acerca de").assertIsDisplayed()
    }

    // ---------- Auditoría M3 (I3): la barra superior común ----------

    @Test fun acercaDeTieneLaBarraComun() = compose.assertSharedTopBar("Acerca de") {
        AboutScreen(onBack = {}, onLicenses = {}, info = info, startMail = { true })
    }

    @Test fun licenciasTieneLaBarraComun() = compose.assertSharedTopBar("Licencias") {
        LicensesScreen(onBack = {}, onOpen = {})
    }

    @Test fun elTextoDeLicenciaTieneLaBarraComun() = compose.assertSharedTopBar("Lector bilingüe") {
        LicenseTextScreen(id = "lector-bilingue", onBack = {})
    }

    @Test fun comoSeUsaTieneLaBarraComun() = compose.assertSharedTopBar("Cómo se usa") {
        HowToScreen(onBack = {}, onResetTips = {})
    }
}

/** Gestor de modelos vacío: sin red ni archivos (esta prueba no pasa por la Bienvenida ni por Idiomas). */
private class EmptyHub : ModelHubApi {
    override val pairs = MutableStateFlow(emptyList<PairStatus>())
    override val loaded = MutableStateFlow(true)
    override suspend fun refresh(): ModelMessage? = null
    override fun download(modelId: String) {}
    override fun cancel(modelId: String) {}
    override suspend fun delete(engine: EngineId, pair: String): ModelMessage? = null
    override suspend fun import(uri: Uri): ModelMessage = ModelMessage.INVALID_ZIP
    override fun totalRamBytes(): Long = 8L * 1024 * 1024 * 1024
}
