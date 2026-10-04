package io.github.diegobr4nd.lectorbilingue.engine.api

/** Motores de traducción. [wire] es el nombre en el catálogo, en `.installed.json` y en las carpetas. */
enum class EngineId(val wire: String) {
    OPUS("opus"),
    FIREFOX("firefox");

    companion object {
        fun fromWire(s: String): EngineId? = entries.firstOrNull { it.wire == s }
    }
}
