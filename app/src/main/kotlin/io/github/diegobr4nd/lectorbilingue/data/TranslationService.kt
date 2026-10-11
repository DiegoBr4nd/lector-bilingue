package io.github.diegobr4nd.lectorbilingue.data

import io.github.diegobr4nd.lectorbilingue.books.db.TranslationDao
import io.github.diegobr4nd.lectorbilingue.books.db.TranslationEntity
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineChoice
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineSelector
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.api.TranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lo que Idiomas necesita del caché de traducciones (una interfaz para poder probar la pantalla sin Room). */
interface TranslationCacheApi {
    suspend fun cacheBytes(): Long
    suspend fun clearCache()
}

enum class Priority { TAP, PREFETCH }

/**
 * El ámbito del servicio: vive lo que el proceso (SupervisorJob: un fallo no lo apaga). Un fallo inesperado se
 * descarta en silencio: sin el manejador iría al del hilo, que cierra la app y deja el mensaje (que podría llevar texto
 * del libro) en el registro.
 */
fun translationScope(worker: CoroutineDispatcher): CoroutineScope =
    CoroutineScope(SupervisorJob() + worker + CoroutineExceptionHandler { _, _ -> })

data class TranslateRequest(val pair: LanguagePair, val text: String, val priority: Priority, val resource: String)

sealed interface TranslateResult {
    data class Done(val translation: String) : TranslateResult
    data class MissingModel(val engine: EngineId) : TranslateResult
    data object EngineFailed : TranslateResult      // no se pudo preparar el motor
    data object ParagraphFailed : TranslateResult   // falló una oración: nada en caché
    data object TooLong : TranslateResult           // más de MAX_PARAGRAPH_CHARS: ni fila ni motor
}

/**
 * Traduce párrafos para toda la app (uno solo, vive en [LectorApp]).
 *
 * - **Fila:** un toque ([Priority.TAP]) sale antes que cualquier pretraducción pendiente; dentro de la misma
 *   prioridad, por orden de llegada. Lo que ya está en curso termina (nunca se corta a mitad) y se guarda.
 * - **Un hilo:** todo el trabajo del motor corre en [worker] (un hilo único) y en un solo bucle: nunca dos
 *   traducciones a la vez.
 * - **Sin duplicados:** un pedido con la misma huella que otro en la fila o en curso espera el resultado de
 *   ese (un `Deferred`, una "promesa" de resultado, por huella).
 * - **Caché:** primero Room; solo se guarda un párrafo completo.
 * - **Tope:** un párrafo de más de [TranslationRules.MAX_PARAGRAPH_CHARS] responde [TranslateResult.TooLong] enseguida.
 * - **Motor perezoso:** se carga con el primer pedido que lo necesita, mirando cada vez lo instalado; se
 *   descarga tras [idleUnloadMillis] sin pedidos o con [release].
 *
 * Nunca registra nada (ni texto, ni traducción, ni huella).
 *
 * Fallos: se atrapa cualquier `Throwable` salvo la cancelación (también un `Error` del código nativo, como
 * UnsatisfiedLinkError u OutOfMemoryError). Si se escapara, el ámbito lo descarta en silencio y el bucle quedaría
 * muerto: todos los toques siguientes esperarían para siempre en "Preparando…".
 *
 * La fila se protege con un candado corto (`synchronized`, sin suspender dentro) porque [prefetch],
 * [cancelPrefetchExcept] y [release] no son `suspend`. El motor cargado ([loaded]) solo cambia en [worker].
 */
class TranslationService(
    private val provider: EngineProvider,
    private val cache: TranslationDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val worker: CoroutineDispatcher,          // un solo hilo
    private val scope: CoroutineScope,                // vive lo que la app
    private val idleUnloadMillis: Long = 120_000,
) : TranslationCacheApi {
    /** Un párrafo en la fila o en curso. [key] usa la etiqueta del modelo que se esperaba usar al pedirlo. */
    private class Entry(
        val key: String,
        val tag: String,
        val pair: LanguagePair,
        val text: String,
        val resource: String,
        var priority: Priority,
    ) {
        val result = CompletableDeferred<TranslateResult>()
    }

    private class Loaded(val pair: LanguagePair, val tag: String, val engine: TranslationEngine)

    /** Qué modelo se usaría para un par, sin cargar nada. */
    private sealed interface Resolution {
        data class Ready(val tag: String) : Resolution
        data class Missing(val result: TranslateResult) : Resolution
    }

    private val lock = Any()
    private val taps = ArrayDeque<Entry>()
    private val prefetches = ArrayDeque<Entry>()
    private val pending = HashMap<String, Entry>()   // huella → pedido en la fila o en curso
    private var running: Entry? = null
    private var loop: Job? = null
    private var idle: Job? = null
    // Cada cancelación de pretraducción sube [prefetchEpoch]: lo pedido antes que aún no entró a la fila y no
    // es de [keepResource] se descarta al llegar.
    private var prefetchEpoch = 0L
    private var keepResource: String? = null

    @Volatile private var loaded: Loaded? = null

    /** Las traducciones ya guardadas de [texts]: texto normalizado → traducción. No carga el motor. */
    suspend fun cached(pair: LanguagePair, texts: List<String>): Map<String, String> {
        val normalized = texts.map(TranslationRules::normalize).filter { it.isNotEmpty() }.distinct()
        if (normalized.isEmpty()) return emptyMap()
        val tag = (resolve(pair) as? Resolution.Ready)?.tag ?: return emptyMap()
        val byKey = normalized.associateBy { TranslationRules.cacheKey(tag, pair, it) }
        return try {
            byKey.keys.chunked(CACHE_CHUNK).flatMap { cache.getAll(it) }
                .associate { byKey.getValue(it.key) to it.translation }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun translate(request: TranslateRequest): TranslateResult =
        submit(request, epoch = Long.MAX_VALUE)!!.await()

    /** Encola [requests] como pretraducción y vuelve enseguida. */
    fun prefetch(requests: List<TranslateRequest>) {
        if (requests.isEmpty()) return
        val epoch = synchronized(lock) { prefetchEpoch }
        scope.launch {
            for (r in requests) submit(r.copy(priority = Priority.PREFETCH), epoch)
        }
    }

    /** Al cambiar de capítulo: saca de la fila la pretraducción de otros recursos (la que está en curso termina). */
    fun cancelPrefetchExcept(resource: String) {
        synchronized(lock) {
            prefetchEpoch++
            keepResource = resource
            val it = prefetches.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.resource != resource) {
                    it.remove()
                    pending.remove(e.key, e)
                    e.result.cancel()
                }
            }
        }
    }

    /** Al salir del Lector: cancela la fila (también lo que está en curso) y descarga el motor. */
    fun release() {
        synchronized(lock) { releaseLocked() }
        scope.launch(worker) { unloadEngine() }
    }

    /** Con el candado tomado. Devuelve el bucle que cancela (o null) para poder esperarlo sin que se escape otro. */
    private fun releaseLocked(): Job? {
        prefetchEpoch++
        keepResource = null
        (taps + prefetches).forEach { it.result.cancel() }
        running?.result?.cancel()
        taps.clear()
        prefetches.clear()
        pending.clear()
        running = null
        val cancelled = loop
        cancelled?.cancel()
        loop = null
        idle?.cancel()
        idle = null
        return cancelled
    }

    /** Tamaño aproximado de las traducciones guardadas, en bytes. */
    override suspend fun cacheBytes(): Long = cache.approxBytes()

    /**
     * Borra todas las traducciones guardadas. Primero [release] (vacía la fila y cancela lo que está en curso) y se
     * espera a que el bucle termine de verdad: así nada en curso puede guardar una fila justo después de borrar.
     *
     * Privacidad: `secure_delete` (ver `LectorDatabase.open`) pone en ceros lo borrado, pero las copias de las páginas
     * con el texto viejo siguen en el WAL ("lector.db-wal") hasta un checkpoint. Por eso, tras borrar, se hace un
     * checkpoint que vacía el WAL: después el texto no queda ni en el .db ni en el -wal. Room lo corre fuera del hilo
     * principal.
     */
    override suspend fun clearCache() {
        val cancelled = synchronized(lock) { releaseLocked() }
        scope.launch(worker) { unloadEngine() }
        // Aunque se salga de Idiomas a mitad, el borrado termina: si no, quedaría a medias.
        withContext(NonCancellable) {
            cancelled?.join()
            cache.deleteAll()
            cache.checkpointWal()
        }
    }

    /** true si hay un motor cargado para [pair] (la tarjeta muestra "Traduciendo…" en vez de "Preparando el traductor…"). */
    fun engineReady(pair: LanguagePair): Boolean = loaded?.pair == pair

    /**
     * Pone el pedido en la fila (o lo une a uno igual) y devuelve su resultado futuro. null = pretraducción
     * descartada porque se canceló antes de llegar.
     */
    private suspend fun submit(request: TranslateRequest, epoch: Long): Deferred<TranslateResult>? {
        val text = TranslationRules.normalize(request.text)
        if (text.isEmpty()) return CompletableDeferred(TranslateResult.Done(""))
        // Antes de mirar lo instalado: el párrafo enorme no ocupa ni el hilo del motor.
        if (TranslationRules.tooLong(text)) return CompletableDeferred(TranslateResult.TooLong)
        val tag = when (val r = resolve(request.pair)) {
            is Resolution.Ready -> r.tag
            is Resolution.Missing -> return CompletableDeferred(r.result)
        }
        val key = TranslationRules.cacheKey(tag, request.pair, text)
        synchronized(lock) {
            touchLocked()
            joinLocked(key, request.priority)?.let { return it }
        }
        stored(key)?.let { return CompletableDeferred(TranslateResult.Done(it)) }
        synchronized(lock) {
            joinLocked(key, request.priority)?.let { return it }
            if (request.priority == Priority.PREFETCH && epoch < prefetchEpoch && request.resource != keepResource) {
                return null
            }
            val e = Entry(key, tag, request.pair, text, request.resource, request.priority)
            pending[key] = e
            if (e.priority == Priority.TAP) taps.addLast(e) else prefetches.addLast(e)
            startLoopLocked()
            return e.result
        }
    }

    /** Un pedido igual ya en la fila o en curso. Si espera como pretraducción y llega un toque, pasa a la fila de toques. */
    private fun joinLocked(key: String, priority: Priority): Deferred<TranslateResult>? {
        val e = pending[key] ?: return null
        if (priority == Priority.TAP && e.priority == Priority.PREFETCH && prefetches.remove(e)) {
            e.priority = Priority.TAP
            taps.addLast(e)
        }
        return e.result
    }

    /** El modelo que se usaría: el cargado si es del par; si no, se mira lo instalado (en [worker]: lee disco). */
    private suspend fun resolve(pair: LanguagePair): Resolution {
        loaded?.takeIf { it.pair == pair }?.let { return Resolution.Ready(it.tag) }
        return withContext(worker) {
            try {
                val installed = provider.installed(pair)
                when (val c = EngineSelector.choose(installed.keys, provider.totalRamBytes(), provider.forced())) {
                    is EngineChoice.Missing -> Resolution.Missing(TranslateResult.MissingModel(c.engine))
                    is EngineChoice.Use -> Resolution.Ready(installed.getValue(c.engine))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Resolution.Missing(TranslateResult.EngineFailed)
            }
        }
    }

    private suspend fun stored(key: String): String? = try {
        cache.get(key)?.translation
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null // un caché que falla no impide traducir
    }

    private fun startLoopLocked() {
        idle?.cancel()
        idle = null
        if (loop != null) return
        // Se asigna con el candado tomado: el bucle no puede mirar [loop] antes de esta asignación.
        loop = scope.launch(worker) { runLoop() }
    }

    /** El único bucle que usa el motor. Termina cuando la fila queda vacía (y arranca la cuenta de inactividad). */
    private suspend fun runLoop() {
        val me = currentCoroutineContext()[Job]
        try {
            loopEntries(me)
        } finally {
            // Si el bucle terminara por algo inesperado, el siguiente pedido arranca otro.
            synchronized(lock) {
                if (loop === me) {
                    loop = null
                    running = null
                }
            }
        }
    }

    private suspend fun loopEntries(me: Job?) {
        while (true) {
            val entry = synchronized(lock) {
                // Un bucle cancelado por release() no toma trabajo del bucle nuevo.
                if (loop !== me) return
                val next = taps.removeFirstOrNull() ?: prefetches.removeFirstOrNull()
                running = next
                if (next == null) {
                    loop = null
                    scheduleIdleLocked()
                }
                next
            } ?: return
            val result = try {
                process(entry)
            } catch (e: CancellationException) {
                entry.result.cancel()
                throw e
            } catch (e: Throwable) {
                TranslateResult.EngineFailed // última barrera (p. ej. un Error del caché)
            }
            entry.result.complete(result)
            synchronized(lock) {
                pending.remove(entry.key, entry)
                if (running === entry) running = null
            }
        }
    }

    /** En [worker]. */
    private suspend fun process(entry: Entry): TranslateResult {
        // Pudo guardarse mientras esperaba (otro pedido igual que llegó justo al terminar el primero).
        stored(entry.key)?.let { return TranslateResult.Done(it) }
        val engine = when (val r = ensureEngine(entry.pair)) {
            is EngineOrResult.Ready -> r.loaded
            is EngineOrResult.Failed -> return r.result
        }
        val translation = try {
            TranslationRules.join(TranslationRules.batches(entry.text).flatMap { engine.engine.translate(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return TranslateResult.ParagraphFailed
        }
        val key = if (engine.tag == entry.tag) entry.key else TranslationRules.cacheKey(engine.tag, entry.pair, entry.text)
        try {
            cache.put(TranslationEntity(key, translation, clock()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sin caché la traducción sirve igual; se volverá a traducir la próxima vez.
        }
        return TranslateResult.Done(translation)
    }

    private sealed interface EngineOrResult {
        class Ready(val loaded: Loaded) : EngineOrResult
        class Failed(val result: TranslateResult) : EngineOrResult
    }

    /** En [worker]. Carga el motor para [pair] si no está; cada carga vuelve a mirar lo instalado. */
    private suspend fun ensureEngine(pair: LanguagePair): EngineOrResult {
        loaded?.takeIf { it.pair == pair }?.let { return EngineOrResult.Ready(it) }
        unloadEngine() // otro par u otro motor: se libera antes de mirar la memoria
        return try {
            val installed = provider.installed(pair)
            when (val c = EngineSelector.choose(installed.keys, provider.totalRamBytes(), provider.forced())) {
                is EngineChoice.Missing -> EngineOrResult.Failed(TranslateResult.MissingModel(c.engine))
                is EngineChoice.Use -> {
                    if (!provider.hasMemoryFor(c.engine)) return EngineOrResult.Failed(TranslateResult.EngineFailed)
                    val engine = provider.engine(c.engine)
                    try {
                        engine.load(pair, provider.config(c.engine))
                    } catch (e: Throwable) {
                        runCatching { engine.unload() } // pudo quedar cargado a medias
                        throw e
                    }
                    val l = Loaded(pair, installed.getValue(c.engine), engine)
                    loaded = l
                    EngineOrResult.Ready(l)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            EngineOrResult.Failed(TranslateResult.EngineFailed)
        }
    }

    /** En [worker]. */
    private fun unloadEngine() {
        val l = loaded ?: return
        loaded = null
        l.engine.unload()
    }

    /** Hubo un pedido: si el bucle no corre, la cuenta de inactividad vuelve a empezar. */
    private fun touchLocked() {
        if (loop == null && loaded != null) scheduleIdleLocked()
    }

    private fun scheduleIdleLocked() {
        idle?.cancel()
        idle = scope.launch(worker) {
            delay(idleUnloadMillis)
            val busy = synchronized(lock) { loop != null }
            if (!busy) unloadEngine()
        }
    }

    private companion object {
        /** SQLite admite 999 parámetros por consulta. */
        const val CACHE_CHUNK = 500
    }
}
