package io.github.diegobr4nd.lectorbilingue.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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

    private val engineState = MutableStateFlow(EnginePreference.AUTO)
    private val engineLoaded = MutableStateFlow(false)

    /**
     * El motor elegido, observable: cambia en el mismo instante en que se guarda, así Inicio refleja lo que se
     * cambió en Idiomas sin releer. Vale [EnginePreference.AUTO] hasta que [enginePreferenceLoaded] sea true.
     */
    val enginePreferenceFlow: StateFlow<EnginePreference> = engineState.asStateFlow()

    /** true cuando [enginePreferenceFlow] ya tiene el valor guardado (la primera lectura toca el disco). */
    val enginePreferenceLoaded: StateFlow<Boolean> = engineLoaded.asStateFlow()

    /** Lee el motor guardado fuera del hilo principal y lo publica. Repetirla no hace nada. */
    suspend fun loadEnginePreference(io: CoroutineDispatcher = Dispatchers.IO) {
        if (engineLoaded.value) return
        val stored = withContext(io) { readEngine() }
        // Si mientras tanto alguien guardó un valor nuevo, ese manda.
        if (!engineLoaded.value) engineState.value = stored
        engineLoaded.value = true
    }

    var enginePreference: EnginePreference
        get() = readEngine()
        set(value) {
            prefs.edit { putString(KEY_ENGINE, value.wire) }
            engineState.value = value
            engineLoaded.value = true
        }

    private fun readEngine(): EnginePreference = try {
        EnginePreference.fromWire(prefs.getString(KEY_ENGINE, null))
    } catch (_: ClassCastException) {
        EnginePreference.AUTO
    }

    companion object {
        private const val FILE = "app_settings"
        private const val KEY_WELCOME = "welcome_done"
        private const val KEY_ENGINE = "engine_choice"

        fun of(context: Context) = AppSettings(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
