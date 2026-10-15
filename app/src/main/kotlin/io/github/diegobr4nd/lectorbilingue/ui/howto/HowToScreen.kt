package io.github.diegobr4nd.lectorbilingue.ui.howto

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.ui.BottomInsetSpacer
import io.github.diegobr4nd.lectorbilingue.ui.ScreenFrame
import io.github.diegobr4nd.lectorbilingue.ui.tips.FreeBooks

/**
 * "Cómo se usa" (spec 3c-3 §5): secciones cortas con título (heading para TalkBack) y un ícono decorativo; libros
 * gratis; al final "Volver a ver los consejos". Sin estado propio salvo el aviso de "volverán a aparecer".
 */
@Composable
fun HowToScreen(onBack: () -> Unit, onResetTips: () -> Unit, modifier: Modifier = Modifier) {
    var resetDone by rememberSaveable { mutableStateOf(false) }
    ScreenFrame(modifier) {
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(LectorIcons.ArrowBack), contentDescription = stringResource(R.string.languages_back))
            }
            Spacer(Modifier.width(Spacing.s))
            Text(
                stringResource(R.string.howto_title),
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = ReadingFontFamily),
                modifier = Modifier.semantics { heading() },
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Section(LectorIcons.Add, R.string.howto_add_title, R.string.howto_add_body)
            Section(LectorIcons.Translate, R.string.howto_tap_title, R.string.howto_tap_body)
            Section(LectorIcons.Tune, R.string.howto_modes_title, R.string.howto_modes_body)
            Section(LectorIcons.Toc, R.string.howto_chapters_title, R.string.howto_chapters_body)
            Section(LectorIcons.Download, R.string.howto_languages_title, R.string.howto_languages_body)
            Section(LectorIcons.Lock, R.string.howto_privacy_title, R.string.howto_privacy_body)
            Text(
                stringResource(R.string.howto_free_books_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            FreeBooks(Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = { onResetTips(); resetDone = true },
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.howto_reset)) }
            if (resetDone) {
                Text(
                    stringResource(R.string.howto_reset_done),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            BottomInsetSpacer()
        }
    }
}

@Composable
private fun Section(@DrawableRes icon: Int, @StringRes title: Int, @StringRes body: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
