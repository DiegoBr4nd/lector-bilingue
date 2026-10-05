package io.github.diegobr4nd.lectorbilingue.ui.nav

/** Reglas puras de arranque y de "atrás" (sin Android, fáciles de probar). */
object StartRules {
    fun start(welcomeDone: Boolean): Route = if (welcomeDone) Route.Home else Route.Welcome(1)

    /** Paso anterior de la Bienvenida; `null` si la regla no decide (la pila lo saca o la app se cierra). */
    fun back(current: Route): Route? =
        if (current is Route.Welcome && current.step > 1) Route.Welcome(current.step - 1) else null
}
