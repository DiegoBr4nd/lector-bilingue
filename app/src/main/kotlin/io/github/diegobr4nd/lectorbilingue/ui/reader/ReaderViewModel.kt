package io.github.diegobr4nd.lectorbilingue.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.books.BookRepository
import io.github.diegobr4nd.lectorbilingue.data.Priority
import io.github.diegobr4nd.lectorbilingue.data.TranslateRequest
import io.github.diegobr4nd.lectorbilingue.data.TranslateResult
import io.github.diegobr4nd.lectorbilingue.data.TranslationRules
import io.github.diegobr4nd.lectorbilingue.data.TranslationService
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
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

    // Con hueco de sobra: tryEmit nunca espera y la pantalla lo vacía enseguida en el hilo principal.
    private val _cardOps = MutableSharedFlow<CardOp>(extraBufferCapacity = 256)
    val cardOps: SharedFlow<CardOp> = _cardOps.asSharedFlow()

    /** Una tarjeta abierta. [token] cambia con cada pedido: un resultado viejo (o de una tarjeta ya cerrada) se ignora. */
    private class OpenCard(val text: String, var state: CardState, var token: Long)

    private val cards = HashMap<String, LinkedHashMap<Int, OpenCard>>() // recurso → índice → tarjeta
    private var visible: String? = null
    private var nextToken = 0L

    init {
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
    }

    /**
     * Toque sobre un párrafo: `hit[0]` es el tocado y el resto, los siguientes (para pretraducir). Si ya tenía tarjeta,
     * se cierra y se olvida; si no, se abre y se pide su traducción. Vacío → nada.
     */
    fun onTap(resource: String, hit: List<PageParagraph>) {
        val tapped = hit.firstOrNull() ?: return
        if (visible == null) onResourceShown(resource)
        val open = cards.getOrPut(resource) { LinkedHashMap() }
        if (!CardRules.toggle(open.mapValues { it.value.state }, tapped.index)) {
            open.remove(tapped.index)
            _cardOps.tryEmit(CardOp.Hide(resource, tapped.index))
            return
        }
        if (TranslationRules.normalize(tapped.text).isEmpty()) return
        val card = OpenCard(tapped.text, CardState.Skeleton, 0)
        open[tapped.index] = card
        request(resource, tapped.index, card, hit)
    }

    /** El recurso visible cambió o se recargó: se cancela la pretraducción de los demás y se reponen sus tarjetas. */
    fun onResourceShown(resource: String) {
        visible = resource
        translations.cancelPrefetchExcept(resource)
        cards[resource]?.forEach { (index, card) -> _cardOps.tryEmit(CardOp.Show(resource, index, card.state)) }
    }

    /** Estado de la tarjeta abierta del párrafo [index] de [resource], o null si no tiene. */
    fun cardState(resource: String, index: Int): CardState? = cards[resource]?.get(index)?.state

    /** Tocar una tarjeta con error (o sin modelo) la vuelve a pedir. Cualquier otro estado: nada. */
    fun retry(resource: String, index: Int) {
        val card = cards[resource]?.get(index) ?: return
        if (card.state is CardState.Failed || card.state is CardState.MissingModel) request(resource, index, card, null)
    }

    /**
     * Al cerrar Idiomas: se suelta el motor (así un motor o modelo elegido allí se usa enseguida) y se vuelven a pedir
     * las tarjetas sin modelo y las que estaban traduciéndose (soltar el motor cancela la fila).
     */
    fun onLanguagesClosed() {
        translations.release()
        for ((resource, open) in cards) {
            for ((index, card) in open) {
                if (card.state is CardState.MissingModel || card.state is CardState.Skeleton || card.state is CardState.Preparing) {
                    request(resource, index, card, null)
                }
            }
        }
    }

    /** Guarda la dirección del libro y cierra todas las tarjetas (eran de la otra dirección). */
    fun setDirection(pair: LanguagePair) {
        if (pair == _direction.value) return
        directionChosen = true
        _direction.value = pair
        for ((resource, open) in cards) {
            for (index in open.keys) _cardOps.tryEmit(CardOp.Hide(resource, index))
        }
        cards.clear()
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
    }

    /** Pide la traducción de [card] como toque; con [hit], al llegar pretraduce los siguientes. */
    private fun request(resource: String, index: Int, card: OpenCard, hit: List<PageParagraph>?) {
        val token = ++nextToken
        card.token = token
        card.state = if (translations.engineReady(_direction.value)) CardState.Skeleton else CardState.Preparing
        showIfVisible(resource, index, card.state)
        viewModelScope.launch {
            bookLoaded.await()
            val pair = _direction.value
            val result = try {
                translations.translate(TranslateRequest(pair, card.text, Priority.TAP, resource))
            } catch (e: CancellationException) {
                ensureActive() // el VM se limpió: termina aquí
                return@launch // release() vació la fila; onLanguagesClosed la vuelve a pedir
            }
            // Cerrada, reabierta o vuelta a pedir mientras tanto: este resultado ya no es el suyo.
            if (card.token != token || cards[resource]?.get(index) !== card) return@launch
            card.state = result.toCardState()
            // En otro recurso no se inserta; queda guardado y se repone al volver (onResourceShown).
            showIfVisible(resource, index, card.state)
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

    private fun showIfVisible(resource: String, index: Int, state: CardState) {
        if (visible == resource) _cardOps.tryEmit(CardOp.Show(resource, index, state))
    }

    private fun TranslateResult.toCardState(): CardState = when (this) {
        is TranslateResult.Done -> CardState.Text(translation)
        is TranslateResult.MissingModel -> CardState.MissingModel(engine)
        TranslateResult.EngineFailed -> CardState.Failed(prepare = true)
        TranslateResult.ParagraphFailed -> CardState.Failed(prepare = false)
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
}
