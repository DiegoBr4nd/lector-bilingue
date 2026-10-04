package io.github.diegobr4nd.lectorbilingue.ui.enginetest

/** Por qué están bloqueados el interruptor de motor y el selector de par (para explicarlo en pantalla). */
enum class LockReason { DOWNLOADING, MODEL_BUSY, LOADING, TRANSLATING, MEASURING }

/** Reglas puras (sin Android) de la pantalla de prueba: se prueban en la JVM. */
object ScreenRules {
    /** null si los controles están libres; si no, el motivo principal. */
    fun lockReason(s: EngineTestUiState): LockReason? = when {
        s.modelBusy && s.downloading -> LockReason.DOWNLOADING
        s.modelBusy -> LockReason.MODEL_BUSY
        s.modelStatus == ModelStatus.LOADING -> LockReason.LOADING
        s.busy && s.measuring -> LockReason.MEASURING
        s.busy -> LockReason.TRANSLATING
        else -> null
    }

    /** Traducir y medir se rechazan mientras el modelo carga o hay una operación de modelos. */
    fun modelBlocksWork(s: EngineTestUiState): Boolean = s.modelBusy || s.modelStatus == ModelStatus.LOADING

    /** El aviso "mejor con Wi-Fi" solo tiene sentido si hay algo que descargar o se está descargando. */
    fun showWifiHint(s: EngineTestUiState): Boolean = s.downloading || s.models.any { !it.installed }

    /** La medición de velocidad aún aún no tiene textos en español: solo se ofrece en → es. */
    fun benchAvailable(s: EngineTestUiState): Boolean = s.pair == PairChoice.EN_ES
}
