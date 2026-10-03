package io.github.diegobr4nd.lectorbilingue

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchText
import io.github.diegobr4nd.lectorbilingue.core.text.SentenceSplitter
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.opus.OpusEngine
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Genera comparacion.json (OPUS beam 1 vs beam 4) para la evaluación a ciegas.
 * Solo corre con `-e calidad 1`. Nunca registra texto: solo números.
 * Misma tubería que EngineTestViewModel (dividir en frases, traducir, unir con espacio).
 */
@RunWith(AndroidJUnit4::class)
class CalidadBeamTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pair = LanguagePair("en", "es")

    @Test fun generaComparacionBeam1YBeam4() = runBlocking<Unit> {
        assumeTrue("pasa -e calidad 1 para correrlo", InstrumentationRegistry.getArguments().getString("calidad") == "1")
        val probe = OpusEngine(File(context.filesDir, "models"))
        assumeTrue("modelo no copiado", probe.isModelPresent(pair))
        assumeTrue("otro motor ya cargado en el proceso", OpusEngine.loadedEngineCount() == 0)

        val privateFile = File(context.filesDir, "bench/textos.txt")
        val source = if (privateFile.isFile) "private" else "substitutes"
        val raw = if (privateFile.isFile) {
            privateFile.readText(Charsets.UTF_8)
        } else {
            context.assets.open("sustitutos.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val paragraphs = BenchText.parse(raw)
        assertTrue(paragraphs.isNotEmpty(), "sin párrafos")

        val results = mutableMapOf<Int, List<Pair<String, Long>>>()
        for (beam in listOf(1, 4)) {
            val engine = OpusEngine(File(context.filesDir, "models"))
            try {
                engine.load(pair, EngineConfig(beamSize = beam, threads = 4))
                translateParagraph(engine, paragraphs.first()) // calentamiento, no se registra
                results[beam] = paragraphs.map { p ->
                    val start = System.nanoTime()
                    val out = translateParagraph(engine, p)
                    out to (System.nanoTime() - start) / 1_000_000
                }
            } finally {
                engine.unload()
            }
        }

        val n = paragraphs.size
        for (beam in listOf(1, 4)) {
            assertEquals(n, results.getValue(beam).size, "resultados beam $beam")
            val blanks = results.getValue(beam).count { it.first.isBlank() }
            assertEquals(0, blanks, "traducciones vacías en beam $beam")
        }

        val array = JSONArray()
        paragraphs.forEachIndexed { i, p ->
            val b1 = results.getValue(1)[i]
            val b4 = results.getValue(4)[i]
            array.put(
                JSONObject()
                    .put("index", i)
                    .put("words", BenchText.countWords(p))
                    .put("beam1", JSONObject().put("text", b1.first).put("ms", b1.second))
                    .put("beam4", JSONObject().put("text", b4.first).put("ms", b4.second)),
            )
        }
        val root = JSONObject()
            .put("version", 1)
            .put("pair", "en-es")
            .put("threads", 4)
            .put("source", source)
            .put("paragraphs", array)

        val dir = File(context.filesDir, "bench").apply { mkdirs() }
        val tmp = File(dir, "comparacion.json.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        Files.move(
            tmp.toPath(),
            File(dir, "comparacion.json").toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }
}

private suspend fun translateParagraph(engine: OpusEngine, text: String): String =
    engine.translate(SentenceSplitter.split(text)).joinToString(" ")
