package io.github.diegobr4nd.lectorbilingue.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId

/**
 * Motor que eligió el usuario en Idiomas. No se llama `EngineChoice` para no chocar con el de `:engine:api`.
 * [wire] es el valor guardado en los ajustes.
 */
enum class EnginePreference(val wire: String) {
    AUTO("auto"), QUALITY("opus"), FAST("firefox");

    /** El motor que se pasa a `EngineSelector` como forzado (null = que decida la RAM). */
    fun toForced(): EngineId? = when (this) {
        AUTO -> null
        QUALITY -> EngineId.OPUS
        FAST -> EngineId.FIREFOX
    }

    companion object {
        /** Un valor desconocido (o ausente) se lee como [AUTO]. */
        fun fromWire(value: String?): EnginePreference = entries.firstOrNull { it.wire == value } ?: AUTO
    }
}

/**
 * Ajustes guardados en el propio teléfono (`SharedPreferences`: un archivo pequeño de clave y valor).
 * Un valor de otro tipo (archivo dañado o de otra versión) se lee como el valor por defecto.
 * Leer la primera vez toca el disco: los ViewModels lo hacen fuera del hilo principal.
 */
class AppSettings(private val prefs: SharedPreferences) {
    var welcomeDone: Boolean
        get() = try {
            prefs.getBoolean(KEY_WELCOME, false)
        } catch (_: ClassCastException) {
            false
        }
        set(value) = prefs.edit { putBoolean(KEY_WELCOME, value) }

    var enginePreference: EnginePreference
        get() = try {
            EnginePreference.fromWire(prefs.getString(KEY_ENGINE, null))
        } catch (_: ClassCastException) {
            EnginePreference.AUTO
        }
        set(value) = prefs.edit { putString(KEY_ENGINE, value.wire) }

    companion object {
        private const val FILE = "app_settings"
        private const val KEY_WELCOME = "welcome_done"
        private const val KEY_ENGINE = "engine_choice"

        fun of(context: Context) = AppSettings(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
