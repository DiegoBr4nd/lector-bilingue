package io.github.diegobr4nd.lectorbilingue.ui

import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId

/** Cómo ve la persona cada motor: OPUS es "Calidad" y Firefox es "Rápido". */
fun EngineId.kind(): EngineKind = when (this) {
    EngineId.OPUS -> EngineKind.QUALITY
    EngineId.FIREFOX -> EngineKind.FAST
}
