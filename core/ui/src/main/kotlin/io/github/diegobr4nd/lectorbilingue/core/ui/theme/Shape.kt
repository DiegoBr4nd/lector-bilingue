package io.github.diegobr4nd.lectorbilingue.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Esquinas: 12 dp tarjetas, 20 dp botones, 28 dp diálogos.
 * `small` y `medium` valen 12 dp a propósito. Los botones de Material 3 usan `Shapes.full` por
 * defecto, así que `large` no les llega: para ellos está [ButtonShape].
 */
val LectorShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Esquina de los botones (20 dp); se pasa explícitamente a cada botón. */
val ButtonShape = RoundedCornerShape(20.dp)
