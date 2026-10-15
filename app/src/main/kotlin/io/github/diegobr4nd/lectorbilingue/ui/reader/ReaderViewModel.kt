package io.github.diegobr4nd.lectorbilingue.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.books.BookRepository
import io.github.diegobr4nd.lectorbilingue.data.AppSettings
import io.github.diegobr4nd.lectorbilingue.data.Priority
import io.github.diegobr4nd.lectorbilingue.data.ReadingMode
import io.github.diegobr4nd.lectorbilingue.data.ReadingSettings
import io.github.diegobr4nd.lectorbilingue.data.TipId
import io.github.diegobr4nd.lectorbilingue.data.TranslateRequest
import io.github.diegobr4nd.lectorbilingue.data.TranslateResult
import io.github.diegobr4nd.lectorbilingue.data.TranslationRules
import io.github.diegobr4nd.lectorbilingue.data.TranslationService
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.ui.tips.TipEvent
import io.github.diegobr4nd.lectorbilingue.ui.tips.TipRules
import io.github.diegobr4nd.lectorbilingue.ui.tips.TipState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lo que el Lector necesita de un `Locator` de Readium, ya traducido a tipos simples (así se prueba en la JVM).
 * [json] es el localizador entero, para volver al mismo punto.
 */
data class ReaderPosition(val json: String, val totalProgression: Double?, val chapterTitle: String?)

/**
 * Estado del Lector: barras, etiqueta de posición, guardado, dirección de traducción y tarjetas.
 *
 * - La posición se guarda 1 s después del último cambio y al salir.
 * - Tarjetas: por recurso (capítulo), índice del párrafo → estado. Solo viven en memoria: no van a
 *   `SavedStateHandle` ni a disco (la traducción ya queda en el caché del servicio). La pantalla aplica [cardOps].
 * - Modos (3c-2, spec §5.3): en Intercalado y Solo traducción, la "guía de modo" pide sola lo visible ([onVisible]),
 *   de a un pedido por vez al servicio y con su propia fila (así, al desplazar rápido, lo viejo se descarta antes de
 *   llegar al motor). La tarjeta guarda el estado de la traducción; lo que ve la página sale de [ModeRules.display].
 * - Consejos (3c-3): [tip] es el que se ve ahora, calculado con [TipRules] a partir de los vistos, el modo, la
 *   dirección y lo que pasó en esta sesión. Los eventos que lo cierran llegan por [onTipEvent].
 * - Se llama desde el hilo principal (todo el estado de las tarjetas vive ahí, sin candados).
 *
 * No guarda ni registra texto del libro ni traducciones (solo el localizador, que es una dirección dentro del EPUB).
 */
@OptIn(FlowPreview::class)
class ReaderViewModel(
    private val bookId: String,
    private val repo: BookRepository,
    private val translations: TranslationService,
    /** Idiomas del EPUB (`publication.metadata.languages`), para la dirección automática. */
    private val languages: List<String>,
    /** Ajustes del teléfono: de aquí salen y aquí se guardan los ajustes de lectura (iguales para todos los libros). */
    private val settings: AppSettings,
    /** Dónde se leen los consejos vistos del disco (en las pruebas, el hilo de prueba). */
    private val tipsIo: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val position = MutableStateFlow<ReaderPosition?>(null)
    private var lastSaved: ReaderPosition? = null
    private val saving = Mutex() // Un guardado a la vez y en orden (al pausar y al parar se guarda dos veces).
    @Volatile private var atEnd = false
    @Volatile private var touchExploration = false

    private val _bars = MutableStateFlow(true) // Al abrir se ven.
    val barsVisible: StateFlow<Boolean> = _bars.asStateFlow()
    private val _label = MutableStateFlow(PositionLabel(null, null))
    val label: StateFlow<PositionLabel> = _label.asStateFlow()

    private val _direction = MutableStateFlow(TranslationRules.direction(languages, null))
    val direction: StateFlow<LanguagePair> = _direction.asStateFlow()
    private val bookLoaded = CompletableDeferred<Unit>() // la dirección guardada ya se leyó
    private var directionChosen = false

    // Sin tope ni pérdidas (revisión Tarea 4): reponer un capítulo manda una op por tarjeta de golpe y la pantalla las
    // aplica de a una con un script; un búfer fijo perdería las primeras (justo donde se lee). Un solo receptor: la
    // pantalla. Lo que llega sin pantalla espera y se aplica al volver (filtra por recurso y la reposición lo corrige).
    // Para que no crezca sin pantalla (seguridad 3c-2, BAJO-2), cada reposición descarta lo que ya no vale ([compactOps]).
    private val _cardOps = Channel<CardOp>(Channel.UNLIMITED)
    val cardOps: Flow<CardOp> = _cardOps.receiveAsFlow()

    // Ruling L: el resultado de un toque (o de reintentar tocando la tarjeta) se anuncia a TalkBack; la reinserción,
    // la pretraducción, los reintentos al cerrar Idiomas y las tarjetas que la guía de modo llena solas no. Solo si
    // llega con su recurso a la vista.
    private val _announcements = MutableSharedFlow<CardOp.Show>(extraBufferCapacity = 16)
    val announcements: SharedFlow<CardOp.Show> = _announcements.asSharedFlow()

    /**
     * Una tarjeta abierta. [token] cambia con cada pedido: un resultado viejo (o de una tarjeta ya cerrada) se ignora.
     * [auto]: la pidió la guía de modo, no un toque (al volver a Tocar se quita). [toggled]: la persona la tocó
     * (Intercalado: la ocultó; Solo traducción: está viendo el original); se olvida al cambiar de modo.
     */
    private class OpenCard(val text: String, var state: CardState, var token: Long, val auto: Boolean = false) {
        var toggled = false
    }

    /** Un pedido de la guía de modo. Vale mientras la tarjeta siga en su sitio con el mismo [token]. */
    private class ModeJob(val resource: String, val index: Int, val card: OpenCard, val token: Long, var priority: Priority)

    private val cards = HashMap<String, LinkedHashMap<Int, OpenCard>>() // recurso → índice → tarjeta
    private var visible: String? = null
    private var nextToken = 0L

    // Guía de modo. El modo sale de los ajustes de lectura (iguales para todos los libros).
    private var mode: ReadingMode = settings.readingSettingsFlow.value.mode
    private var appliedSettings: ReadingSettings = settings.readingSettingsFlow.value
    private val modeQueue = ArrayDeque<ModeJob>() // en espera, en el orden en que se piden
    private var modeInFlight = 0
    private var modeMore = false // la última vuelta dejó párrafos fuera por el tope: se vuelve a planear al vaciarse
    private var guideRound = 0L // cada vuelta nueva (o cambio de recurso, modo, dirección) deja sin efecto la anterior
    private val lastVisible = HashMap<String, List<PageParagraph>>() // lo último que la página dijo que se ve
    private val _chapterNotice = MutableStateFlow<ChapterNotice?>(null)

    /** Aviso único arriba del capítulo en los modos nuevos (falta el modelo o el motor no carga); null = nada. */
    val chapterNotice: StateFlow<ChapterNotice?> = _chapterNotice.asStateFlow()

    // Revisión final 3c-2 (m3): un toque que vuelve a fallar con el MISMO aviso puesto. El aviso no cambia (su región
    // viva calla), así que la pantalla lo vuelve a decir a TalkBack; la banda no se repite.
    private val _noticeAgain = MutableSharedFlow<ChapterNotice>(extraBufferCapacity = 4)

    /** El aviso que un toque volvió a dar estando ya puesto (para anunciarlo otra vez). */
    val noticeAgain: SharedFlow<ChapterNotice> = _noticeAgain.asSharedFlow()

    // ---------- Consejos (3c-3) ----------
    // Va después de _direction (Ruling A): stateIn empieza a leerla al crearse.

    /** Lo que solo sabe el Lector en esta sesión (los vistos, el modo y la dirección vienen de sus propios flujos). */
    private data class TipInputs(
        val pageShown: Boolean = false,
        val blocked: Boolean = false,
        val tapTranslated: Boolean = false,
        val chapterEnd: Boolean = false,
    )

    private val tipInputs = MutableStateFlow(TipInputs())
    private var shownTip: TipId? = null // el que está en pantalla: se queda hasta cerrarse (TipRules.next)

    /** El consejo que la pantalla muestra ahora, o null (spec 3c-3 §3). */
    val tip: StateFlow<TipId?> = combine(
        settings.seenTipsFlow,
        settings.seenTipsLoaded,
        settings.readingSettingsFlow,
        _direction,
        tipInputs,
    ) { seen, loaded, reading, dir, local ->
        val state = TipState(
            seen = seen,
            seenLoaded = loaded,
            pageReady = local.pageShown,
            blocked = local.blocked,
            mode = reading.mode,
            sourceIsSpanish = dir.source == "es",
            translatedByTap = local.tapTranslated,
            reachedChapterEnd = local.chapterEnd,
        )
        // Mientras está bloqueado se recuerda el de antes: al desbloquear vuelve el mismo.
        TipRules.next(state, shownTip).also { if (!state.blocked) shownTip = it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** "Entendido": el consejo en pantalla queda visto. */
    fun dismissTip() {
        tip.value?.let { settings.markTipSeen(it) }
    }

    /** Algo que hizo la persona: si cierra el consejo en pantalla, queda visto (TipRules.dismissedBy). */
    fun onTipEvent(event: TipEvent) {
        val current = tip.value ?: return
        if (TipRules.dismissedBy(current, event)) settings.markTipSeen(current)
    }

    /** La lectura llegó al final de un capítulo que no es el último (lo mide la pantalla al asentarse). */
    fun onChapterEnd() = tipInputs.update { it.copy(chapterEnd = true) }

    /** Hay una hoja, "Idiomas", "Cómo se usa" o la banda del aviso: el consejo espera (no se marca). */
    fun setTipBlocked(blocked: Boolean) = tipInputs.update { it.copy(blocked = blocked) }

    init {
        // Los vistos se leen fuera del hilo principal antes de que nada los marque (así marcar no lee el disco aquí).
        viewModelScope.launch { settings.loadSeenTips(tipsIo) }
        viewModelScope.launch {
            position.filterNotNull().debounce(1_000).collect { save(it) }
        }
        viewModelScope.launch {
            val saved = try {
                repo.get(bookId)?.direction
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // sin la base se usa la automática
            }
            if (!directionChosen) _direction.value = TranslationRules.direction(languages, saved)
            bookLoaded.complete(Unit)
        }
        viewModelScope.launch { settings.readingSettingsFlow.collect { onSettings(it) } }
    }

    /**
     * Toque sobre un párrafo: `hit[0]` es el tocado y el resto, los siguientes (para pretraducir en Tocar). Lo que hace
     * depende del modo ([ModeRules.tap]): sin tarjeta, se abre y se pide su traducción; con tarjeta, en Tocar se cierra
     * y se olvida, en Intercalado se oculta o se vuelve a mostrar y en Solo traducción alterna el original. Vacío → nada.
     */
    fun onTap(resource: String, hit: List<PageParagraph>) {
        val tapped = hit.firstOrNull() ?: return
        if (visible == null) onResourceShown(resource)
        // Antes de abrir la tarjeta: "Toca un párrafo…" queda visto al tocar aunque la traducción tarde.
        onTipEvent(TipEvent.TAPPED)
        val open = cards.getOrPut(resource) { LinkedHashMap() }
        val anchor = ModeRules.anchor(mode)
        val existing = open[tapped.index]
        when (ModeRules.tap(mode, existing?.state)) {
            TapAction.NOTHING -> return
            TapAction.CLOSE -> {
                open.remove(tapped.index)
                _cardOps.trySend(CardOp.Hide(resource, tapped.index, anchor))
                onTipEvent(TipEvent.CARD_HIDDEN)
                return
            }
            TapAction.TOGGLE -> {
                val card = existing ?: return
                card.toggled = !card.toggled
                emit(resource, tapped.index, card, anchor)
                // Lo que aparece se anuncia (la traducción o "Original"); al ocultar no: eso lo anuncia la pantalla.
                val shown = ModeRules.display(mode, card.state, card.toggled)
                if (shown != null && visible == resource) _announcements.tryEmit(CardOp.Show(resource, tapped.index, shown, anchor))
                return
            }
            TapAction.OPEN -> Unit
        }
        val normalized = TranslationRules.normalize(tapped.text)
        if (normalized.isEmpty()) return
        if (tapped.cut || TranslationRules.tooLong(normalized)) {
            // Directa, sin pasar por "Preparando…": el servicio lo rechazaría enseguida igual (y no se reintenta).
            open[tapped.index] = OpenCard(tapped.text, CardState.TooLong, 0)
            showIfVisible(resource, tapped.index)
            if (visible == resource) _announcements.tryEmit(CardOp.Show(resource, tapped.index, CardState.TooLong, anchor))
            return
        }
        val card = OpenCard(tapped.text, CardState.Skeleton, 0)
        open[tapped.index] = card
        // En los modos nuevos lo de abajo lo pide la guía de modo: aquí no se pretraduce.
        request(resource, tapped.index, card, hit.takeIf { mode == ReadingMode.TAP }, announce = true)
    }

    /**
     * El recurso visible cambió o se recargó: se cancela la pretraducción de los demás y se reponen sus tarjetas.
     * [reloaded] = false: vuelta al frente con la MISMA página (no se recargó, sus tarjetas siguen ahí): no se repone
     * nada, así no se mueve lo que se lee (revisión final 3c-2, I1).
     */
    fun onResourceShown(resource: String, reloaded: Boolean = true) {
        val previous = visible
        val changed = previous != resource
        visible = resource
        tipInputs.update { it.copy(pageShown = true) }
        if (changed && previous != null) {
            // Otro capítulo: cierra "Sigue deslizando…" y el borde se vuelve a medir en el nuevo.
            onTipEvent(TipEvent.CHAPTER_CHANGED)
            tipInputs.update { it.copy(chapterEnd = false) }
        }
        translations.cancelPrefetchExcept(resource)
        if (changed) {
            // Lo que la guía de modo aún no pidió del capítulo anterior ya no se pide (y no deja esqueletos).
            guideRound++
            dropQueued { it.resource != resource }
        }
        if (reloaded || changed) reshow(ModeRules.anchor(mode))
    }

    /**
     * La página quedó quieta: [paragraphs] son los que se ven y los [ModeRules.FOLLOWING] siguientes, de arriba abajo
     * (`ParagraphBridge.visible`). En Intercalado y Solo traducción se piden los que aún no tienen tarjeta (ver
     * [ModeRules.plan]); lo que esperaba turno y ya no se ve se descarta. En Tocar, nada.
     */
    fun onVisible(resource: String, paragraphs: List<PageParagraph>) {
        if (mode == ReadingMode.TAP) return
        if (visible == null) onResourceShown(resource)
        if (visible != resource) return // respuesta de una página que ya no está
        lastVisible[resource] = paragraphs
        guide(resource)
    }

    /** "Reintentar" del aviso del capítulo: se quita el aviso y se vuelve a pedir lo que se ve. */
    fun retryChapter(resource: String) {
        _chapterNotice.value = null
        guide(resource)
    }

    /** Estado de la tarjeta del párrafo [index] de [resource] tal como la ve la página, o null si no tiene. */
    fun cardState(resource: String, index: Int): CardState? =
        cards[resource]?.get(index)?.let { ModeRules.display(mode, it.state, it.toggled) }

    /** Tocar una tarjeta con error (o sin modelo) la vuelve a pedir. Cualquier otro estado: nada. */
    fun retry(resource: String, index: Int) {
        val card = cards[resource]?.get(index) ?: return
        if (card.state is CardState.Failed || card.state is CardState.MissingModel) {
            request(resource, index, card, null, announce = true, retry = true)
        }
    }

    /**
     * Al cerrar Idiomas: se suelta el motor (así un motor o modelo elegido allí se usa enseguida), se quita el aviso del
     * capítulo y se vuelven a pedir las tarjetas sin modelo y las que estaban traduciéndose (soltar el motor cancela la
     * fila); en los modos nuevos, además, lo que se ve.
     */
    fun onLanguagesClosed() {
        translations.release()
        _chapterNotice.value = null
        modeQueue.clear()
        val requeue = ArrayList<ModeJob>()
        for ((resource, open) in cards) {
            for ((index, card) in open) {
                val waiting = card.state is CardState.MissingModel || card.state is CardState.Skeleton || card.state is CardState.Preparing
                if (!waiting) continue
                if (!card.auto) {
                    request(resource, index, card, null)
                } else if (resource == visible) {
                    // Token nuevo: el pedido cancelado, al volver, ya no la toca.
                    card.token = ++nextToken
                    requeue += ModeJob(resource, index, card, card.token, Priority.TAP)
                }
            }
        }
        modeQueue.addAll(requeue.sortedBy { it.index })
        pump()
        visible?.let { guide(it) }
    }

    /** Ajustes de lectura de la hoja "Ajustes de lectura". Valen los de fábrica hasta que se leen del teléfono. */
    val readingSettings: StateFlow<ReadingSettings> get() = settings.readingSettingsFlow

    /** Guarda los ajustes (el tamaño ya acotado) y los publica en el mismo instante: el libro cambia enseguida. */
    fun setReadingSettings(new: ReadingSettings) {
        settings.readingSettings = new
    }

    /** Un cambio de la hoja ("qué cambiar"): se aplica sobre los últimos ajustes, así dos toques seguidos no se pisan. */
    fun updateReadingSettings(change: (ReadingSettings) -> ReadingSettings) = settings.updateReadingSettings(change)

    /** A+ / A−: un paso de 10 %, sin pasar de 75 % ni de 250 %. */
    fun stepFontScale(up: Boolean) = updateReadingSettings { it.copy(fontScale = ReadingRules.step(it.fontScale, up)) }

    /** "Restablecer": vuelve a los de fábrica (respetan el estilo del libro). */
    fun resetReadingSettings() = setReadingSettings(ReadingSettings())

    /**
     * Guarda la dirección del libro, cierra todas las tarjetas (eran de la otra dirección) y vacía la fila del servicio.
     * En los modos nuevos vuelve a pedir lo que se ve, ya con la dirección nueva.
     */
    fun setDirection(pair: LanguagePair) {
        if (pair == _direction.value) return
        directionChosen = true
        _direction.value = pair
        val anchor = ModeRules.anchor(mode)
        for ((resource, open) in cards) {
            for (index in open.keys) _cardOps.trySend(CardOp.Hide(resource, index, anchor))
        }
        cards.clear()
        modeQueue.clear()
        guideRound++
        _chapterNotice.value = null // el aviso era de la otra dirección
        // Lo pedido con la dirección vieja (toques y pretraducción) ya no sirve: sin esto el motor lo terminaría y
        // cambiaría de modelo dos veces (5 a 15 s de espera tras elegir la nueva).
        translations.release()
        // NonCancellable: si se sale enseguida del Lector, la elección se guarda igual.
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    repo.setDirection(bookId, TranslationRules.wire(pair))
                } catch (e: Exception) {
                    // Sin la base, la dirección vale mientras el Lector siga abierto.
                }
            }
        }
        visible?.let { guide(it) }
    }

    /**
     * Pide la traducción de [card] como toque; con [hit], al llegar pretraduce los siguientes. [announce]: la pidió la
     * persona con un toque, así que el resultado se anuncia.
     */
    private fun request(
        resource: String,
        index: Int,
        card: OpenCard,
        hit: List<PageParagraph>?,
        announce: Boolean = false,
        retry: Boolean = false,
    ) {
        val token = ++nextToken
        card.token = token
        card.state = if (translations.engineReady(_direction.value)) CardState.Skeleton else CardState.Preparing
        showIfVisible(resource, index)
        viewModelScope.launch {
            // Reintentar: la cuenta del tiempo mínimo del estado de espera corre a la par del pedido.
            val minShown = if (retry) launch { delay(RETRY_MIN_MILLIS) } else null
            bookLoaded.await()
            val pair = _direction.value
            val result = try {
                translations.translate(TranslateRequest(pair, card.text, Priority.TAP, resource))
            } catch (e: CancellationException) {
                ensureActive() // el VM se limpió: termina aquí
                minShown?.cancel()
                return@launch // release() vació la fila; onLanguagesClosed la vuelve a pedir
            }
            // Reintentar que no traduce (falla al preparar, sin modelo o falla del párrafo): lo que se mostraba
            // ("Preparando…" o, con el motor cargado, el esqueleto) se ve un momento, así se nota que se intentó.
            if (result is TranslateResult.Done) minShown?.cancel() else minShown?.join()
            // Cerrada, reabierta o vuelta a pedir mientras tanto: este resultado ya no es el suyo.
            if (card.token != token || cards[resource]?.get(index) !== card) return@launch
            val anchor = ModeRules.anchor(mode)
            val notice = result.toNotice()
            if (mode != ReadingMode.TAP && notice != null) {
                // Modos nuevos: sin tarjeta de error por párrafo ni su anuncio; un solo aviso arriba (decisión del
                // controlador: la pantalla anuncia solo el aviso, al cambiar chapterNotice). Si ya estaba puesto, no
                // cambia: se vuelve a decir por noticeAgain (m3).
                val already = _chapterNotice.value == notice
                chapterFailed(notice, resource, index, card)
                if (announce && already && visible == resource) _noticeAgain.tryEmit(notice)
                return@launch
            }
            card.state = result.toCardState()
            // En otro recurso no se inserta; queda guardado y se repone al volver (onResourceShown).
            showIfVisible(resource, index)
            val shown = ModeRules.display(mode, card.state, card.toggled)
            // Primera traducción mostrada por un toque: toca "Toca otra vez para ocultarla…".
            if (announce && mode == ReadingMode.TAP && result is TranslateResult.Done) tipInputs.update { it.copy(tapTranslated = true) }
            if (announce && visible == resource && shown != null) _announcements.tryEmit(CardOp.Show(resource, index, shown, anchor))
            // Solo si tradujo: tras un fallo o sin modelo, cada pretraducción volvería a intentar cargar el motor.
            if (hit != null && result is TranslateResult.Done) prefetch(resource, pair, hit)
        }
    }

    private suspend fun prefetch(resource: String, pair: LanguagePair, hit: List<PageParagraph>) {
        if (visible != resource) return
        val cached = translations.cached(pair, hit.drop(1).map { it.text }).keys
        if (visible != resource || _direction.value != pair) return
        val open = cards[resource]?.values?.map { TranslationRules.normalize(it.text) }.orEmpty()
        val targets = CardRules.prefetchTargets(hit, cached + open)
        translations.prefetch(targets.map { TranslateRequest(pair, it.text, Priority.PREFETCH, resource) })
    }

    // ---------- Guía de modo (Intercalado y Solo traducción) ----------

    /** Una vuelta de la guía: mira el caché de lo visible sin tarjeta y aplica [ModeRules.plan]. */
    private fun guide(resource: String) {
        if (mode == ReadingMode.TAP || _chapterNotice.value != null || visible != resource) return
        val paragraphs = lastVisible[resource] ?: return
        val round = ++guideRound
        viewModelScope.launch {
            bookLoaded.await()
            val pair = _direction.value
            val open = cards[resource].orEmpty()
            val unknown = paragraphs.filter { it.index !in open }.map { it.text }
            val cached = if (unknown.isEmpty()) emptyMap() else translations.cached(pair, unknown)
            // Mientras se miraba el caché pudo llegar otra vuelta, otro capítulo, otro modo, otra dirección o el aviso.
            if (round != guideRound || visible != resource || mode == ReadingMode.TAP || _chapterNotice.value != null || _direction.value != pair) {
                return@launch
            }
            applyPlan(resource, paragraphs, cached)
        }
    }

    private fun applyPlan(resource: String, paragraphs: List<PageParagraph>, cached: Map<String, String>) {
        val open = cards.getOrPut(resource) { LinkedHashMap() }
        val anchor = ModeRules.anchor(mode)
        val queued = modeQueue.filter { it.resource == resource && current(it) }.associateBy { it.index }
        val plan = ModeRules.plan(paragraphs, open.keys, queued.keys, modeInFlight, cached.keys)
        modeMore = plan.more
        for (p in plan.tooLong) {
            val card = OpenCard(p.text, CardState.TooLong, 0, auto = true)
            open[p.index] = card
            emit(resource, p.index, card, anchor)
        }
        for (p in plan.cached) {
            val card = OpenCard(p.text, CardState.Text(cached.getValue(TranslationRules.normalize(p.text))), 0, auto = true)
            open[p.index] = card
            emit(resource, p.index, card, anchor)
        }
        val fresh = ArrayList<ModeJob>()
        val next = plan.requests.map { r ->
            queued[r.paragraph.index]?.also { it.priority = r.priority } ?: run {
                val card = OpenCard(r.paragraph.text, CardState.Skeleton, ++nextToken, auto = true)
                open[r.paragraph.index] = card
                ModeJob(resource, r.paragraph.index, card, card.token, r.priority).also { fresh += it }
            }
        }
        // Lo que esperaba turno y ya no se ve (o pasa del tope) se descarta, sin dejar su esqueleto.
        val kept = next.toHashSet()
        dropQueued { it !in kept }
        modeQueue.clear()
        modeQueue.addAll(next)
        pump(silent = fresh)
        // Las nuevas salen ya con su estado (la primera, "Preparando…" si el motor aún no está cargado).
        for (job in fresh) if (current(job)) emit(resource, job.index, job.card, anchor)
    }

    /**
     * Manda al servicio el siguiente pedido de la fila del modo: uno a la vez, así un pedido viejo nunca espera en la
     * fila del servicio (el motor no queda ocioso más que el salto al hilo principal). [silent]: tarjetas que aún no se
     * pusieron en la página (las pone quien llama).
     */
    private fun pump(silent: Collection<ModeJob> = emptyList()) {
        while (modeInFlight < MODE_IN_FLIGHT) {
            val job = modeQueue.removeFirstOrNull() ?: return
            if (!current(job)) continue
            modeInFlight++
            val card = job.card
            if (card.state == CardState.Skeleton && !translations.engineReady(_direction.value)) {
                card.state = CardState.Preparing
                if (job !in silent) emit(job.resource, job.index, card, ModeRules.anchor(mode))
            }
            viewModelScope.launch {
                try {
                    bookLoaded.await()
                    val result = try {
                        translations.translate(TranslateRequest(_direction.value, card.text, job.priority, job.resource))
                    } catch (e: CancellationException) {
                        ensureActive() // el VM se limpió: termina aquí
                        // La fila del servicio se vació (cambio de capítulo, Idiomas, dirección): sin dejar el
                        // esqueleto; se pide en la siguiente vuelta si se sigue viendo.
                        if (current(job)) removeCard(job.resource, job.index)
                        return@launch
                    }
                    if (current(job)) onModeResult(job, result)
                } finally {
                    modeInFlight--
                    pump()
                    // Fila vacía y algo quedó fuera por el tope: se pide sin esperar a que la página se mueva.
                    if (modeQueue.isEmpty() && modeInFlight == 0 && modeMore) {
                        modeMore = false
                        visible?.let { guide(it) }
                    }
                }
            }
        }
    }

    private fun onModeResult(job: ModeJob, result: TranslateResult) {
        val notice = result.toNotice()
        if (notice != null) {
            chapterFailed(notice, job.resource, job.index, job.card)
            return
        }
        job.card.state = result.toCardState()
        // Sin anuncio: la tarjeta se llenó sola (Ruling L).
        emit(job.resource, job.index, job.card, ModeRules.anchor(mode))
    }

    /**
     * Falta el modelo o el motor no carga en un modo nuevo: un solo aviso arriba del capítulo y ninguna tarjeta de
     * error. Lo que esperaba turno tampoco se pide (daría lo mismo y volvería a intentar cargar el motor).
     */
    private fun chapterFailed(notice: ChapterNotice, resource: String, index: Int, card: OpenCard) {
        _chapterNotice.value = notice
        guideRound++
        if (cards[resource]?.get(index) === card) removeCard(resource, index)
        dropQueued { true }
        // Diseño T6: ninguna otra tarjeta en espera (p. ej. abierta con un toque) se queda en "Traduciendo…" para siempre.
        // Las ya traducidas se quedan; sus pedidos pendientes llegan después y no encuentran su tarjeta.
        for ((res, open) in cards) {
            val it = open.entries.iterator()
            while (it.hasNext()) {
                val (i, c) = it.next()
                if (c.state != CardState.Skeleton && c.state != CardState.Preparing) continue
                it.remove()
                if (visible == res) _cardOps.trySend(CardOp.Hide(res, i, ModeRules.anchor(mode)))
            }
        }
        purgeDeadShows()
        // Y la segunda barrera, en la página: cualquier espera que haya quedado.
        visible?.let { _cardOps.trySend(CardOp.RemoveWaiting(it)) }
    }

    /**
     * La pantalla aplica las ops una a una y la fila puede llevar varias aún sin aplicar: los `Show` de tarjetas que ya no
     * existen (esperas que el aviso acaba de quitar) se sacan de la fila, para que la página no las pinte un rato antes de
     * quitarlas. Los `Hide` y lo demás se quedan en su orden. Todo en el hilo principal, como la pantalla (ver [compactOps]).
     */
    private fun purgeDeadShows() {
        val pending = ArrayList<CardOp>()
        while (true) pending += _cardOps.tryReceive().getOrNull() ?: break
        for (op in pending) {
            if (op is CardOp.Show && cards[op.resource]?.get(op.index) == null) continue
            _cardOps.trySend(op)
        }
    }

    /** Saca de la fila del modo los pedidos que cumplen [which] y quita sus tarjetas (aún en espera). */
    private fun dropQueued(which: (ModeJob) -> Boolean) {
        val it = modeQueue.iterator()
        while (it.hasNext()) {
            val job = it.next()
            if (!which(job)) continue
            it.remove()
            if (current(job)) removeCard(job.resource, job.index)
        }
    }

    private fun removeCard(resource: String, index: Int) {
        cards[resource]?.remove(index)
        if (visible == resource) _cardOps.trySend(CardOp.Hide(resource, index, ModeRules.anchor(mode)))
    }

    private fun current(job: ModeJob) = cards[job.resource]?.get(job.index) === job.card && job.card.token == job.token

    // ---------- Modo y ajustes ----------

    private fun onSettings(new: ReadingSettings) {
        val old = appliedSettings
        appliedSettings = new
        if (new.mode != mode) {
            changeMode(new.mode)
        } else if (mode != ReadingMode.TAP && new != old) {
            // Revisión de la Tarea 3: la tarjeta Solo copió el estilo del original en px; con otro tamaño, letra o
            // margen ese estilo queda viejo. Se reponen todas y los scripts lo vuelven a copiar.
            reshow(CardAnchor.SCREEN)
        }
    }

    /**
     * Cambiar de modo: primero el modo de la página ([CardOp.Mode]) y luego cada tarjeta adaptada (Text ↔ Solo), con
     * el ancla de pantalla (se está leyendo). Al volver a Tocar se quitan las tarjetas de la guía (las traducciones
     * quedan en caché) y su fila; las abiertas con un toque se quedan. Entre los modos nuevos se vuelve a pedir lo
     * visible; al salir de Tocar lo visible lo manda la pantalla con [onVisible].
     */
    private fun changeMode(new: ReadingMode) {
        mode = new
        guideRound++
        val hides = ArrayList<Int>()
        if (new == ReadingMode.TAP) {
            _chapterNotice.value = null
            modeQueue.clear()
            lastVisible.clear() // en Tocar la pantalla no lo actualiza: al volver a un modo manda lo de ese momento
            for ((resource, open) in cards) {
                val it = open.entries.iterator()
                while (it.hasNext()) {
                    val (index, card) = it.next()
                    if (!card.auto) continue
                    it.remove()
                    if (resource == visible) hides += index
                }
            }
        }
        cards.values.forEach { open -> open.values.forEach { it.toggled = false } }
        _cardOps.trySend(CardOp.Mode(new))
        visible?.let { resource -> hides.forEach { _cardOps.trySend(CardOp.Hide(resource, it, CardAnchor.SCREEN)) } }
        reshow(CardAnchor.SCREEN)
        visible?.let { guide(it) }
    }

    /** Vuelve a poner todas las tarjetas del recurso visible tal como se ven en el modo actual. */
    private fun reshow(anchor: CardAnchor) {
        val resource = visible ?: return
        compactOps(resource)
        cards[resource]?.entries?.sortedBy { it.key }?.forEach { (index, card) ->
            ModeRules.display(mode, card.state, card.toggled)?.let { _cardOps.trySend(CardOp.Show(resource, index, it, anchor)) }
        }
    }

    /**
     * Antes de reponer (seguridad 3c-2, BAJO-2): saca de la fila lo que la pantalla aún no aplicó y deja solo lo que
     * sigue valiendo, en el mismo orden. Así la fila no crece si la pantalla no recoge (Lector en segundo plano). Sin
     * pérdidas: la reposición que sigue vuelve a mandar cada tarjeta abierta del recurso [resource] tal como se ve ahora.
     * - Show: todas fuera (las del visible vuelven con la reposición; las de otro recurso la pantalla ya no las aplica).
     * - Hide: solo las de [resource], una por párrafo (las de otro recurso tampoco se aplican; al volver se quita todo).
     * - Mode: solo la última (la página queda en el modo de ahora; las tarjetas que siguen ya vienen adaptadas).
     * Todo pasa en el hilo principal, como la pantalla: nada se cuela entre sacar y volver a poner.
     */
    private fun compactOps(resource: String) {
        val pending = ArrayList<CardOp>()
        while (true) pending += _cardOps.tryReceive().getOrNull() ?: break
        if (pending.isEmpty()) return
        val lastMode = pending.lastOrNull { it is CardOp.Mode }
        val hidden = HashSet<Int>()
        for (op in pending) {
            val keep = when (op) {
                is CardOp.Show -> false
                is CardOp.Hide -> op.resource == resource && hidden.add(op.index)
                is CardOp.Mode -> op === lastMode
                is CardOp.RemoveWaiting -> false // la reposición ya no trae esperas: el barrido sobra
            }
            if (keep) _cardOps.trySend(op)
        }
    }

    /** Pone en la página lo que [card] muestra en el modo actual (o la quita si no muestra nada). */
    private fun emit(resource: String, index: Int, card: OpenCard, anchor: CardAnchor) {
        if (visible != resource) return
        val shown = ModeRules.display(mode, card.state, card.toggled)
        _cardOps.trySend(if (shown != null) CardOp.Show(resource, index, shown, anchor) else CardOp.Hide(resource, index, anchor))
    }

    /** Pone en la página la tarjeta abierta del párrafo [index], si su recurso es el visible. */
    private fun showIfVisible(resource: String, index: Int) {
        cards[resource]?.get(index)?.let { emit(resource, index, it, ModeRules.anchor(mode)) }
    }

    private fun TranslateResult.toCardState(): CardState = when (this) {
        is TranslateResult.Done -> CardState.Text(translation)
        is TranslateResult.MissingModel -> CardState.MissingModel(engine)
        TranslateResult.EngineFailed -> CardState.Failed(prepare = true)
        TranslateResult.ParagraphFailed -> CardState.Failed(prepare = false)
        TranslateResult.TooLong -> CardState.TooLong
    }

    /** Lo que en los modos nuevos va al aviso del capítulo en vez de a una tarjeta; null para lo demás. */
    private fun TranslateResult.toNotice(): ChapterNotice? = when (this) {
        is TranslateResult.MissingModel -> ChapterNotice.MissingModel(engine)
        TranslateResult.EngineFailed -> ChapterNotice.EngineFailed
        else -> null
    }

    /** Al salir del Lector: se cancela la fila y se descarga el motor. */
    override fun onCleared() {
        translations.release()
    }

    fun onPosition(new: ReaderPosition) {
        atEnd = ReaderRules.atEnd(new.totalProgression)
        _label.value = ReaderRules.label(new.chapterTitle, new.totalProgression)
        updateBars(null)
        position.value = new
    }

    /** `DragEvent.offset.y` de Readium (ver [ReaderRules.barsVisible]). Puede llegar desde otro hilo. */
    fun onDrag(dy: Double) = updateBars(dy)

    fun setTouchExploration(enabled: Boolean) {
        touchExploration = enabled
        updateBars(null)
    }

    private fun updateBars(dy: Double?) = _bars.update { ReaderRules.barsVisible(dy, atEnd, it, touchExploration) }

    /**
     * Al salir (onStop): guardado inmediato. NonCancellable porque, si la actividad se cierra, el ViewModel se
     * limpia enseguida y cancelaría la escritura a medias.
     */
    fun flush() {
        val p = position.value ?: return
        viewModelScope.launch { withContext(NonCancellable) { save(p) } }
    }

    private suspend fun save(p: ReaderPosition) = saving.withLock {
        if (p == lastSaved) return@withLock
        repo.savePosition(bookId, p.json, (p.totalProgression?.takeIf { !it.isNaN() } ?: 0.0).toFloat())
        lastSaved = p
    }

    companion object {
        /**
         * Lo mínimo (ms) que se ve el estado de espera al reintentar si el resultado no es una traducción: vale para
         * EngineFailed y MissingModel ("Preparando…") y para ParagraphFailed (el esqueleto, con el motor ya cargado).
         */
        const val RETRY_MIN_MILLIS = 700L

        /** Pedidos de la guía de modo en el servicio a la vez (ver `pump`). */
        private const val MODE_IN_FLIGHT = 1
    }
}
