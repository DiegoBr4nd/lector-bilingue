package io.github.diegobr4nd.lectorbilingue.data

import kotlin.math.ceil
import kotlin.math.floor

// Cada enum guarda en [wire] el texto que va a los ajustes del teléfono; así renombrar una constante
// de Kotlin no rompe lo que ya está guardado.

/** Tema de la página. [SYSTEM] sigue al modo oscuro del teléfono hasta que se elija otro. */
enum class PageTheme(val wire: String) {
    SYSTEM("system"), LIGHT("light"), SEPIA("sepia"), DARK("dark"), BLACK("black");

    companion object {
        fun fromWire(value: String?): PageTheme = entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

/** Fuente de lectura. [ORIGINAL] deja la del libro. */
enum class ReadingFont(val wire: String) {
    ORIGINAL("original"), LITERATA("literata"), INTER("inter"), ATKINSON("atkinson");

    companion object {
        fun fromWire(value: String?): ReadingFont = entries.firstOrNull { it.wire == value } ?: ORIGINAL
    }
}

enum class LineHeightLevel(val wire: String) {
    COMPACT("compact"), NORMAL("normal"), WIDE("wide");

    companion object {
        fun fromWire(value: String?): LineHeightLevel = entries.firstOrNull { it.wire == value } ?: NORMAL
    }
}

enum class MarginLevel(val wire: String) {
    NARROW("narrow"), NORMAL("normal"), WIDE("wide");

    companion object {
        fun fromWire(value: String?): MarginLevel = entries.firstOrNull { it.wire == value } ?: NORMAL
    }
}

enum class TextAlignChoice(val wire: String) {
    START("start"), JUSTIFY("justify");

    companion object {
        fun fromWire(value: String?): TextAlignChoice = entries.firstOrNull { it.wire == value } ?: START
    }
}

/**
 * Cómo quiere leer la persona. Iguales para todos los libros. El valor por defecto es el "de fábrica":
 * respeta el estilo del libro.
 */
data class ReadingSettings(
    val theme: PageTheme = PageTheme.SYSTEM,
    val fontScale: Double = 1.0,
    val font: ReadingFont = ReadingFont.ORIGINAL,
    val lineHeight: LineHeightLevel = LineHeightLevel.NORMAL,
    val margins: MarginLevel = MarginLevel.NORMAL,
    val align: TextAlignChoice = TextAlignChoice.START,
) {
    /** true si no se ha cambiado nada (se puede respetar el estilo del libro tal cual). */
    val isFactory: Boolean get() = this == ReadingSettings()

    companion object {
        const val MIN_SCALE = 0.75
        const val MAX_SCALE = 2.5
        const val STEP = 0.1

        /** Acota a [MIN_SCALE]..[MAX_SCALE] y redondea a pasos de [STEP] (los topes se conservan tal cual). */
        fun normalizeScale(scale: Double): Double = when {
            scale.isNaN() -> 1.0
            scale <= MIN_SCALE -> MIN_SCALE
            scale >= MAX_SCALE -> MAX_SCALE
            else -> Math.round(scale * 10) / 10.0
        }

        /** Un paso de tamaño hacia arriba o abajo, a la décima más cercana en esa dirección. */
        fun stepScale(scale: Double, up: Boolean): Double {
            val tenths = scale * 10
            val next = if (up) floor(tenths + 1e-9) + 1 else ceil(tenths - 1e-9) - 1
            return normalizeScale(next / 10)
        }
    }
}
