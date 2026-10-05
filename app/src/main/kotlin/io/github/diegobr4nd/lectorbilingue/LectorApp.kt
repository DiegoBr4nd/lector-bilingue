package io.github.diegobr4nd.lectorbilingue

import android.app.Application
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.ModelHub

/**
 * La "Application" vive mientras viva el proceso: es el lugar para lo que debe existir una sola vez.
 * El gestor de modelos no se cancela nunca, así que solo puede haber una copia.
 */
class LectorApp : Application() {
    val hub: ModelHub by lazy { ModelHub(this) }
    val settings: AppSettings by lazy { AppSettings.of(this) }
}
