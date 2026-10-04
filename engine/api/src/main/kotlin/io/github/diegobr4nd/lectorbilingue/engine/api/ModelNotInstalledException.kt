package io.github.diegobr4nd.lectorbilingue.engine.api

/** El motor no encontró un modelo instalado por el gestor para [pair]. Mensaje fijo: sin rutas. */
class ModelNotInstalledException(val engine: EngineId, val pair: LanguagePair) : Exception("modelo no instalado")
