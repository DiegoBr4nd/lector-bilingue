package io.github.diegobr4nd.lectorbilingue

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.data.TipId
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Regla de prueba (Ruling C): los consejos vistos de Juan (`tips_seen`) no cambian por correr una prueba.
 *
 * - Al empezar guarda el valor tal cual está en el teléfono (también si no existe).
 * - [allSeen] = true (pruebas que no son de consejos): durante la prueba todos cuentan como vistos. Así ninguna
 *   tarjeta de consejo tapa la barra inferior ni la prueba depende del estado de Juan. Con false la prueba arregla
 *   los consejos a su gusto (TipsOnDeviceTest).
 * - Al terminar, siempre (aunque la prueba falle), devuelve el valor exacto y el estado en memoria de la app.
 */
class KeepTips(private val allSeen: Boolean = true) : ExternalResource() {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LectorApp
    // El mismo archivo que AppSettings.of (Android da la misma instancia para el mismo nombre).
    private val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private var saved: Snapshot? = null

    /** Lo que había: el valor crudo (null = no existía) y lo que la app entendía de él. */
    private class Snapshot(val raw: Any?, val tips: Set<TipId>)

    override fun before() {
        runBlocking { app.settings.loadSeenTips() }
        saved = Snapshot(prefs.all[KEY], app.settings.seenTipsFlow.value)
        if (allSeen) TipId.entries.forEach(app.settings::markTipSeen)
    }

    override fun after() {
        val s = saved ?: return
        saved = null
        // Primero el estado en memoria de la app (por su API) y luego el valor crudo exacto encima.
        app.settings.resetTips()
        s.tips.forEach(app.settings::markTipSeen)
        prefs.edit().apply {
            when (val raw = s.raw) {
                null -> remove(KEY)
                is String -> putString(KEY, raw)
                is Int -> putInt(KEY, raw)
                is Long -> putLong(KEY, raw)
                is Float -> putFloat(KEY, raw)
                is Boolean -> putBoolean(KEY, raw)
                else -> Unit // un conjunto de strings no lo escribe la app: queda lo que dejó markTipSeen
            }
        }.commit()
    }

    private companion object {
        const val FILE = "app_settings"
        const val KEY = "tips_seen"
    }
}
