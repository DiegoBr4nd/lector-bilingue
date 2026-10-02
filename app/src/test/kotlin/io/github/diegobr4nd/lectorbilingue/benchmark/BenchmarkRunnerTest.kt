package io.github.diegobr4nd.lectorbilingue.benchmark

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkRunnerTest {
    /** Reloj falso: cada traducción "tarda" lo que diga la lista, en ms. */
    private class FakeClock(durationsMs: List<Long>) {
        private val durations = ArrayDeque(durationsMs)
        var now = 0L
        fun advance() { now += durations.removeFirst() * 1_000_000 }
    }

    @Test fun `calienta con el primer parrafo sin contarlo`() = runTest {
        val clock = FakeClock(listOf(9999, 100, 300))
        val calls = mutableListOf<String>()
        val result = BenchmarkRunner { clock.now }.run(listOf("a b", "c d e f")) { calls += it; clock.advance(); "x" }
        assertEquals(listOf("a b", "a b", "c d e f"), calls)
        assertEquals(400, result.totalMillis)
    }

    @Test fun `calcula palabras por segundo mediana y maximo`() = runTest {
        // 3 párrafos de 10 palabras; tiempos medidos 500, 1000, 1500 ms
        val p = "w ".repeat(10).trim()
        val clock = FakeClock(listOf(0, 500, 1000, 1500))
        val r = BenchmarkRunner { clock.now }.run(listOf(p, p, p)) { clock.advance(); "x" }
        assertEquals(3, r.paragraphs)
        assertEquals(30, r.words)
        assertEquals(3000, r.totalMillis)
        assertEquals(10.0, r.wordsPerSecond, 1e-9)
        assertEquals(1000, r.medianMillis)
        assertEquals(1500, r.maxMillis)
        assertFalse(r.meetsSpeedGoal)
        assertTrue(r.meetsLatencyGoal)
    }

    @Test fun `mediana con cantidad par`() = runTest {
        val clock = FakeClock(listOf(0, 100, 200, 300, 400))
        val r = BenchmarkRunner { clock.now }.run(List(4) { "w" }) { clock.advance(); "x" }
        assertEquals(250, r.medianMillis)
    }

    @Test fun `metas cumplidas`() = runTest {
        val p = "w ".repeat(30).trim()
        val clock = FakeClock(listOf(0, 1000, 1000))
        val r = BenchmarkRunner { clock.now }.run(listOf(p, p)) { clock.advance(); "x" }
        assertTrue(r.meetsSpeedGoal)   // 60 palabras / 2 s = 30 palabras/s
        assertTrue(r.meetsLatencyGoal) // mediana 1000 ms
    }

    @Test fun `rechaza lista vacia`() = runTest {
        assertFailsWith<IllegalArgumentException> { BenchmarkRunner().run(emptyList()) { it } }
    }
}
