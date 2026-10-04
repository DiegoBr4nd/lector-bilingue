package io.github.diegobr4nd.lectorbilingue.ui.enginetest

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchText
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchmarkResult
import io.github.diegobr4nd.lectorbilingue.benchmark.BenchmarkRunner
import io.github.diegobr4nd.lectorbilingue.core.text.SentenceSplitter
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineConfig
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.engine.api.ModelNotInstalledException
import io.github.diegobr4nd.lectorbilingue.engine.api.Reason
import io.github.diegobr4nd.lectorbilingue.engine.api.TranslationEngine
import io.github.diegobr4nd.lectorbilingue.engine.firefox.FirefoxEngine
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ModelStatus { LOADING, READY, MISSING, ERROR }
enum class BenchSource { PRIVATE, SUBSTITUTES }

/** Qué hace la app mientras hay una operación de modelos y aún no hay descarga en marcha. */
enum class ModelPhase { NONE, LOOKING_UP_CATALOG, IMPORTING, DELETING }

/** Una fila de la lista de modelos del catálogo para el par elegido. */
data class ModelRow(val id: String, val engine: EngineId, val sizeMb: Long, val installed: Boolean)

/** Carga fallida en Automático: se ofrece, con un botón, probar con el otro motor instalado. */
data class LoadOffer(val failed: EngineId, val alternative: EngineId)

data class EngineTestUiState(
    val modelStatus: ModelStatus = ModelStatus.LOADING,
    val engineSwitch: EngineSwitch = EngineSwitch.AUTO,
    val pair: PairChoice = PairChoice.EN_ES,
    val totalRamGb: Long = 0,
    /** Motor cargado (o que se está cargando) y por qué se eligió. */
    val engineInUse: EngineId? = null,
    val reason: Reason? = null,
    /** Motor cuyo modelo falta para el par elegido (estado MISSING). */
    val missingEngine: EngineId? = null,
    val loadOffer: LoadOffer? = null,
    val models: List<ModelRow> = emptyList(),
    /** Id del modelo cuya descarga está en marcha (la fila que muestra el avance). */
    val activeModelId: String? = null,
    val input: String = "",
    val output: String = "",
    val lastMillis: Long? = null,
    val busy: Boolean = false,
    val benchmark: BenchmarkResult? = null,
    val benchSource: BenchSource? = null,
    /** El par elegido no tiene textos de prueba (es → en sin textos-es.txt). */
    val benchNoTexts: Boolean = false,
    val errorMessage: String? = null,
    /** Hay una operación de modelos en marcha (preparando, descargando, importando o borrando). */
    val modelBusy: Boolean = false,
    /** Avance de la descarga 0..1; null mientras no se conoce. Solo tiene sentido con [downloading]. */
    val downloadFraction: Float? = null,
    val downloading: Boolean = false,
    /** Fase previa a la descarga (buscar catálogo), importación o borrado; solo con [modelBusy] y sin [downloading]. */
    val phase: ModelPhase = ModelPhase.NONE,
    /** La descarga está encolada, esperando conexión (aún no corre). */
    val downloadQueued: Boolean = false,
    /** Se tocó "Cancelar" y aún no llega el estado final. */
    val cancelling: Boolean = false,
    val modelMessage: ModelMessage? = null,
)

/** RAM total del teléfono en bytes (`ActivityManager.MemoryInfo.totalMem`). */
private fun totalRam(context: Context): Long {
    val info = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
    return info.totalMem
}

/** Temporal (fase 1b): prueba el motor y mide el benchmark. Nunca registra el texto. */
class EngineTestViewModel(application: Application) : AndroidViewModel(application) {
    // Fase 2c: los motores solo cargan lo que el gestor de modelos instaló (lee el disco en su propio hilo).
    private fun dirOf(engine: EngineId): (LanguagePair) -> File? =
        { p -> Models.installedDir(app(), engine.wire, "${p.source}-${p.target}") }

    private val opus = OpusEngine(dirOf(EngineId.OPUS))
    private val firefox = FirefoxEngine(dirOf(EngineId.FIREFOX))

    /** Solo un motor cargado a la vez; el candado serializa cargar y descargar. */
    private val engineLock = Mutex()
    @Volatile private var loaded: TranslationEngine? = null

    private val totalRamBytes = totalRam(application)
    private val _state = MutableStateFlow(EngineTestUiState(totalRamGb = EnginePicker.ramGb(totalRamBytes)))
    val state: StateFlow<EngineTestUiState> = _state.asStateFlow()

    private fun app(): Application = getApplication()

    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            // Recuperación del arranque (barata tras la primera vez): limpia restos de instalaciones cortadas.
            runCatching { Models.recover(getApplication()) }
            refreshModels()
            reloadEngine()
        }
    }

    private fun engineOf(id: EngineId): TranslationEngine = if (id == EngineId.OPUS) opus else firefox

    // Firefox: 1 hilo (con 4 apenas mejora); OPUS conserva su configuración.
    private fun configOf(id: EngineId) = if (id == EngineId.FIREFOX) EngineConfig(threads = 1) else EngineConfig()

    private fun installedEngines(pair: PairChoice): Set<EngineId> =
        EngineId.entries.filterTo(mutableSetOf()) {
            runCatching { Models.installedDir(app(), it.wire, pair.wire) != null }.getOrDefault(false)
        }

    /** Descarga el motor cargado (si hay), fuera del hilo principal: unload() espera el candado nativo. */
    private suspend fun unloadLoaded() = engineLock.withLock { unloadLocked() }

    private suspend fun unloadLocked() {
        val e = loaded ?: return
        loaded = null
        withContext(Dispatchers.Default) { e.unload() }
    }

    /** Descarga el motor anterior y carga el que corresponde al interruptor y al par (o avisa qué falta). */
    private suspend fun reloadEngine() {
        engineLock.withLock {
            _state.update {
                it.copy(
                    modelStatus = ModelStatus.LOADING, engineInUse = null, reason = null, missingEngine = null,
                    loadOffer = null, errorMessage = null,
                )
            }
            unloadLocked()
            val s = _state.value
            val installed = withContext(Dispatchers.IO) { installedEngines(s.pair) }
            when (val plan = EnginePicker.plan(s.engineSwitch, installed, totalRamBytes)) {
                is EnginePlan.Download ->
                    _state.update { it.copy(modelStatus = ModelStatus.MISSING, missingEngine = plan.engine) }
                is EnginePlan.Load -> {
                    _state.update { it.copy(engineInUse = plan.engine, reason = plan.reason) }
                    val engine = engineOf(plan.engine)
                    runCatching { engine.load(s.pair.pair, configOf(plan.engine)) }
                        .onSuccess {
                            loaded = engine
                            _state.update { it.copy(modelStatus = ModelStatus.READY) }
                        }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            if (e is ModelNotInstalledException) {
                                _state.update {
                                    it.copy(
                                        modelStatus = ModelStatus.MISSING, engineInUse = null, reason = null,
                                        missingEngine = plan.engine,
                                    )
                                }
                            } else {
                                val alt = EnginePicker.fallbackOffer(s.engineSwitch, plan.engine, installed)
                                _state.update {
                                    it.copy(
                                        modelStatus = ModelStatus.ERROR,
                                        errorMessage = app().getString(R.string.model_error),
                                        loadOffer = alt?.let { a -> LoadOffer(plan.engine, a) },
                                    )
                                }
                            }
                        }
                }
            }
        }
    }

    fun selectEngine(choice: EngineSwitch) {
        val s = _state.value
        if (s.busy || s.engineSwitch == choice) return
        _state.update { it.copy(engineSwitch = choice) }
        viewModelScope.launch { reloadEngine() }
    }

    fun selectPair(choice: PairChoice) {
        val s = _state.value
        if (s.busy || s.pair == choice) return
        _state.update {
            it.copy(pair = choice, output = "", lastMillis = null, benchmark = null, benchNoTexts = false, modelMessage = null)
        }
        viewModelScope.launch {
            refreshModels()
            reloadEngine()
        }
    }

    /** Lista de modelos del catálogo guardado (sin red) para el par elegido; retoma una descarga en curso. */
    private suspend fun refreshModels() {
        val app = getApplication<Application>()
        val pairWire = _state.value.pair.wire
        val (rows, resumeId) = withContext(Dispatchers.IO) {
            val catalog = runCatching { Models.catalogRepository(app).current() }.getOrNull()
            val installed = runCatching { Models.store(app).installed() }.getOrDefault(emptyList())
            val models = catalog?.let { ModelActions.pickModels(it, pairWire) }.orEmpty()
            val rows = models.mapNotNull { m ->
                EngineId.fromWire(m.engine)?.let { e ->
                    ModelRow(
                        m.id, e, ModelActions.megabytes(m.totalSize),
                        installed.any { it.engine == m.engine && it.pair == pairWire },
                    )
                }
            }
            val active = models.firstOrNull { runCatching { Models.isDownloading(app, it.id) }.getOrDefault(false) }
            rows to active?.id
        }
        _state.update { it.copy(models = rows) }
        if (resumeId != null && !_state.value.modelBusy) {
            _state.update { it.copy(activeModelId = resumeId, modelBusy = true, downloading = true) }
            observeDownload(resumeId, null)
        }
    }

    /** Descarga el modelo [modelId]: refresca el catálogo de la red (si puede), usa el más nuevo y luego encola y observa. */
    fun downloadModel(modelId: String) {
        if (!tryStartModelOperation(ModelPhase.LOOKING_UP_CATALOG)) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val (picked, problem) = try {
                withContext(Dispatchers.IO) {
                    val repo = Models.catalogRepository(app)
                    // Primero el refresco (mejor esfuerzo: así llegan catálogos nuevos aunque el APK traiga uno),
                    // luego el catálogo guardado, que tras un refresco válido ya es el más nuevo.
                    val refreshError = try {
                        repo.refresh()
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e
                    }
                    val (catalog, catalogProblem) = ModelActions.resolveCatalog(repo.current(), refreshError)
                    if (catalog == null) {
                        null to catalogProblem
                    } else {
                        val m = catalog.models.firstOrNull { it.id == modelId }
                        m to if (m == null) ModelMessage.NO_MODEL else null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to ModelActions.classify(e)
            }
            if (picked == null) {
                _state.update { it.copy(modelBusy = false, phase = ModelPhase.NONE, modelMessage = problem) }
                return@launch
            }
            // El id activo va ANTES de mostrar el botón Cancelar: así un toque siempre encuentra a qué cancelar.
            _state.update { it.copy(activeModelId = picked.id, downloading = true, phase = ModelPhase.NONE) }
            val requestId = try {
                Models.enqueueDownload(app, picked.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        modelBusy = false, downloading = false, cancelling = false, activeModelId = null,
                        modelMessage = ModelMessage.UNKNOWN,
                    )
                }
                return@launch
            }
            // Si ya tocaron Cancelar mientras se encolaba, esa cancelación no tenía qué cancelar todavía.
            if (_state.value.cancelling) runCatching { Models.cancelDownload(app, picked.id) }
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
                    null -> _state.update { it.copy(downloadFraction = null) }
                    DownloadState.Status.QUEUED ->
                        _state.update { it.copy(downloadFraction = null, downloadQueued = true) }
                    DownloadState.Status.RUNNING ->
                        _state.update {
                            it.copy(downloadFraction = ModelActions.fraction(info.bytes, info.total), downloadQueued = false)
                        }
                    DownloadState.Status.SUCCEEDED -> {
                        _state.update {
                            it.copy(
                                downloading = false, downloadFraction = null, downloadQueued = false, cancelling = false,
                                activeModelId = null,
                            )
                        }
                        refreshModels()
                        reloadEngine()
                        _state.update {
                            it.copy(
                                modelBusy = false,
                                modelMessage = if (it.modelStatus == ModelStatus.READY) ModelActions.finalMessage(info) else null,
                            )
                        }
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
        val id = _state.value.activeModelId ?: return
        if (_state.value.cancelling) return
        _state.update { it.copy(cancelling = true) }
        if (runCatching { Models.cancelDownload(app(), id) }.isFailure) {
            _state.update { it.copy(cancelling = false) }
        }
    }

    /** Marca "operación de modelos en marcha" de forma atómica; false si ya había una. */
    private fun tryStartModelOperation(phase: ModelPhase): Boolean {
        var started = false
        _state.update {
            started = !it.modelBusy
            if (started) {
                it.copy(modelBusy = true, modelMessage = null, downloadFraction = null, phase = phase, cancelling = false)
            } else {
                it
            }
        }
        return started
    }

    private fun endDownload(message: ModelMessage) = _state.update {
        it.copy(
            modelBusy = false, downloading = false, downloadFraction = null, downloadQueued = false,
            cancelling = false, phase = ModelPhase.NONE, modelMessage = message, activeModelId = null,
        )
    }

    /** Importa un modelo desde un .zip que eligió el usuario y recarga el motor. */
    fun importModel(uri: Uri) {
        if (!tryStartModelOperation(ModelPhase.IMPORTING)) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val problem: ModelMessage? = try {
                val hasCatalog = withContext(Dispatchers.IO) {
                    runCatching { Models.catalogRepository(app).current() }.getOrNull() != null
                }
                if (!hasCatalog) {
                    ModelMessage.NO_CATALOG_IMPORT
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
                refreshModels()
                reloadEngine()
                _state.update { it.copy(modelBusy = false, phase = ModelPhase.NONE, modelMessage = ModelMessage.IMPORT_OK) }
            } else {
                _state.update { it.copy(modelBusy = false, phase = ModelPhase.NONE, modelMessage = problem) }
            }
        }
    }

    /** Borra el modelo de la fila: primero descarga el motor cargado (no se borra un modelo en uso). */
    fun deleteModel(modelId: String) {
        val row = _state.value.models.firstOrNull { it.id == modelId } ?: return
        if (!tryStartModelOperation(ModelPhase.DELETING)) return
        viewModelScope.launch {
            val problem: ModelMessage? = try {
                unloadLoaded()
                Models.deleteModel(getApplication(), row.engine.wire, _state.value.pair.wire)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ModelActions.classify(e)
            }
            refreshModels()
            reloadEngine()
            _state.update {
                it.copy(modelBusy = false, phase = ModelPhase.NONE, modelMessage = problem ?: ModelMessage.DELETE_OK)
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
                .onFailure { _state.update { it.copy(errorMessage = app().getString(R.string.error_generic), busy = false) } }
        }
    }

    fun runBenchmark() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, errorMessage = null, benchmark = null, benchNoTexts = false) }
        viewModelScope.launch {
            runCatching {
                val loadedTexts = loadBenchParagraphs(_state.value.pair)
                if (loadedTexts == null) {
                    null
                } else {
                    val (source, paragraphs) = loadedTexts
                    source to BenchmarkRunner().run(paragraphs, ::translateParagraph)
                }
            }.onSuccess { done ->
                _state.update {
                    if (done == null) {
                        it.copy(benchNoTexts = true, busy = false)
                    } else {
                        it.copy(benchmark = done.second, benchSource = done.first, busy = false)
                    }
                }
            }.onFailure { _state.update { it.copy(errorMessage = app().getString(R.string.error_generic), busy = false) } }
        }
    }

    /** Una sola llamada al motor por párrafo (con sus frases): Firefox arma su lista corta en cada llamada. */
    private suspend fun translateParagraph(paragraph: String): String {
        val engine = loaded ?: error("no hay motor cargado")
        return engine.translate(SentenceSplitter.split(paragraph)).joinToString(" ")
    }

    /** Textos del benchmark del par, o null si no hay (es → en sin `bench/textos-es.txt`). */
    private suspend fun loadBenchParagraphs(pair: PairChoice): Pair<BenchSource, List<String>>? =
        withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (pair == PairChoice.ES_EN) {
                val spanish = File(app.filesDir, "bench/textos-es.txt")
                return@withContext if (spanish.isFile) {
                    BenchSource.PRIVATE to BenchText.parse(spanish.readText(Charsets.UTF_8))
                } else {
                    null
                }
            }
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
        CoroutineScope(Dispatchers.Default + NonCancellable).launch {
            opus.unload()
            firefox.unload()
        }
    }
}
