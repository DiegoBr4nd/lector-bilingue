package io.github.diegobr4nd.lectorbilingue.ui.howto

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.components.LectorIcons
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ButtonShape
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.ui.BottomInsetSpacer
import io.github.diegobr4nd.lectorbilingue.ui.ScreenFrame
import io.github.diegobr4nd.lectorbilingue.ui.tips.FreeBooks

/**
 * "Cómo se usa" (spec 3c-3 §5): secciones cortas con título (heading para TalkBack) y un ícono decorativo; libros
 * gratis; al final "Volver a ver los consejos". Sin estado propio salvo el aviso de "volverán a aparecer" (se vuelve a decir en cada toque).
 */
@Composable
fun HowToScreen(onBack: () -> Unit, onResetTips: () -> Unit, modifier: Modifier = Modifier) {
    // Veces que se tocó "Volver a ver los consejos": cada toque vuelve a decir el aviso (nodo nuevo con key).
    var resets by rememberSaveable { mutableIntStateOf(0) }
    ScreenFrame(stringResource(R.string.howto_title), onBack, modifier) {
        val scroll = rememberScrollState()
        // Tras "Volver a ver los consejos" el aviso queda debajo del botón: se desplaza hasta el final para que se vea.
        LaunchedEffect(resets) {
            if (resets == 0) return@LaunchedEffect
            withFrameNanos { } // el aviso ya está medido: maxValue lo incluye
            scroll.animateScrollTo(scroll.maxValue)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            // Los nombres de botones salen de los strings de esos botones (spec §3): así no se desalinean.
            val add = stringResource(R.string.library_add)
            val aa = stringResource(R.string.reading_aa)
            val languages = stringResource(R.string.library_menu_languages)
            Section(LectorIcons.Add, stringResource(R.string.howto_add_title), stringResource(R.string.howto_add_body, add))
            Section(LectorIcons.Translate, stringResource(R.string.howto_tap_title), stringResource(R.string.howto_tap_body))
            Section(LectorIcons.Tune, stringResource(R.string.howto_modes_title, aa), stringResource(R.string.howto_modes_body, aa))
            Section(LectorIcons.Toc, stringResource(R.string.howto_chapters_title), stringResource(R.string.howto_chapters_body))
            Section(
                LectorIcons.Download,
                stringResource(R.string.howto_languages_title),
                stringResource(R.string.howto_languages_body, languages),
            )
            Section(LectorIcons.Lock, stringResource(R.string.howto_privacy_title), stringResource(R.string.howto_privacy_body))
            Text(
                stringResource(R.string.howto_free_books_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            FreeBooks(Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = { onResetTips(); resets++ },
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.howto_reset)) }
            if (resets > 0) {
                key(resets) {
                    Text(
                        stringResource(R.string.howto_reset_done),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            BottomInsetSpacer()
        }
    }
}

@Composable
private fun Section(@DrawableRes icon: Int, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
