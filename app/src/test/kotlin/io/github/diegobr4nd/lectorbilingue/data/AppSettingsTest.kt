package io.github.diegobr4nd.lectorbilingue.data

import android.content.SharedPreferences
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SharedPreferences falso en memoria: así la prueba corre sin teléfono. Como el real, lanza
 * ClassCastException si el valor guardado es de otro tipo.
 */
private class MemoryPrefs : SharedPreferences {
    val values = HashMap<String, Any?>()

    override fun getAll(): Map<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = if (key in values) values[key] as String? else defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        @Suppress("UNCHECKED_CAST") (values[key] as? Set<String>) ?: defValues
    override fun getInt(key: String, defValue: Int): Int = if (key in values) values[key] as Int else defValue
    override fun getLong(key: String, defValue: Long): Long = if (key in values) values[key] as Long else defValue
    override fun getFloat(key: String, defValue: Float): Float = if (key in values) values[key] as Float else defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = if (key in values) values[key] as Boolean else defValue
    override fun contains(key: String): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) values.clear()
            for ((k, v) in pending) if (v == null) values.remove(k) else values[k] = v
        }
    }
}

class AppSettingsTest {
    @Test fun `por defecto la bienvenida no esta vista y el motor es automatico`() {
        val settings = AppSettings(MemoryPrefs())
        assertFalse(settings.welcomeDone)
        assertEquals(EnginePreference.AUTO, settings.enginePreference)
    }

    @Test fun `se escribe y se lee la bienvenida vista`() {
        val prefs = MemoryPrefs()
        AppSettings(prefs).welcomeDone = true
        assertTrue(AppSettings(prefs).welcomeDone)
        AppSettings(prefs).welcomeDone = false
        assertFalse(AppSettings(prefs).welcomeDone)
    }

    @Test fun `se escribe y se lee el motor elegido con su valor guardado`() {
        val prefs = MemoryPrefs()
        val settings = AppSettings(prefs)
        settings.enginePreference = EnginePreference.QUALITY
        assertEquals("opus", prefs.values["engine_choice"])
        assertEquals(EnginePreference.QUALITY, AppSettings(prefs).enginePreference)
        settings.enginePreference = EnginePreference.FAST
        assertEquals("firefox", prefs.values["engine_choice"])
        assertEquals(EnginePreference.FAST, AppSettings(prefs).enginePreference)
        settings.enginePreference = EnginePreference.AUTO
        assertEquals("auto", prefs.values["engine_choice"])
        assertEquals(EnginePreference.AUTO, AppSettings(prefs).enginePreference)
    }

    @Test fun `un valor desconocido se lee como automatico`() {
        val prefs = MemoryPrefs()
        prefs.values["engine_choice"] = "nllb"
        assertEquals(EnginePreference.AUTO, AppSettings(prefs).enginePreference)
        prefs.values["engine_choice"] = 3
        assertEquals(EnginePreference.AUTO, AppSettings(prefs).enginePreference)
    }

    @Test fun `un valor de otro tipo en la bienvenida se lee como no vista`() {
        val prefs = MemoryPrefs()
        prefs.values["welcome_done"] = "si"
        assertFalse(AppSettings(prefs).welcomeDone)
    }

    @Test fun `la preferencia se convierte en el motor forzado`() {
        assertNull(EnginePreference.AUTO.toForced())
        assertEquals(EngineId.OPUS, EnginePreference.QUALITY.toForced())
        assertEquals(EngineId.FIREFOX, EnginePreference.FAST.toForced())
    }
}
