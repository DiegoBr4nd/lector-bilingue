package io.github.diegobr4nd.lectorbilingue.engine.api

/** Contrato común de todos los motores (OPUS, Firefox). Un solo motor cargado a la vez. */
interface TranslationEngine {
    val id: String
    suspend fun load(pair: LanguagePair, config: EngineConfig)
    suspend fun translate(sentences: List<String>): List<String>
    fun unload()
}
