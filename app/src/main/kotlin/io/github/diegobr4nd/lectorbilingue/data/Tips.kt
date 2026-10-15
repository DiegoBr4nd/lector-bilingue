package io.github.diegobr4nd.lectorbilingue.data

/**
 * Los consejos del Lector (3c-3). El orden es la prioridad: si dos tocan a la vez, sale el primero.
 * [wire] es lo que se guarda en el teléfono: no cambiarlo (un consejo ya visto volvería a salir).
 */
enum class TipId(val wire: String) {
    DIRECTION("direction"),
    TAP("tap"),
    HIDE_AND_MODES("hide_modes"),
    CHAPTER_EDGE("chapter_edge"),

    /** Tarjeta "¿Primera vez? Mira cómo se usa la app" de la Biblioteca (no es del Lector: TipRules nunca la elige). */
    LIBRARY_HOWTO("library_howto"),
    ;

    companion object {
        fun fromWire(value: String): TipId? = entries.firstOrNull { it.wire == value }
    }
}
