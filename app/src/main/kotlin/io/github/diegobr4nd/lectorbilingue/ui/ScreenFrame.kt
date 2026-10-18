package io.github.diegobr4nd.lectorbilingue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.components.LectorIcons
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing

/** Ancho máximo del contenido: en tabletas las pantallas no se estiran de lado a lado. */
val ScreenMaxWidth = 560.dp

/**
 * Barra superior común de Idiomas, Cómo se usa, Acerca de y Licencias (auditoría M3, I3): la misma `TopAppBar` que la
 * Biblioteca. Título en una línea (TalkBack lo lee entero) marcado como encabezado y «Volver» de 48 dp. En reposo es
 * del color del fondo; con [scrollBehavior], al desplazar el contenido cambia de tono y se nota el borde.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LectorTopBar(title: String, onBack: () -> Unit, scrollBehavior: TopAppBarScrollBehavior?, modifier: Modifier = Modifier) {
    TopAppBar(
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = ReadingFontFamily),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(LectorIcons.ArrowBack), contentDescription = stringResource(R.string.languages_back))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        scrollBehavior = scrollBehavior,
        modifier = modifier,
    )
}

/**
 * Pantalla con [LectorTopBar] arriba (Idiomas, Cómo se usa, Acerca de y Licencias): fondo, márgenes seguros a los
 * lados y ancho máximo del contenido. La barra respeta la de estado; abajo no hay margen: el contenido se desliza bajo
 * la barra de gestos y [BottomInsetSpacer] deja el final libre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenFrame(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // «Fijada» (pinned): la barra no se esconde al desplazar, solo cambia de tono.
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = { LectorTopBar(title, onBack, scroll) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ScreenMaxWidth).fillMaxSize().padding(horizontal = Spacing.xl),
                content = content,
            )
        }
    }
}

/** Barra de carga con texto; TalkBack lo anuncia con calma. */
@Composable
fun LoadingLine(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Final de una lista que se desliza: deja libre la barra de gestos (o el teclado) y un poco más de aire. */
@Composable
fun BottomInsetSpacer() {
    Spacer(Modifier.height(Spacing.xl))
    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
}
