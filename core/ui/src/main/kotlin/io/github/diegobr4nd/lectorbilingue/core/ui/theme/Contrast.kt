package io.github.diegobr4nd.lectorbilingue.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow

/** Cálculo de contraste según WCAG 2.x. Lo usan las pruebas para vigilar la paleta. */
object Contrast {
    private fun canal(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminancia(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * canal(r) + 0.7152 * canal(g) + 0.0722 * canal(b)
    }

    /** Razón de contraste entre dos colores 0xFFRRGGBB (de 1 a 21). */
    fun ratio(a: Long, b: Long): Double {
        val la = luminancia(a)
        val lb = luminancia(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}

internal fun Color.argbLong(): Long = this.toArgb().toLong() and 0xFFFFFFFF
