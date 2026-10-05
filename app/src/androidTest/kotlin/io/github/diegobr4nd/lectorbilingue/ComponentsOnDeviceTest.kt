package io.github.diegobr4nd.lectorbilingue

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
            ModelRow(EngineKind.QUALITY, 180, state, {}, {}, {}, enabled = true)
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
}
