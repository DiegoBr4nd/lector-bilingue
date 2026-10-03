package io.github.diegobr4nd.lectorbilingue.engine.api

/** Par de idiomas con códigos ISO 639-1, por ejemplo en → es. */
data class LanguagePair(val source: String, val target: String) {
    init {
        require(source.isNotBlank()) { "source vacío" }
        require(target.isNotBlank()) { "target vacío" }
        require(CODE.matches(source)) { "source no es un código de idioma válido" }
        require(CODE.matches(target)) { "target no es un código de idioma válido" }
        require(source != target) { "source y target deben ser distintos" }
    }

    private companion object {
        /** Solo 2 o 3 letras minúsculas: impide rutas como "../x" al armar la carpeta del modelo. */
        val CODE = Regex("^[a-z]{2,3}$")
    }
}
