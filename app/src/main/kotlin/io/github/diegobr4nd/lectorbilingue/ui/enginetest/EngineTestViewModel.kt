package io.github.diegobr4nd.lectorbilingue.ui.enginetest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchText
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchmarkResult
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchmarkRunner
import io.github.diegobr4nd.lectorbilingue.core.text.SentenceSplitter
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.opus.OpusEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ModelStatus { LOADING, READY, MISSING, ERROR }
enum class BenchSource { PRIVATE, SUBSTITUTES }

data class EngineTestUiState(
    val modelStatus: ModelStatus = ModelStatus.LOADING,
    val modelPath: String = "",
    val input: String = "",
    val output: String = "",
    val lastMillis: Long? = null,
    val busy: Boolean = false,
    val benchmark: BenchmarkResult? = null,
    val benchSource: BenchSource? = null,
    val errorMessage: String? = null,
)

/** Temporal (fase 1b): prueba el motor y mide el benchmark. Nunca registra el texto. */
class EngineTestViewModel(application: Application) : AndroidViewModel(application) {
    private val pair = LanguagePair("en", "es")
    private val engine = OpusEngine(File(application.filesDir, "models"))
    private val _state = MutableStateFlow(EngineTestUiState(modelPath = engine.modelDir(pair).path))
    val state: StateFlow<EngineTestUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (!engine.isModelPresent(pair)) {
                _state.update { it.copy(modelStatus = ModelStatus.MISSING) }
                return@launch
            }
            runCatching { engine.load(pair, EngineConfig()) }
                .onSuccess { _state.update { it.copy(modelStatus = ModelStatus.READY) } }
                .onFailure { e -> _state.update { it.copy(modelStatus = ModelStatus.ERROR, errorMessage = e.message) } }
        }
    }

    fun onInputChange(text: String) = _state.update { it.copy(input = text) }

    fun translate() {
        val text = _state.value.input
        if (text.isBlank() || _state.value.busy) return
        _state.update { it.copy(busy = true, errorMessage = null) }
        viewModelScope.launch {
            val start = System.nanoTime()
            runCatching { translateParagraph(text) }
                .onSuccess { out ->
                    val ms = (System.nanoTime() - start) / 1_000_000
                    _state.update { it.copy(output = out, lastMillis = ms, busy = false) }
                }
                .onFailure { e -> _state.update { it.copy(errorMessage = e.message, busy = false) } }
        }
    }

    fun runBenchmark() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, errorMessage = null, benchmark = null) }
        viewModelScope.launch {
            runCatching {
                val (source, paragraphs) = loadBenchParagraphs()
                source to BenchmarkRunner().run(paragraphs, ::translateParagraph)
            }.onSuccess { (source, result) ->
                _state.update { it.copy(benchmark = result, benchSource = source, busy = false) }
            }.onFailure { e -> _state.update { it.copy(errorMessage = e.message, busy = false) } }
        }
    }

    private suspend fun translateParagraph(paragraph: String): String =
        engine.translate(SentenceSplitter.split(paragraph)).joinToString(" ")

    private suspend fun loadBenchParagraphs(): Pair<BenchSource, List<String>> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val private = File(app.filesDir, "bench/textos.txt")
        if (private.isFile) {
            BenchSource.PRIVATE to BenchText.parse(private.readText(Charsets.UTF_8))
        } else {
            BenchSource.SUBSTITUTES to BenchText.parse(
                app.assets.open("sustitutos.txt").bufferedReader(Charsets.UTF_8).use { it.readText() },
            )
        }
    }

    override fun onCleared() {
        engine.unload()
    }
}
