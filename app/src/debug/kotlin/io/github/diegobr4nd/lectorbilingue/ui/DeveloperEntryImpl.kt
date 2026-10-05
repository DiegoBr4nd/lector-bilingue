package io.github.diegobr4nd.lectorbilingue.ui

import androidx.compose.runtime.Composable
import io.github.diegobr4nd.lectorbilingue.ui.developer.DeveloperScreen

/** Versión debug: el menú Desarrollador existe. `object` = una sola instancia en toda la app. */
object DeveloperEntries {
    val current: DeveloperEntry = object : DeveloperEntry {
        override val available = true

        @Composable
        override fun Screen(onBack: () -> Unit) = DeveloperScreen(onBack)
    }
}
