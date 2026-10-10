package io.github.diegobr4nd.lectorbilingue

import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.LiveRegionMode
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
import androidx.compose.ui.test.performScrollTo
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
import io.github.diegobr4nd.lectorbilingue.data.TranslationCacheApi
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.ui.library.LanguageNotice
import io.github.diegobr4nd.lectorbilingue.ui.library.LibraryContent
import io.github.diegobr4nd.lectorbilingue.ui.library.LibraryUiState
import io.github.diegobr4nd.lectorbilingue.ui.library.languageNotices
import io.github.diegobr4nd.lectorbilingue.ui.nav.AppNav
import io.github.diegobr4nd.lectorbilingue.ui.languages.LanguagesScreen
import io.github.diegobr4nd.lectorbilingue.ui.withNoBreakArrow
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** Idiomas y el aviso de idiomas de la Biblioteca con un gestor falso. Los ajustes usan su propio archivo, nunca el real. */
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

    private val deleteTitle: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.languages_delete_title, "Calidad")

    @Test
    fun borrar_abre_el_dialogo_y_cancelar_no_borra() {
        val hub = LangFakeHub(installedOpus())
        showLanguages(hub)
        rule.onNodeWithContentDescription("Borrar modelo Calidad, Inglés → español").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText(deleteTitle).assertExists()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // La flecha lleva un espacio sin corte antes (U+00A0), igual que en el diálogo.
        val direction = ctx.getString(R.string.pair_direction_en_es).withNoBreakArrow()
        rule.onNodeWithText(ctx.getString(R.string.languages_delete_body, 227, direction)).assertExists()
        rule.onNode(inDialog("Cancelar")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(deleteTitle).assertDoesNotExist()
        assertTrue(hub.deletes.isEmpty())
    }

    @Test
    fun borrar_en_el_dialogo_llama_a_delete() {
        val hub = LangFakeHub(installedOpus())
        showLanguages(hub)
        rule.onNodeWithContentDescription("Borrar modelo Calidad, Inglés → español").performClick()
        rule.onNode(inDialog("Borrar")).performClick()
        rule.waitUntil(5_000) { hub.deletes.isNotEmpty() }
        assertEquals(listOf(EngineId.OPUS to "en-es"), hub.deletes)
        rule.onNodeWithText(deleteTitle).assertDoesNotExist()
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
    fun sin_idiomas_el_aviso_invita_a_descargar() = runBlocking {
        settings.loadEnginePreference()
        val notice = withTimeout(5_000) { languageNotices(LangFakeHub(emptyList()), settings).first() }
        assertEquals(LanguageNotice.NoLanguages, notice)
    }

    @Test
    fun con_un_idioma_listo_no_hay_aviso() = runBlocking {
        settings.loadEnginePreference()
        // Gestor y ajustes ya cargados: el primer valor es el definitivo, y no hay nada que avisar.
        val notice = withTimeout(5_000) { languageNotices(LangFakeHub(installedOpus()), settings).first() }
        assertNull(notice)
    }

    @Test
    fun cambiar_el_motor_en_idiomas_se_ve_en_la_biblioteca_al_volver() {
        settings.welcomeDone = true
        runBlocking { settings.loadEnginePreference() }
        val hub = LangFakeHub(installedOpus())
        rule.setContent {
            LectorTheme {
                AppNav(
                    settings, hub,
                    library = { onLanguages, _ ->
                        val notice by remember { languageNotices(hub, settings) }.collectAsState(null)
                        LibraryContent(
                            state = LibraryUiState(loaded = true, notice = notice),
                            onAdd = {}, onOpen = {}, onDelete = {},
                            onLanguages = onLanguages, onDeveloper = null,
                            snackbar = remember { SnackbarHostState() },
                        )
                    },
                )
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Biblioteca").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Falta", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Más opciones").performClick()
        rule.onNode(hasText("Idiomas") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Motor").fetchSemanticsNodes().isNotEmpty() }
        // Solo Calidad está instalado: elegir Rápido deja al par sin el motor elegido.
        rule.onNode(radio and hasText("Rápido")).performClick()
        androidx.test.espresso.Espresso.pressBack()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Falta Rápido", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Falta Rápido", substring = true).assertHeightIsAtLeast(48.dp)
    }

    /** Caché falso: [fail] hace fallar el borrado; [measureFails], la medida. Nunca toca la base real. */
    private class LangFakeCache(var bytes: Long, val fail: Boolean = false, var measureFails: Boolean = false) : TranslationCacheApi {
        override suspend fun cacheBytes(): Long = if (measureFails) error("no se pudo medir") else bytes
        override suspend fun clearCache() {
            if (fail) error("fallo")
            bytes = 0
        }
    }

    private fun showLanguagesWithCache(cache: TranslationCacheApi) {
        rule.setContent {
            LectorTheme { LanguagesScreen(hub = LangFakeHub(installedOpus()), settings = settings, onBack = {}, cache = cache) }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Motor").fetchSemanticsNodes().isNotEmpty() }
    }

    /** I4: el aviso de borrar sale junto a la fila de traducciones guardadas (donde está el dedo), no arriba. */
    private fun assertCacheMessageNextToRow(message: String, rowText: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        val nodes = rule.onAllNodesWithText(message).fetchSemanticsNodes()
        assertEquals(1, nodes.size, "el aviso sale una sola vez")
        val msg = nodes.single()
        assertEquals(LiveRegionMode.Polite, msg.config[SemanticsProperties.LiveRegion])
        val row = rule.onNodeWithText(rowText, substring = true).fetchSemanticsNode().boundsInRoot
        val gap = with(rule.density) { (msg.boundsInRoot.top - row.bottom).toDp() }
        assertTrue(gap >= (-1).dp && gap < 40.dp, "el aviso está lejos de la fila: $gap")
    }

    @Test
    fun el_aviso_de_traducciones_borradas_sale_junto_a_la_fila() {
        showLanguagesWithCache(LangFakeCache(bytes = 3_355_443L))
        rule.onNodeWithText("Traducciones guardadas", substring = true).performScrollTo()
        rule.onNodeWithContentDescription("Borrar las traducciones guardadas", substring = true).performClick()
        rule.onNode(inDialog("Borrar")).performClick()
        assertCacheMessageNextToRow("Traducciones borradas", "Traducciones guardadas: ninguna")
    }

    @Test
    fun el_aviso_de_fallo_al_borrar_sale_junto_a_la_fila() {
        showLanguagesWithCache(LangFakeCache(bytes = 3_355_443L, fail = true))
        rule.onNodeWithText("Traducciones guardadas", substring = true).performScrollTo()
        rule.onNodeWithContentDescription("Borrar las traducciones guardadas", substring = true).performClick()
        rule.onNode(inDialog("Borrar")).performClick()
        assertCacheMessageNextToRow("No se pudieron borrar las traducciones", "Traducciones guardadas: aprox.")
    }
}
