package io.github.diegobr4nd.lectorbilingue.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.ModelHub
import io.github.diegobr4nd.lectorbilingue.ui.DeveloperEntries

/**
 * Navegación de toda la app (Navigation 3: la pila de pantallas es una lista que manejamos nosotros).
 * Las pantallas son marcadores hasta las Tareas 7 a 9.
 */
@Composable
fun AppNav(settings: AppSettings, hub: ModelHub, onClose: () -> Unit = {}) {
    val backStack = rememberSaveable(
        saver = listSaver<MutableList<Route>, String>(
            save = { list -> list.map { it.encode() } },
            restore = { saved -> mutableStateListOf<Route>().apply { saved.mapNotNullTo(this) { decodeRoute(it) } } },
        ),
    ) { mutableStateListOf(StartRules.start(settings.welcomeDone)) }
    if (backStack.isEmpty()) backStack.add(StartRules.start(settings.welcomeDone))

    val developer = DeveloperEntries.current

    fun pop() {
        val previous = StartRules.back(backStack.last())
        when {
            previous != null -> backStack[backStack.lastIndex] = previous
            backStack.size > 1 -> backStack.removeAt(backStack.lastIndex)
            else -> onClose()
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { pop() },
        entryProvider = { key ->
            when (key) {
                is Route.Welcome -> NavEntry(key) {
                    Placeholder("Welcome ${key.step}") {
                        Button(onClick = { backStack[backStack.lastIndex] = Route.Welcome(key.step + 1) }) {
                            Text("Siguiente")
                        }
                        Button(onClick = {
                            settings.welcomeDone = true
                            backStack.clear()
                            backStack.add(Route.Home)
                        }) { Text("Terminar") }
                    }
                }
                Route.Home -> NavEntry(key) {
                    Placeholder("Home") {
                        Button(onClick = { backStack.add(Route.Languages) }) { Text("Idiomas") }
                        if (developer.available) {
                            Button(onClick = { backStack.add(Route.Developer) }) { Text("Desarrollador") }
                        }
                    }
                }
                Route.Languages -> NavEntry(key) { Placeholder("Languages") {} }
                Route.Developer -> NavEntry(key) {
                    if (developer.available) developer.Screen(onBack = { pop() }) else Placeholder("Developer") {}
                }
            }
        },
    )
    // En Welcome la pila tiene una sola entrada: este manejador (registrado al final, gana) retrocede un paso.
    BackHandler(enabled = StartRules.back(backStack.last()) != null) { pop() }
}

@Composable
private fun Placeholder(name: String, actions: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(name)
        actions()
    }
}
