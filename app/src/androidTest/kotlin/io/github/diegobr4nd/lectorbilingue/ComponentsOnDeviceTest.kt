package io.github.diegobr4nd.lectorbilingue

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.diegobr4nd.lectorbilingue.core.ui.components.ConfirmDialog
import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.core.ui.components.ModelRow
import io.github.diegobr4nd.lectorbilingue.core.ui.components.ModelRowState
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Semántica de los componentes de :core:ui (lo que ve TalkBack), sin tocar red ni archivos. */
@RunWith(AndroidJUnit4::class)
class ComponentsOnDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private fun row(state: ModelRowState) = rule.setContent {
        LectorTheme {
            ModelRow(EngineKind.QUALITY, 180, "Español → Inglés", state, {}, {}, {}, enabled = true)
        }
    }

    @Test
    fun instalado_muestra_estado_y_boton_borrar() {
        row(ModelRowState.Installed)
        rule.onNodeWithText("Instalado").assertExists()
        rule.onNode(hasText("Borrar") and hasClickAction()).assertExists().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun descargando_muestra_porcentaje_y_boton_cancelar() {
        row(ModelRowState.Downloading(0.4f))
        rule.onNodeWithText("Descargando… 40 %").assertExists()
        rule.onNode(hasText("Cancelar") and hasClickAction()).assertExists().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun no_instalado_tiene_boton_descargar_de_48dp() {
        row(ModelRowState.NotInstalled)
        rule.onNode(hasText("Descargar") and hasClickAction()).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun dialogo_confirma_y_cancela() {
        var confirmado = 0
        var cancelado = 0
        rule.setContent {
            LectorTheme {
                ConfirmDialog("Borrar modelo", "Podrás volver a descargarlo.", "Borrar", { confirmado++ }, { cancelado++ })
            }
        }
        rule.onNodeWithText("Borrar").performClick()
        assertEquals(1, confirmado)
        rule.onNodeWithText("Cancelar").performClick()
        assertEquals(1, cancelado)
    }

    @Test
    fun descarga_sin_total_muestra_barra_indeterminada() {
        row(ModelRowState.Downloading(null))
        rule.onNodeWithText("Descargando…").assertExists()
        rule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun no_instalado_muestra_su_estado() {
        row(ModelRowState.NotInstalled)
        rule.onNodeWithText("No instalado").assertExists()
    }

    @Test
    fun cada_estado_tiene_una_sola_accion() {
        val estados = listOf(
            ModelRowState.NotInstalled,
            ModelRowState.Installed,
            ModelRowState.InUse,
            ModelRowState.Downloading(0.4f),
        )
        var estado by mutableStateOf<ModelRowState>(estados[0])
        rule.setContent {
            LectorTheme { ModelRow(EngineKind.FAST, 40, "Español → Inglés", estado, {}, {}, {}, enabled = true) }
        }
        for (e in estados) {
            estado = e
            rule.waitForIdle()
            rule.onAllNodes(hasClickAction()).assertCountEquals(1)
        }
    }

    @Test
    fun las_acciones_dicen_a_que_modelo_se_refieren() {
        var estado by mutableStateOf<ModelRowState>(ModelRowState.NotInstalled)
        rule.setContent {
            LectorTheme { ModelRow(EngineKind.FAST, 40, "Español → Inglés", estado, {}, {}, {}, enabled = true) }
        }
        rule.onNode(hasContentDescription("Descargar modelo Rápido, Español → Inglés") and hasClickAction()).assertExists()
        estado = ModelRowState.Downloading(0.1f)
        rule.onNode(hasContentDescription("Cancelar descarga de Rápido, Español → Inglés") and hasClickAction()).assertExists()
        estado = ModelRowState.Installed
        rule.onNode(hasContentDescription("Borrar modelo Rápido, Español → Inglés") and hasClickAction()).assertExists()
    }

    @Test
    fun nombre_tamano_y_estado_se_leen_juntos() {
        row(ModelRowState.Installed)
        rule.onNode(hasText("Calidad") and hasText("Instalado")).assertExists()
    }

    @Test
    fun dialogo_atras_llama_a_onDismiss() {
        var cancelado = 0
        rule.setContent {
            LectorTheme { ConfirmDialog("Borrar modelo", "Texto", "Borrar", {}, { cancelado++ }) }
        }
        Espresso.pressBack()
        rule.waitForIdle()
        assertEquals(1, cancelado)
    }
}
