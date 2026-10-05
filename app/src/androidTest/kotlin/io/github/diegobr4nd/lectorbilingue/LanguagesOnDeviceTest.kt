package io.github.diegobr4nd.lectorbilingue

import android.content.Context
import android.net.Uri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.EnginePreference
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.data.RowStatus
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.ui.home.HomeScreen
import io.github.diegobr4nd.lectorbilingue.ui.languages.LanguagesScreen
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Gestor de modelos falso: sin red ni archivos, para probar solo las pantallas. */
private class LangFakeHub(
    initialPairs: List<PairStatus>,
    isLoaded: Boolean = true,
) : ModelHubApi {
    override val pairs = MutableStateFlow(initialPairs)
    override val loaded: StateFlow<Boolean> = MutableStateFlow(isLoaded)
    val deletes = mutableListOf<Pair<EngineId, String>>()
    val downloads = mutableListOf<String>()

    override suspend fun refresh(): ModelMessage? = null
    override fun download(modelId: String) { downloads += modelId }
    override fun cancel(modelId: String) {}
    override suspend fun delete(engine: EngineId, pair: String): ModelMessage? {
        deletes += engine to pair
        return ModelMessage.DELETE_OK
    }
    override suspend fun import(uri: Uri): ModelMessage = ModelMessage.IMPORT_OK
    override fun totalRamBytes(): Long = 8L * 1024 * 1024 * 1024
}

private fun installedOpus() = listOf(
    PairStatus(
        "en-es",
        listOf(
            RowStatus("opus-en-es", EngineId.OPUS, 238_524_992, true, null),
            RowStatus("firefox-en-es", EngineId.FIREFOX, 36_594_513, false, null),
        ),
    ),
)

/** Inicio e Idiomas con un gestor falso. Los ajustes usan su propio archivo, nunca el real. */
@RunWith(AndroidJUnit4::class)
class LanguagesOnDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var settings: AppSettings

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Before
    fun setUp() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("languages_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        settings = AppSettings(prefs)
    }

    private fun showLanguages(hub: ModelHubApi) {
        rule.setContent { LectorTheme { LanguagesScreen(hub = hub, settings = settings, onBack = {}) } }
        // Los ajustes se leen fuera del hilo principal: se espera a que aparezca el selector.
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Motor").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun inDialog(text: String) = hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())

    @Test
    fun borrar_abre_el_dialogo_y_cancelar_no_borra() {
        val hub = LangFakeHub(installedOpus())
        showLanguages(hub)
        rule.onNodeWithContentDescription("Borrar modelo Calidad").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("¿Borrar Calidad?").assertExists()
        rule.onNodeWithText("Liberarás 227 MB. Para volver a usarlo tendrás que descargarlo otra vez.").assertExists()
        rule.onNode(inDialog("Cancelar")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("¿Borrar Calidad?").assertDoesNotExist()
        assertTrue(hub.deletes.isEmpty())
    }

    @Test
    fun borrar_en_el_dialogo_llama_a_delete() {
        val hub = LangFakeHub(installedOpus())
        showLanguages(hub)
        rule.onNodeWithContentDescription("Borrar modelo Calidad").performClick()
        rule.onNode(inDialog("Borrar")).performClick()
        rule.waitUntil(5_000) { hub.deletes.isNotEmpty() }
        assertEquals(listOf(EngineId.OPUS to "en-es"), hub.deletes)
        rule.onNodeWithText("¿Borrar Calidad?").assertDoesNotExist()
    }

    @Test
    fun el_selector_de_motor_son_tres_botones_de_opcion_y_la_eleccion_se_guarda() {
        showLanguages(LangFakeHub(installedOpus()))
        rule.onAllNodes(radio).assertCountEquals(3)
        rule.onNode(radio and hasText("Automático")).assertIsSelected()
        rule.onNodeWithText("Automático elige Calidad en tu teléfono (8 GB).").assertExists()

        rule.onNode(radio and hasText("Rápido")).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNode(radio and hasText("Rápido")).assertIsSelected()
        assertEquals(EnginePreference.FAST, settings.enginePreference)
        rule.onNodeWithText("Automático elige Calidad en tu teléfono (8 GB).").assertDoesNotExist()
    }

    @Test
    fun mientras_carga_muestra_buscando() {
        rule.setContent {
            LectorTheme { LanguagesScreen(hub = LangFakeHub(emptyList(), isLoaded = false), settings = settings, onBack = {}) }
        }
        rule.onAllNodesWithText("Buscando…").assertCountEquals(1)
        rule.onNodeWithText("Importar desde archivo (.zip)").assertExists()
    }

    @Test
    fun sin_idiomas_el_inicio_invita_a_descargar_y_lleva_a_idiomas() {
        var opened = 0
        rule.setContent {
            LectorTheme {
                HomeScreen(
                    hub = LangFakeHub(emptyList()),
                    settings = settings,
                    onLanguages = { opened++ },
                    onDeveloper = null,
                )
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Aún no tienes idiomas").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Descargar idiomas") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun con_un_idioma_el_inicio_dice_listo_para_traducir() {
        rule.setContent {
            LectorTheme {
                HomeScreen(hub = LangFakeHub(installedOpus()), settings = settings, onLanguages = {}, onDeveloper = null)
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Listo para traducir · Calidad").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Inglés → español").assertExists()
        rule.onNodeWithContentDescription("Más opciones").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun cambiar_el_motor_en_idiomas_se_ve_en_inicio_al_volver() {
        settings.welcomeDone = true
        val both = listOf(
            PairStatus(
                "en-es",
                listOf(
                    RowStatus("opus-en-es", EngineId.OPUS, 238_524_992, true, null),
                    RowStatus("firefox-en-es", EngineId.FIREFOX, 36_594_513, true, null),
                ),
            ),
        )
        val hub = LangFakeHub(both)
        rule.setContent { LectorTheme { io.github.diegobr4nd.lectorbilingue.ui.nav.AppNav(settings, hub) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Listo para traducir · Calidad").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Idiomas") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Motor").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(radio and hasText("Rápido")).performClick()
        androidx.test.espresso.Espresso.pressBack()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Listo para traducir · Rápido").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Listo para traducir · Calidad").assertDoesNotExist()
    }
}
