package io.github.diegobr4nd.lectorbilingue.data

import io.github.diegobr4nd.lectorbilingue.books.db.TranslationDao
import io.github.diegobr4nd.lectorbilingue.books.db.TranslationEntity
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.api.TranslationEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Motor de mentira: traduce "x" como "T(x)", oración por oración, y anota todo lo que le piden.
 * - [gate]: cada `translate` espera a [releaseAll] (para dejar un pedido "en curso").
 * - [failOn]: lanza al llegar a esa oración. [failLoads]: cuántas `load` seguidas lanzan.
 * - [workMillis]: tiempo virtual que tarda cada `translate` (para ver si dos se solapan).
 * - [loadError] / [translateError]: un `Error` (no `Exception`, como UnsatisfiedLinkError) que se lanza una sola vez.
 */
class FakeEngine(
    override val id: String = EngineId.OPUS.wire,
    private val gate: Boolean = false,
    var failOn: String? = null,
    var failLoads: Int = 0,
    private val workMillis: Long = 0,
) : TranslationEngine {
    var loadError: Throwable? = null
    var translateError: Throwable? = null
    var loadCount = 0
        private set
    var unloadCount = 0
        private set
    val loadedPairs = mutableListOf<LanguagePair>()
    val translatedTexts = mutableListOf<String>()
    var maxConcurrent = 0
        private set
    private var current = 0
    private val opened = CompletableDeferred<Unit>()

    /** Abre la compuerta: el `translate` en espera y todos los siguientes siguen. */
    fun releaseAll() {
        opened.complete(Unit)
    }

    override suspend fun load(pair: LanguagePair, config: EngineConfig) {
        loadCount++
        loadError?.let { loadError = null; throw it }
        if (failLoads > 0) {
            failLoads--
            throw IllegalStateException("carga fallida")
        }
        loadedPairs += pair
    }

    override suspend fun translate(sentences: List<String>): List<String> {
        current++
        maxConcurrent = maxOf(maxConcurrent, current)
        try {
            translateError?.let { translateError = null; throw it }
            if (gate) opened.await()
            if (workMillis > 0) delay(workMillis)
            return sentences.map { s ->
                if (s == failOn) throw IllegalStateException("oración fallida")
                translatedTexts += s
                "T($s)"
            }
        } finally {
            current--
        }
    }

    override fun unload() {
        unloadCount++
    }
}

/** El caché de Room en memoria. */
class FakeTranslationDao : TranslationDao {
    val rows = linkedMapOf<String, TranslationEntity>()

    override suspend fun get(key: String): TranslationEntity? = rows[key]
    override suspend fun getAll(keys: List<String>): List<TranslationEntity> = keys.mapNotNull { rows[it] }
    override suspend fun put(row: TranslationEntity) {
        rows[row.key] = row
    }
    override suspend fun count(): Int = rows.size
    override suspend fun approxBytes(): Long = rows.values.sumOf { (it.key.length + it.translation.length) * 2L }
    override suspend fun deleteAll() {
        rows.clear()
    }
}

/** Disco y motores de mentira. [installed] se puede cambiar entre cargas. */
class FakeEngineProvider(
    val opus: FakeEngine = FakeEngine(EngineId.OPUS.wire),
    val firefox: FakeEngine = FakeEngine(EngineId.FIREFOX.wire),
    var installedEngines: Map<EngineId, String> = mapOf(EngineId.OPUS to OPUS_TAG),
    var ramBytes: Long = 8L * 1024 * 1024 * 1024,
    var forcedEngine: EngineId? = null,
    var memoryOk: Boolean = true,
) : EngineProvider {
    var installedCalls = 0
        private set

    override fun installed(pair: LanguagePair): Map<EngineId, String> {
        installedCalls++
        return installedEngines
    }

    override fun engine(id: EngineId): TranslationEngine = when (id) {
        EngineId.OPUS -> opus
        EngineId.FIREFOX -> firefox
    }

    override fun config(id: EngineId): EngineConfig =
        if (id == EngineId.FIREFOX) EngineConfig(threads = 1) else EngineConfig()

    override fun totalRamBytes(): Long = ramBytes
    override fun forced(): EngineId? = forcedEngine
    override fun hasMemoryFor(id: EngineId): Boolean = memoryOk

    companion object {
        const val OPUS_TAG = "opus:opus-en-es:1"
        const val FIREFOX_TAG = "firefox:firefox-en-es:1"
    }
}
