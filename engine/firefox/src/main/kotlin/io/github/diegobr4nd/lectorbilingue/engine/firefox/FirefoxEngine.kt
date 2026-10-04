package io.github.diegobr4nd.lectorbilingue.engine.firefox

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.api.ModelNotInstalledException
import io.github.diegobr4nd.lectorbilingue.engine.api.TranslationEngine
import io.github.diegobr4nd.lectorbilingue.engine.firefox.NativeBridge.Companion.MAX_BATCH
import io.github.diegobr4nd.lectorbilingue.engine.firefox.NativeBridge.Companion.MAX_SENTENCE_CHARS
import io.github.diegobr4nd.lectorbilingue.engine.firefox.NativeBridge.Companion.MAX_THREADS
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Motor de los modelos de Firefox vía slimt. Un solo modelo cargado a la vez y nunca dos
 * llamadas nativas en paralelo: todas pasan por el mismo candado.
 *
 * - slimt solo hace búsqueda voraz: [EngineConfig.beamSize] se valida (>= 1) pero se ignora.
 * - [EngineConfig.threads] va de 1 a [MAX_THREADS]; la app usa 1 (más hilos casi no ayudan).
 * - slimt arma su lista corta en cada llamada, así que mezclar párrafos distintos puede cambiar
 *   el resultado. Este motor nunca une llamadas separadas; quien llame debe pasar las oraciones
 *   de UN párrafo por llamada. Dentro de una llamada solo parte en lotes de [MAX_BATCH].
 */
class FirefoxEngine(
    private val modelDir: (LanguagePair) -> File?,
    private val bridge: NativeBridge = SlimtNativeBridge,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TranslationEngine {
    override val id = EngineId.FIREFOX.wire

    private val lock = Any()
    private var handle = 0L

    override suspend fun load(pair: LanguagePair, config: EngineConfig) {
        require(config.threads in 1..MAX_THREADS) { "threads debe estar entre 1 y $MAX_THREADS" }
        require(config.beamSize >= 1) { "beamSize debe ser >= 1" }
        // El proveedor lee el disco: se llama en el despachador del motor, nunca en el hilo del llamador.
        withContext(dispatcher) {
            val dir = modelDir(pair) ?: throw ModelNotInstalledException(EngineId.FIREFOX, pair)
            check(dir.isDirectory) { "carpeta de modelo no válida" }
            synchronized(lock) {
                releaseLocked()
                val newHandle = bridge.load(dir.path, config.threads)
                check(newHandle != 0L) { "el puente no devolvió un modelo" }
                handle = newHandle
                loadedCount.incrementAndGet()
            }
        }
    }

    override suspend fun translate(sentences: List<String>): List<String> {
        if (sentences.isEmpty()) return emptyList()
        sentences.forEachIndexed { index, s ->
            require(s.length <= MAX_SENTENCE_CHARS) {
                "La oración $index tiene ${s.length} caracteres (máximo $MAX_SENTENCE_CHARS)"
            }
        }
        return withContext(dispatcher) {
            synchronized(lock) {
                check(handle != 0L) { "Motor no cargado" }
                val out = ArrayList<String>(sentences.size)
                for (batch in sentences.chunked(MAX_BATCH)) {
                    val result = bridge.translate(handle, batch.toTypedArray())
                    check(result.size == batch.size) { "el puente devolvió un número inesperado de oraciones" }
                    out.addAll(result)
                }
                out
            }
        }
    }

    override fun unload() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        if (handle != 0L) {
            bridge.release(handle)
            handle = 0L
            loadedCount.decrementAndGet()
        }
    }

    companion object {
        private val loadedCount = AtomicInteger(0)

        /** Número de motores Firefox con modelo cargado en este proceso; útil para diagnóstico y pruebas. */
        fun loadedEngineCount(): Int = loadedCount.get()
    }
}
