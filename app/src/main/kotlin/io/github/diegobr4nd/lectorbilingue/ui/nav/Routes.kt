package io.github.diegobr4nd.lectorbilingue.ui.nav

import io.github.diegobr4nd.lectorbilingue.ui.DeveloperEntries

/** Las pantallas de la app. Una "ruta" es el nombre de una pantalla (con sus datos, si los lleva). */
sealed interface Route {
    data class Welcome(val step: Int) : Route
    data object Home : Route
    data object Languages : Route
    data object Developer : Route

    companion object {
        /** La Bienvenida tiene 3 pasos. */
        const val WELCOME_STEPS = 3
    }
}

/** Texto para guardar y recuperar la pila al girar la pantalla o si Android cierra la app. */
internal fun Route.encode(): String = when (this) {
    is Route.Welcome -> "welcome:$step"
    Route.Home -> "home"
    Route.Languages -> "languages"
    Route.Developer -> "developer"
}

/** Lo inválido da `null`: un paso fuera de 1..3, un texto raro, o Desarrollador en una versión que no lo trae. */
internal fun decodeRoute(text: String): Route? = when {
    text.startsWith("welcome:") ->
        text.removePrefix("welcome:").toIntOrNull()?.takeIf { it in 1..Route.WELCOME_STEPS }?.let { Route.Welcome(it) }
    text == "home" -> Route.Home
    text == "languages" -> Route.Languages
    text == "developer" -> if (DeveloperEntries.current.available) Route.Developer else null
    else -> null
}
