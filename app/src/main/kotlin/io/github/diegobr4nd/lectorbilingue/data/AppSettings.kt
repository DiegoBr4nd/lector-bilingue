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

    private val readingState = MutableStateFlow(ReadingSettings())
    private val readingLoaded = MutableStateFlow(false)

    /** Los ajustes de lectura, observables: cambian en el mismo instante en que se guardan. */
    val readingSettingsFlow: StateFlow<ReadingSettings> = readingState.asStateFlow()

    /** Lee los ajustes de lectura fuera del hilo principal y los publica. Repetirla no hace nada. */
    suspend fun loadReadingSettings(io: CoroutineDispatcher = Dispatchers.IO) {
        if (readingLoaded.value) return
        val stored = withContext(io) { readReading() }
        // Si mientras tanto alguien guardó otros, esos mandan.
        if (!readingLoaded.value) readingState.value = stored
        readingLoaded.value = true
    }

    var readingSettings: ReadingSettings
        get() = readReading()
        set(value) {
            val clean = value.copy(fontScale = ReadingSettings.normalizeScale(value.fontScale))
            prefs.edit {
                putString(KEY_READING_THEME, clean.theme.wire)
                putFloat(KEY_READING_SCALE, clean.fontScale.toFloat())
                putString(KEY_READING_FONT, clean.font.wire)
                putString(KEY_READING_LINE_HEIGHT, clean.lineHeight.wire)
                putString(KEY_READING_MARGINS, clean.margins.wire)
                putString(KEY_READING_ALIGN, clean.align.wire)
            }
            readingState.value = clean
            readingLoaded.value = true
        }

    // Cada campo se lee por separado: uno corrupto vuelve a su valor de fábrica y los demás se conservan.
    private fun readReading() = ReadingSettings(
        theme = PageTheme.fromWire(safeString(KEY_READING_THEME)),
        fontScale = readScale(),
        font = ReadingFont.fromWire(safeString(KEY_READING_FONT)),
        lineHeight = LineHeightLevel.fromWire(safeString(KEY_READING_LINE_HEIGHT)),
        margins = MarginLevel.fromWire(safeString(KEY_READING_MARGINS)),
        align = TextAlignChoice.fromWire(safeString(KEY_READING_ALIGN)),
    )

    private fun safeString(key: String): String? = try {
        prefs.getString(key, null)
    } catch (_: ClassCastException) {
        null
    }

    private fun readScale(): Double {
        val raw = try {
            prefs.getFloat(KEY_READING_SCALE, 1.0f).toDouble()
        } catch (_: ClassCastException) {
            return 1.0
        }
        // NaN o fuera de rango: dato dañado, vuelve a fábrica (no se acota).
        if (raw.isNaN() || raw < ReadingSettings.MIN_SCALE - 1e-6 || raw > ReadingSettings.MAX_SCALE + 1e-6) return 1.0
        return ReadingSettings.normalizeScale(raw)
    }

    companion object {
        private const val KEY_READING_THEME = "reading_theme"
        private const val KEY_READING_SCALE = "reading_scale"
        private const val KEY_READING_FONT = "reading_font"
        private const val KEY_READING_LINE_HEIGHT = "reading_line_height"
        private const val KEY_READING_MARGINS = "reading_margins"
        private const val KEY_READING_ALIGN = "reading_align"
        private const val FILE = "app_settings"
        private const val KEY_WELCOME = "welcome_done"
        private const val KEY_ENGINE = "engine_choice"

        fun of(context: Context) = AppSettings(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
