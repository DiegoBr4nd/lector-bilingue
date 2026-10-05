package io.github.diegobr4nd.lectorbilingue.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.diegobr4nd.lectorbilingue.R

/** Nombre del par con inicial mayúscula, para títulos: "Inglés → español". */
@Composable
fun pairName(pair: String): String = when (pair) {
    "en-es" -> stringResource(R.string.welcome_pair_en_es)
    "es-en" -> stringResource(R.string.welcome_pair_es_en)
    else -> stringResource(R.string.welcome_pair_other, pair)
}

/**
 * Reemplaza el espacio antes de la flecha por uno sin corte (U+00A0): la flecha nunca empieza una línea.
 * Un solo lugar para que pantalla y pruebas coincidan.
 */
fun String.withNoBreakArrow(): String = replace(" →", "\u00a0→")

/** Nombre del par dentro de una frase: "inglés → español". */
@Composable
fun pairDirection(pair: String): String = when (pair) {
    "en-es" -> stringResource(R.string.pair_direction_en_es)
    "es-en" -> stringResource(R.string.pair_direction_es_en)
    else -> stringResource(R.string.welcome_pair_other, pair)
}
