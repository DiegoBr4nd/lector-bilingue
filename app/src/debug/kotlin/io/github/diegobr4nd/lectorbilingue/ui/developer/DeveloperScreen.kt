package io.github.diegobr4nd.lectorbilingue.ui.developer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.ui.enginetest.EngineTestScreen

/** Menú Desarrollador (solo debug): abre la prueba del motor y permite repetir la Bienvenida. */
@Composable
fun DeveloperScreen(onBack: () -> Unit) {
    var showEngineTest by rememberSaveable { mutableStateOf(false) }
    if (showEngineTest) {
        BackHandler { showEngineTest = false }
        EngineTestScreen()
        return
    }
    BackHandler(onBack = onBack)
    val settings = AppSettings.of(LocalContext.current)
    // Encendido = la Bienvenida volverá a salir (welcomeDone = false).
    var showWelcomeAgain by remember { mutableStateOf(!settings.welcomeDone) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.developer_title), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { showEngineTest = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.developer_engine_test))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = showWelcomeAgain,
                        role = Role.Switch,
                        onValueChange = {
                            showWelcomeAgain = it
                            settings.welcomeDone = !it
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.developer_show_welcome), modifier = Modifier.weight(1f))
                Switch(checked = showWelcomeAgain, onCheckedChange = null)
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.developer_back))
            }
        }
    }
}
