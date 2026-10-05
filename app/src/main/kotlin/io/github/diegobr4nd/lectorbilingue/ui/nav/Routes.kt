package io.github.diegobr4nd.lectorbilingue.ui.nav

/** Las pantallas de la app. Una "ruta" es el nombre de una pantalla (con sus datos, si los lleva). */
sealed interface Route {
    data class Welcome(val step: Int) : Route
    data object Home : Route
    data object Languages : Route
    data object Developer : Route
}

/** Texto para guardar y recuperar la pila al girar la pantalla o si Android cierra la app. */
internal fun Route.encode(): String = when (this) {
    is Route.Welcome -> "welcome:$step"
    Route.Home -> "home"
    Route.Languages -> "languages"
    Route.Developer -> "developer"
}

internal fun decodeRoute(text: String): Route? = when {
    text.startsWith("welcome:") -> text.removePrefix("welcome:").toIntOrNull()?.let { Route.Welcome(it) }
    text == "home" -> Route.Home
    text == "languages" -> Route.Languages
    text == "developer" -> Route.Developer
    else -> null
}
