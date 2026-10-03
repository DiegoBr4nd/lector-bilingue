package io.github.diegobr4nd.lectorbilingue.engine.opus

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.api.TranslationEngine
import io.github.diegobr4nd.lectorbilingue.engine.opus.NativeBridge.Companion.MAX_BATCH
import io.github.diegobr4nd.lectorbilingue.engine.opus.NativeBridge.Companion.MAX_BEAM
import io.github.diegobr4nd.lectorbilingue.engine.opus.NativeBridge.Companion.MAX_SENTENCE_CHARS
import io.github.diegobr4nd.lectorbilingue.engine.opus.NativeBridge.Companion.MAX_THREADS
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Motor OPUS-MT vía CTranslate2. Un solo modelo cargado a la vez y nunca dos
 * llamadas nativas en paralelo: todas pasan por el mismo candado.
 */
class OpusEngine(
    private val modelsRoot: File,
    private val bridge: NativeBridge = Ct2NativeBridge,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TranslationEngine {
    override val id = "opus"

    private val lock = Any()
    private var handle = 0L

    fun modelDir(pair: LanguagePair): File {
        val dir = File(modelsRoot, "${pair.source}-${pair.target}")
        // Defensa extra: la carpeta debe colgar directamente de la raíz de modelos.
        require(dir.canonicalFile.parentFile == modelsRoot.canonicalFile) { "Carpeta de modelo fuera de la raíz" }
        return dir
    }

    fun isModelPresent(pair: LanguagePair) = modelDir(pair).isDirectory

    override suspend fun load(pair: LanguagePair, config: EngineConfig) {
        require(config.threads <= MAX_THREADS) { "threads debe ser <= $MAX_THREADS" }
        require(config.beamSize <= MAX_BEAM) { "beamSize debe ser <= $MAX_BEAM" }
        val dir = modelDir(pair)
        check(dir.isDirectory) { "Modelo no encontrado en ${dir.path}" }
        withContext(dispatcher) {
            synchronized(lock) {
                releaseLocked()
                handle = bridge.load(dir.path, config.threads, config.beamSize)
                if (handle != 0L) loadedCount.incrementAndGet()
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
                sentences.chunked(MAX_BATCH).flatMap { batch ->
                    bridge.translate(handle, batch.toTypedArray()).asList()
                }
            }
        }
    }

    override fun unload() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        if (handle != 0L) {
            bridge.unload(handle)
            handle = 0L
            loadedCount.decrementAndGet()
        }
    }

    companion object {
        private val loadedCount = AtomicInteger(0)

        /** Número de motores con modelo cargado en este proceso; útil para diagnóstico y pruebas. */
        fun loadedEngineCount(): Int = loadedCount.get()
    }
}
