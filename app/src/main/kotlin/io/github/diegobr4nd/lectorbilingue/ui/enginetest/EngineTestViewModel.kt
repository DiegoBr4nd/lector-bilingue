package io.github.diegobr4nd.lectorbilingue.ui.enginetest

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchText
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchmarkResult
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchmarkRunner
import io.github.diegobr4nd.lectorbilingue.core.text.SentenceSplitter
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.opus.OpusEngine
import io.github.diegobr4nd.lectorbilingue.models.DownloadState
import io.github.diegobr4nd.lectorbilingue.models.Models
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    /** Tamaño del modelo en-es en MB, si el catálogo ya se conoce. */
    val modelSizeMb: Long? = null,
    /** Hay una operación de modelos en marcha (preparando, descargando o importando). */
    val modelBusy: Boolean = false,
    /** Avance de la descarga 0..1; null mientras no se conoce. Solo tiene sentido con [downloading]. */
    val downloadFraction: Float? = null,
    val downloading: Boolean = false,
    val modelMessage: ModelMessage? = null,
)

/** Temporal (fase 1b): prueba el motor y mide el benchmark. Nunca registra el texto. */
class EngineTestViewModel(application: Application) : AndroidViewModel(application) {
    private val pair = LanguagePair("en", "es")
    private val engine = OpusEngine(File(application.filesDir, "models"))
    private val _state = MutableStateFlow(EngineTestUiState(modelPath = engine.modelDir(pair).path))
    val state: StateFlow<EngineTestUiState> = _state.asStateFlow()

    private fun app(): Application = getApplication()

    private var downloadJob: Job? = null
    private var activeModelId: String? = null

    init {
        viewModelScope.launch {
            // Recuperación del arranque (barata tras la primera vez): limpia restos de instalaciones cortadas.
            runCatching { Models.recover(getApplication()) }
            loadEngine()
        }
        viewModelScope.launch { loadModelSize() }
    }

    /** Carga (o recarga) el motor con el modelo instalado. */
    private suspend fun loadEngine() {
        if (!engine.isModelPresent(pair)) {
            _state.update { it.copy(modelStatus = ModelStatus.MISSING) }
            return
        }
        _state.update { it.copy(modelStatus = ModelStatus.LOADING) }
        runCatching { engine.load(pair, EngineConfig()) }
            .onSuccess { _state.update { it.copy(modelStatus = ModelStatus.READY) } }
            .onFailure { e -> _state.update { it.copy(modelStatus = ModelStatus.ERROR, errorMessage = app().getString(R.string.model_error)) } }
    }

    /** Tamaño del modelo según el catálogo guardado (sin red). Si hay una descarga en curso, la retoma. */
    private suspend fun loadModelSize() {
        val app = getApplication<Application>()
        val model = withContext(Dispatchers.IO) {
            runCatching { Models.catalogRepository(app).current()?.let(ModelActions::pickModel) }.getOrNull()
        } ?: return
        _state.update { it.copy(modelSizeMb = ModelActions.megabytes(model.totalSize)) }
        if (runCatching { Models.isDownloading(app, model.id) }.getOrDefault(false)) {
            _state.update { if (it.modelBusy) it else it.copy(modelBusy = true, downloading = true) }
            activeModelId = model.id
            observeDownload(model.id, null)
        }
    }

    /** Descarga el modelo en-es: catálogo guardado o, si no hay, el de la red; luego encola y observa. */
    fun downloadModel() {
        if (!tryStartModelOperation()) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val (picked, problem) = try {
                withContext(Dispatchers.IO) {
                    val repo = Models.catalogRepository(app)
                    // Sin catálogo guardado y sin red (o sin catálogo publicado aún): un solo mensaje.
                    val catalog = repo.current() ?: try {
                        repo.refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (catalog == null) {
                        null to ModelMessage.NO_CATALOG
                    } else {
                        val m = ModelActions.pickModel(catalog)
                        m to if (m == null) ModelMessage.NO_MODEL else null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to ModelActions.classify(e)
            }
            if (picked == null) {
                _state.update { it.copy(modelBusy = false, modelMessage = problem) }
                return@launch
            }
            _state.update { it.copy(modelSizeMb = ModelActions.megabytes(picked.totalSize), downloading = true) }
            val requestId = try {
                Models.enqueueDownload(app, picked.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(modelBusy = false, downloading = false, modelMessage = ModelMessage.UNKNOWN) }
                return@launch
            }
            activeModelId = picked.id
            observeDownload(picked.id, requestId)
        }
    }

    /** Sigue el progreso por modelo (no por id de petición: con KEEP el id devuelto puede no ser el activo). */
    private fun observeDownload(modelId: String, requestId: UUID?) {
        // Sin requestId (retomada al abrir la app) no hay estados viejos que ignorar.
        var seenActive = requestId == null
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            Models.downloadInfo(getApplication(), modelId).collect { info ->
                if (!ModelActions.acceptDownloadState(info, requestId, seenActive)) return@collect
                if (ModelActions.isActive(info)) seenActive = true
                when (info?.status) {
                    null, DownloadState.Status.QUEUED -> _state.update { it.copy(downloadFraction = null) }
                    DownloadState.Status.RUNNING ->
                        _state.update { it.copy(downloadFraction = ModelActions.fraction(info.bytes, info.total)) }
                    DownloadState.Status.SUCCEEDED -> {
                        _state.update { it.copy(downloading = false, downloadFraction = null) }
                        loadEngine()
                        _state.update { it.copy(modelBusy = false) }
                        downloadJob?.cancel()
                    }
                    DownloadState.Status.FAILED -> {
                        endDownload(ModelActions.fromCode(info.error))
                        downloadJob?.cancel()
                    }
                    DownloadState.Status.CANCELLED -> {
                        endDownload(ModelMessage.CANCELLED)
                        downloadJob?.cancel()
                    }
                }
            }
        }
    }

    /** Cancela la descarga en curso; el estado CANCELLED llega por el observador. */
    fun cancelDownload() {
        val id = activeModelId ?: return
        runCatching { Models.cancelDownload(app(), id) }
    }

    /** Marca "operación de modelos en marcha" de forma atómica; false si ya había una. */
    private fun tryStartModelOperation(): Boolean {
        var started = false
        _state.update {
            started = !it.modelBusy
            if (started) it.copy(modelBusy = true, modelMessage = null, downloadFraction = null) else it
        }
        return started
    }

    private fun endDownload(message: ModelMessage) = _state.update {
        it.copy(modelBusy = false, downloading = false, downloadFraction = null, modelMessage = message)
    }

    /** Importa un modelo desde un .zip que eligió el usuario y recarga el motor. */
    fun importModel(uri: Uri) {
        if (!tryStartModelOperation()) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val problem: ModelMessage? = try {
                val hasCatalog = withContext(Dispatchers.IO) {
                    runCatching { Models.catalogRepository(app).current() }.getOrNull() != null
                }
                if (!hasCatalog) {
                    ModelMessage.NO_CATALOG
                } else {
                    val input = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri) }
                        ?: throw IOException()
                    input.use { Models.importModel(app, it) }
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ModelActions.classifyImport(e)
            }
            if (problem == null) {
                loadEngine()
                _state.update { it.copy(modelBusy = false, modelMessage = ModelMessage.IMPORT_OK) }
            } else {
                _state.update { it.copy(modelBusy = false, modelMessage = problem) }
            }
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
                .onFailure { e -> _state.update { it.copy(errorMessage = app().getString(R.string.error_generic), busy = false) } }
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
            }.onFailure { e -> _state.update { it.copy(errorMessage = app().getString(R.string.error_generic), busy = false) } }
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
        // unload() espera el candado nativo y podría bloquear el hilo principal; se hace
        // fuera de él, en un scope que sobrevive al ViewModel (que ya se está destruyendo).
        CoroutineScope(Dispatchers.Default + NonCancellable).launch { engine.unload() }
    }
}
