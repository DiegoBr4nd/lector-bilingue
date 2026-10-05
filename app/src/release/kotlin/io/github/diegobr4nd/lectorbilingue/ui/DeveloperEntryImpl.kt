package io.github.diegobr4nd.lectorbilingue.ui

import androidx.compose.runtime.Composable

/** Versión release: no hay menú Desarrollador y no se referencia ningún código de él. */
object DeveloperEntries {
    val current: DeveloperEntry = object : DeveloperEntry {
        override val available = false

        @Composable
        override fun Screen(onBack: () -> Unit) = Unit
    }
}
