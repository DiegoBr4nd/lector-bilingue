package io.github.diegobr4nd.lectorbilingue.engine.opus

import java.util.concurrent.atomic.AtomicInteger

/** Puente falso: "traduce" poniendo el prefijo ES: y registra llamadas y concurrencia. */
class FakeNativeBridge(private val delayMillis: Long = 0) : NativeBridge {
    val loads = mutableListOf<Triple<String, Int, Int>>()
    val unloaded = mutableListOf<Long>()
    val batchSizes = mutableListOf<Int>()
    private val active = AtomicInteger(0)
    @Volatile var maxConcurrent = 0
        private set
    private var nextHandle = 1L

    @Synchronized override fun load(modelDir: String, threads: Int, beamSize: Int): Long {
        loads += Triple(modelDir, threads, beamSize)
        return nextHandle++
    }

    override fun translate(handle: Long, sentences: Array<String>): Array<String> {
        val now = active.incrementAndGet()
        synchronized(this) { maxConcurrent = maxOf(maxConcurrent, now) }
        try {
            if (delayMillis > 0) Thread.sleep(delayMillis)
            synchronized(this) { batchSizes += sentences.size }
            return Array(sentences.size) { "ES:" + sentences[it] }
        } finally {
            active.decrementAndGet()
        }
    }

    @Synchronized override fun unload(handle: Long) {
        unloaded += handle
    }
}
