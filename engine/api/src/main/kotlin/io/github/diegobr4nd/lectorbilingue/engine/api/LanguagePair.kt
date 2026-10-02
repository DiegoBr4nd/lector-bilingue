package io.github.diegobr4nd.lectorbilingue.engine.api

/** Par de idiomas con códigos ISO 639-1, por ejemplo en → es. */
data class LanguagePair(val source: String, val target: String) {
    init {
        require(source.isNotBlank()) { "source vacío" }
        require(target.isNotBlank()) { "target vacío" }
        require(source != target) { "source y target deben ser distintos" }
    }
}
