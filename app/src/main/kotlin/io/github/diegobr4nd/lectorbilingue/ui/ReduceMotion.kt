package io.github.diegobr4nd.lectorbilingue.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * ¿La persona pidió "quitar animaciones" en Android? Eso pone la escala de animación en 0
 * (Ajustes, Accesibilidad, Quitar animaciones). Si es así, nada se anima.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        try {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: RuntimeException) {
            false
        }
    }
}
