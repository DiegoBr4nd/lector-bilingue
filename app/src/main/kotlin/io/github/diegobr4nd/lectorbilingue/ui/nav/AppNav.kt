package io.github.diegobr4nd.lectorbilingue.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.ModelHubApi
import io.github.diegobr4nd.lectorbilingue.ui.DeveloperEntries
import io.github.diegobr4nd.lectorbilingue.ui.rememberReduceMotion
import io.github.diegobr4nd.lectorbilingue.ui.home.HomeScreen
import io.github.diegobr4nd.lectorbilingue.ui.languages.LanguagesScreen
import io.github.diegobr4nd.lectorbilingue.ui.welcome.WelcomeScreen

/**
 * Navegación de toda la app (Navigation 3: la pila de pantallas es una lista que manejamos nosotros).
 */
@Composable
fun AppNav(
    settings: AppSettings,
    hub: ModelHubApi,
    onClose: () -> Unit = {},
    // "Ranura" (slot): un hueco que llena quien llama. La Tarea 8 la usa para la Biblioteca.
    library: @Composable (onLanguages: () -> Unit, onDeveloper: (() -> Unit)?) -> Unit = { _, _ -> },
) {
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

    // Con "quitar animaciones" activado en Android, las pantallas cambian sin animar; si no, con un fundido suave.
    val reduceMotion = rememberReduceMotion()
    val fade = if (reduceMotion) {
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        fadeIn() togetherWith fadeOut()
    }

    NavDisplay(
        backStack = backStack,
        onBack = { pop() },
        transitionSpec = { fade },
        popTransitionSpec = { fade },
        predictivePopTransitionSpec = { fade },
        entryProvider = { key ->
            when (key) {
                is Route.Welcome -> NavEntry(key) {
                    WelcomeScreen(
                        step = key.step,
                        hub = hub,
                        onNext = { backStack[backStack.lastIndex] = StartRules.next(key) },
                        onBack = { StartRules.back(key)?.let { backStack[backStack.lastIndex] = it } },
                        onFinish = {
                            settings.welcomeDone = true
                            backStack.clear()
                            backStack.add(Route.Library)
                        },
                    )
                }
                Route.Library -> NavEntry(key) {
                    HomeScreen(
                        hub = hub,
                        settings = settings,
                        onLanguages = { backStack.add(Route.Languages) },
                        onDeveloper = if (developer.available) ({ backStack.add(Route.Developer) }) else null,
                    )
                }
                Route.Languages -> NavEntry(key) {
                    LanguagesScreen(hub = hub, settings = settings, onBack = { pop() })
                }
                Route.Developer -> NavEntry(key) {
                    // Sin pantalla de desarrollador (release) esta ruta no se alcanza: Routes la descarta.
                    if (developer.available) developer.Screen(onBack = { pop() })
                }
            }
        },
    )
    // En Welcome la pila tiene una sola entrada: este manejador (registrado al final, gana) retrocede un paso.
    BackHandler(enabled = StartRules.back(backStack.last()) != null) { pop() }
}
