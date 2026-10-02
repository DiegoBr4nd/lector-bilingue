package io.github.diegobr4nd.lectorbilingue.benchmark

data class BenchmarkResult(
    val paragraphs: Int,
    val words: Int,
    val totalMillis: Long,
    val wordsPerSecond: Double,
    val medianMillis: Long,
    val maxMillis: Long,
) {
    val meetsSpeedGoal: Boolean get() = wordsPerSecond >= SPEED_GOAL_WPS
    val meetsLatencyGoal: Boolean get() = medianMillis < LATENCY_GOAL_MS

    companion object {
        const val SPEED_GOAL_WPS = 15.0
        const val LATENCY_GOAL_MS = 2000L
    }
}

/**
 * Mide la traducción párrafo por párrafo. El primer párrafo se traduce una vez
 * antes de medir (calentamiento) y luego se mide junto con los demás.
 */
class BenchmarkRunner(private val nanoClock: () -> Long = System::nanoTime) {
    suspend fun run(paragraphs: List<String>, translate: suspend (String) -> String): BenchmarkResult {
        require(paragraphs.isNotEmpty()) { "No hay párrafos para medir" }
        translate(paragraphs.first())
        val nanos = paragraphs.map { p ->
            val start = nanoClock()
            translate(p)
            nanoClock() - start
        }
        val millis = nanos.map { it / 1_000_000 }.sorted()
        val totalNanos = nanos.sum()
        val words = paragraphs.sumOf(BenchText::countWords)
        val median = if (millis.size % 2 == 1) millis[millis.size / 2]
        else (millis[millis.size / 2 - 1] + millis[millis.size / 2]) / 2
        return BenchmarkResult(
            paragraphs = paragraphs.size,
            words = words,
            totalMillis = totalNanos / 1_000_000,
            wordsPerSecond = if (totalNanos == 0L) 0.0 else words / (totalNanos / 1e9),
            medianMillis = median,
            maxMillis = millis.last(),
        )
    }
}
