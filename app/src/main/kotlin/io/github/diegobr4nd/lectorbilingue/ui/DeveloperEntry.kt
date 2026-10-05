package io.github.diegobr4nd.lectorbilingue.ui

import androidx.compose.runtime.Composable

/**
 * Puerta al menú Desarrollador. Una interfaz es un contrato: dice qué se puede hacer sin decir cómo.
 * El código real vive solo en `src/debug`; en release la implementación está vacía y el APK no lleva
 * ni rastro de la pantalla de desarrollador (el CI lo comprueba).
 */
interface DeveloperEntry {
    /** ¿Existe el menú Desarrollador en esta versión? */
    val available: Boolean

    /** Dibuja el menú (en release no dibuja nada). */
    @Composable
    fun Screen(onBack: () -> Unit)
}
