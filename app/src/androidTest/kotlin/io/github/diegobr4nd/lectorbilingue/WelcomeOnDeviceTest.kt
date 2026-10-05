package io.github.diegobr4nd.lectorbilingue

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import io.github.diegobr4nd.lectorbilingue.data.ModelMessage
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.data.RowStatus
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.ui.nav.AppNav
import io.github.diegobr4nd.lectorbilingue.ui.welcome.WelcomeScreen
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Gestor de modelos falso: sin red ni archivos, para probar solo la pantalla. */
private class FakeHub(
    initialPairs: List<PairStatus> = defaultPairs(),
    isLoaded: Boolean = true,
) : ModelHubApi {
    override val pairs = MutableStateFlow(initialPairs)
    override val loaded: StateFlow<Boolean> = MutableStateFlow(isLoaded)
    val downloads = mutableListOf<String>()

    override suspend fun refresh(): ModelMessage? = null
    override fun download(modelId: String) { downloads += modelId }
    override fun cancel(modelId: String) {}
    override suspend fun delete(engine: EngineId, pair: String): ModelMessage? = ModelMessage.DELETE_OK
    override suspend fun import(uri: Uri): ModelMessage = ModelMessage.IMPORT_OK
    override fun totalRamBytes(): Long = 8L * 1024 * 1024 * 1024

    companion object {
        fun defaultPairs() = listOf(
            PairStatus("en-es", listOf(RowStatus("opus-en-es", EngineId.OPUS, 238_524_992, false, null))),
            PairStatus("es-en", listOf(RowStatus("opus-es-en", EngineId.OPUS, 238_203_778, false, null))),
        )
    }
}

/** La Bienvenida de punta a punta con un gestor falso. */
@RunWith(AndroidJUnit4::class)
class WelcomeOnDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("welcome_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        settings = AppSettings(prefs)
    }

    private fun setNav(hub: ModelHubApi) = rule.setContent { LectorTheme { AppNav(settings, hub) } }

    @Test
    fun recorre_los_tres_pasos_con_Siguiente() {
        setNav(FakeHub())
        rule.onNodeWithText("Lee en inglés con ayuda").assertExists()
        rule.onNodeWithContentDescription("Paso 1 de 3").assertExists()
        rule.onNode(hasText("Siguiente") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Todo queda en tu teléfono").assertExists()
        rule.onNodeWithContentDescription("Paso 2 de 3").assertExists()
        rule.onNode(hasText("Siguiente") and hasClickAction()).performClick()
        rule.onNodeWithText("Elige tu idioma").assertExists()
        rule.onNodeWithContentDescription("Paso 3 de 3").assertExists()
        rule.onNodeWithText("Siguiente").assertDoesNotExist()
    }

    @Test
    fun atras_vuelve_del_paso_3_al_2() {
        setNav(FakeHub())
        rule.onNodeWithText("Siguiente").performClick()
        rule.onNodeWithText("Siguiente").performClick()
        rule.onNodeWithText("Elige tu idioma").assertExists()
        Espresso.pressBack()
        rule.onNodeWithText("Todo queda en tu teléfono").assertExists()
        rule.onNodeWithContentDescription("Paso 2 de 3").assertExists()
    }

    @Test
    fun mas_tarde_llama_a_terminar() {
        var finished = 0
        rule.setContent {
            LectorTheme { WelcomeScreen(step = 3, hub = FakeHub(), onNext = {}, onFinish = { finished++ }) }
        }
        rule.onNode(hasText("Más tarde") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, finished)
    }

    @Test
    fun mas_tarde_marca_la_bienvenida_y_abre_inicio() {
        setNav(FakeHub())
        rule.onNodeWithText("Siguiente").performClick()
        rule.onNodeWithText("Siguiente").performClick()
        rule.onNodeWithText("Más tarde").performClick()
        rule.waitForIdle()
        assertTrue(settings.welcomeDone)
        rule.onNodeWithText("Home").assertExists()
    }

    @Test
    fun el_indicador_anuncia_el_paso_actual() {
        var step by mutableIntStateOf(1)
        rule.setContent {
            LectorTheme { WelcomeScreen(step = step, hub = FakeHub(), onNext = { step++ }, onFinish = {}) }
        }
        rule.onNodeWithContentDescription("Paso 1 de 3").assertExists()
        rule.onNodeWithText("Siguiente").performClick()
        rule.onNodeWithContentDescription("Paso 2 de 3").assertExists()
    }

    @Test
    fun descargar_encola_solo_lo_elegido_y_termina() {
        val hub = FakeHub()
        var finished = 0
        rule.setContent {
            LectorTheme { WelcomeScreen(step = 3, hub = hub, onNext = {}, onFinish = { finished++ }) }
        }
        rule.onNodeWithText("Inglés → español").assertExists()
        rule.onNodeWithText("Descargar (227 MB)").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Importar desde archivo (.zip)").assertExists()
        // En Android 13 o superior sale el permiso de notificaciones; este camino solo se comprueba sin él.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            rule.onNodeWithText("Descargar (227 MB)").performClick()
            assertEquals(listOf("opus-en-es"), hub.downloads)
            assertEquals(1, finished)
        }
    }

    @Test
    fun mientras_carga_muestra_buscando_y_no_el_aviso_de_sin_catalogo() {
        rule.setContent {
            LectorTheme {
                WelcomeScreen(step = 3, hub = FakeHub(initialPairs = emptyList(), isLoaded = false), onNext = {}, onFinish = {})
            }
        }
        rule.onNodeWithText("Buscando idiomas…").assertExists()
        rule.onNodeWithText("Intentar de nuevo").assertDoesNotExist()
        rule.onNodeWithText("Importar desde archivo (.zip)").assertExists()
        rule.onNodeWithText("Más tarde").assertExists()
    }

    @Test
    fun sin_catalogo_ofrece_reintentar_importar_y_mas_tarde() {
        rule.setContent {
            LectorTheme {
                WelcomeScreen(step = 3, hub = FakeHub(initialPairs = emptyList()), onNext = {}, onFinish = {})
            }
        }
        rule.onNodeWithText("Intentar de nuevo").assertExists()
        rule.onNodeWithText("Importar desde archivo (.zip)").assertExists()
        rule.onNodeWithText("Más tarde").assertExists()
    }
}
