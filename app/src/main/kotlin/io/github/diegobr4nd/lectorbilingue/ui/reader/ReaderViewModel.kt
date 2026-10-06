package io.github.diegobr4nd.lectorbilingue.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.diegobr4nd.lectorbilingue.books.BookRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lo que el Lector necesita de un `Locator` de Readium, ya traducido a tipos simples (así se prueba en la JVM).
 * [json] es el localizador entero, para volver al mismo punto.
 */
data class ReaderPosition(val json: String, val totalProgression: Double?, val chapterTitle: String?)

/**
 * Estado del Lector: barras, etiqueta de posición y guardado. La posición se guarda 1 s después del último cambio
 * y al salir. No guarda ni registra texto del libro (solo el localizador, que es una dirección dentro del EPUB).
 */
@OptIn(FlowPreview::class)
class ReaderViewModel(private val bookId: String, private val repo: BookRepository) : ViewModel() {
    private val position = MutableStateFlow<ReaderPosition?>(null)
    private var lastSaved: ReaderPosition? = null
    @Volatile private var atEnd = false
    @Volatile private var touchExploration = false

    private val _bars = MutableStateFlow(true) // Al abrir se ven.
    val barsVisible: StateFlow<Boolean> = _bars.asStateFlow()
    private val _label = MutableStateFlow(PositionLabel(null, null))
    val label: StateFlow<PositionLabel> = _label.asStateFlow()

    init {
        viewModelScope.launch {
            position.filterNotNull().debounce(1_000).collect { save(it) }
        }
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

    private suspend fun save(p: ReaderPosition) {
        if (p == lastSaved) return
        repo.savePosition(bookId, p.json, (p.totalProgression?.takeIf { !it.isNaN() } ?: 0.0).toFloat())
        lastSaved = p
    }
}
