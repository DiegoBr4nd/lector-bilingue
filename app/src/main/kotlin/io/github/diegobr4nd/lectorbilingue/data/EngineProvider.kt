package io.github.diegobr4nd.lectorbilingue.data

import android.app.ActivityManager
import io.github.diegobr4nd.lectorbilingue.LectorApp
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.api.TranslationEngine
import io.github.diegobr4nd.lectorbilingue.engine.firefox.FirefoxEngine
import io.github.diegobr4nd.lectorbilingue.engine.opus.OpusEngine
import io.github.diegobr4nd.lectorbilingue.models.Models
import kotlinx.coroutines.CoroutineDispatcher

/** Lo que el servicio necesita del disco y de los motores (falso en las pruebas). */
interface EngineProvider {
    /** Motores instalados para el par y su etiqueta de modelo ("opus:<id>:<versión>"). Lee disco: llamar fuera del hilo principal. */
    fun installed(pair: LanguagePair): Map<EngineId, String>
    fun engine(id: EngineId): TranslationEngine
    fun config(id: EngineId): EngineConfig
    fun totalRamBytes(): Long
    fun forced(): EngineId?
    fun hasMemoryFor(id: EngineId): Boolean
}

/**
 * Las piezas reales. Los motores usan [worker] (el mismo hilo único del servicio) como despachador: así su
 * `synchronized` nunca bloquea un hilo compartido de `Dispatchers.Default` y queda como protección barata.
 */
class AndroidEngineProvider(private val app: LectorApp, private val worker: CoroutineDispatcher) : EngineProvider {
    private val opus by lazy {
        OpusEngine({ p -> Models.installedDir(app, EngineId.OPUS.wire, TranslationRules.wire(p)) }, dispatcher = worker)
    }
    private val firefox by lazy {
        FirefoxEngine({ p -> Models.installedDir(app, EngineId.FIREFOX.wire, TranslationRules.wire(p)) }, dispatcher = worker)
    }

    override fun installed(pair: LanguagePair): Map<EngineId, String> {
        val wire = TranslationRules.wire(pair)
        return Models.store(app).installed()
            .filter { it.pair == wire }
            .mapNotNull { m -> EngineId.fromWire(m.engine)?.let { it to "${m.engine}:${m.id}:${m.modelVersion}" } }
            .toMap()
    }

    override fun engine(id: EngineId): TranslationEngine = when (id) {
        EngineId.OPUS -> opus
        EngineId.FIREFOX -> firefox
    }

    // Beam 1 siempre; OPUS con 4 hilos, Firefox con 1 (como EngineTestViewModel.configOf).
    override fun config(id: EngineId): EngineConfig = when (id) {
        EngineId.OPUS -> EngineConfig()
        EngineId.FIREFOX -> EngineConfig(threads = 1)
    }

    override fun totalRamBytes(): Long = memoryInfo().totalMem

    /** El motor elegido en Idiomas (ver [AppSettings.enginePreference]); null = que decida la RAM. */
    override fun forced(): EngineId? = app.settings.enginePreference.toForced()

    override fun hasMemoryFor(id: EngineId): Boolean = memoryInfo().availMem >= when (id) {
        EngineId.OPUS -> OPUS_MIN_FREE_BYTES
        EngineId.FIREFOX -> FIREFOX_MIN_FREE_BYTES
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info
    }

    private companion object {
        // OPUS: ~330 MB medidos en la 2a cargado y traduciendo, más margen.
        const val OPUS_MIN_FREE_BYTES = 450L * 1024 * 1024
        // Firefox: modelo más chico y un solo hilo; mismo criterio de margen.
        const val FIREFOX_MIN_FREE_BYTES = 200L * 1024 * 1024
    }
}
