package io.github.diegobr4nd.lectorbilingue.ui.enginetest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Por qué están bloqueados el interruptor de motor y el selector de par (para explicarlo en pantalla). */
enum class LockReason { DOWNLOADING, MODEL_BUSY, LOADING, TRANSLATING, MEASURING }

/** Reglas puras (sin Android) de la pantalla de prueba: se prueban en la JVM. */
object ScreenRules {
    /**
     * Corre [block] (pasos previos + recarga del motor) y garantiza que, pase lo que pase, el estado no
     * se queda en LOADING con los controles bloqueados: si algo lanza o se cancela, termina en ERROR.
     */
    suspend fun guarded(state: MutableStateFlow<EngineTestUiState>, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            // LOADING lo pusieron selectEngine/selectPair y no debe quedarse.
            state.update { if (it.modelStatus == ModelStatus.LOADING) it.copy(modelStatus = ModelStatus.ERROR) else it }
            throw e
        } catch (_: Exception) {
            // Se ignora a propósito el mensaje (podría llevar rutas).
            state.update { it.copy(modelStatus = ModelStatus.ERROR) }
        }
    }

    /** null si los controles están libres; si no, el motivo principal. */
    fun lockReason(s: EngineTestUiState): LockReason? = when {
        s.modelBusy && s.downloading -> LockReason.DOWNLOADING
        s.modelBusy -> LockReason.MODEL_BUSY
        s.modelStatus == ModelStatus.LOADING -> LockReason.LOADING
        s.busy && s.measuring -> LockReason.MEASURING
        s.busy -> LockReason.TRANSLATING
        else -> null
    }

    /**
     * Estado justo al cambiar el interruptor o el par: lo medido ya no corresponde y el modelo pasa a LOADING
     * en la MISMA actualización, así "Traducir" no se cuela antes de que empiece la recarga.
     */
    fun afterSwitch(s: EngineTestUiState, choice: EngineSwitch): EngineTestUiState =
        resetForReload(s).copy(engineSwitch = choice)

    fun afterPair(s: EngineTestUiState, choice: PairChoice): EngineTestUiState =
        resetForReload(s).copy(pair = choice)

    private fun resetForReload(s: EngineTestUiState) = s.copy(
        modelStatus = ModelStatus.LOADING, output = "", lastMillis = null, benchmark = null,
        benchNoTexts = false, modelMessage = null,
    )

    /** Traducir y medir se rechazan mientras el modelo carga o hay una operación de modelos. */
    fun modelBlocksWork(s: EngineTestUiState): Boolean = s.modelBusy || s.modelStatus == ModelStatus.LOADING

    /** El aviso "mejor con Wi-Fi" solo tiene sentido si hay algo que descargar o se está descargando. */
    fun showWifiHint(s: EngineTestUiState): Boolean = s.downloading || s.models.any { !it.installed }

    /** Medir velocidad: siempre en en → es; en es → en solo si el usuario puso `bench/textos-es.txt`. */
    fun benchAvailable(s: EngineTestUiState): Boolean = s.pair == PairChoice.EN_ES || s.spanishBenchTexts

    /** null si se puede traducir; si no, el motivo (primero bloqueos, luego "sin modelo", luego "sin texto"). */
    fun translateBlock(s: EngineTestUiState): ActionBlock? = measureBlock(s)
        ?: if (s.input.isBlank()) ActionBlock.EmptyInput else null

    /** null si se puede medir (sin contar si hay textos: eso lo dice [benchAvailable]). */
    fun measureBlock(s: EngineTestUiState): ActionBlock? = lockReason(s)?.let { ActionBlock.Lock(it) }
        ?: if (s.modelStatus != ModelStatus.READY) ActionBlock.NotReady else null
}

/** Por qué un botón de acción (Traducir, Medir velocidad) está desactivado. */
sealed interface ActionBlock {
    data class Lock(val reason: LockReason) : ActionBlock
    data object NotReady : ActionBlock
    data object EmptyInput : ActionBlock
}
