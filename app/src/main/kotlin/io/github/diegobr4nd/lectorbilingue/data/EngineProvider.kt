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
 * Cuándo hay memoria para cargar un motor, con lo que informa Android (`ActivityManager.MemoryInfo`).
 *
 * Solo se niega si el sistema ya está en memoria baja ([lowMemory]) o si [availBytes] no pasa su umbral [thresholdBytes]
 * más [CUSHION_BYTES].
 * "Memoria libre" (`availMem`) no cuenta lo que ocupan las apps de fondo: con muchas abiertas baja a 350-450 MB, pero
 * al cargar el motor Android las cierra para hacer sitio (la app al frente es la última que cierra). En el Pixel 7 con
 * 420 MB libres OPUS cargó y tradujo bien; la regla vieja (450 MB fijos) lo negaba y el Lector quedaba en "No se pudo
 * preparar el traductor" hasta que otras apps se cerraran.
 */
object EngineMemory {
    /**
     * Colchón sobre el umbral de memoria baja del sistema. Es pequeño a propósito: el motor ocupa ~330 MB nativos y,
     * en un teléfono con poca RAM, cargarlo justo en el umbral podría hacer que Android cierre la app misma. Con el
     * colchón, el caso de Juan (376 MB libres, umbral 216 MB) sigue cargando.
     */
    const val CUSHION_BYTES = 100L * 1024 * 1024

    fun canLoad(availBytes: Long, thresholdBytes: Long, lowMemory: Boolean): Boolean =
        !lowMemory && availBytes > thresholdBytes + CUSHION_BYTES
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

    /** Igual para los dos motores (ver [EngineMemory]). */
    override fun hasMemoryFor(id: EngineId): Boolean =
        memoryInfo().let { EngineMemory.canLoad(it.availMem, it.threshold, it.lowMemory) }

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info
    }
}
