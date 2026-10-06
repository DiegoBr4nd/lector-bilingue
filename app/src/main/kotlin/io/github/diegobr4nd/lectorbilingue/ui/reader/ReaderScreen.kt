package io.github.diegobr4nd.lectorbilingue.ui.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.diegobr4nd.lectorbilingue.LectorApp
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.components.ConfirmDialog
import io.github.diegobr4nd.lectorbilingue.core.ui.components.LectorIcons
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.ui.rememberReduceMotion
import kotlinx.coroutines.flow.StateFlow
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Lector: el texto de Readium ocupa toda la pantalla y las barras flotan encima. Las barras se ocultan al leer
 * hacia adelante y vuelven al deslizar hacia atrás (ver [ReaderRules.barsVisible]).
 */
@OptIn(ExperimentalReadiumApi::class)
@Composable
fun ReaderScreen(
    app: LectorApp,
    bookId: String,
    publication: Publication,
    externalLink: StateFlow<String?>,
    onExternalDone: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: ReaderViewModel = viewModel(factory = viewModelFactory { initializer { ReaderViewModel(bookId, app.books) } })
    val barsVisible by vm.barsVisible.collectAsStateWithLifecycle()
    val label by vm.label.collectAsStateWithLifecycle()
    val link by externalLink.collectAsStateWithLifecycle()
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var currentHref by remember { mutableStateOf<String?>(null) }
    var tocOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val touchExploration = rememberTouchExploration()
    val reduceMotion = rememberReduceMotion()
    val toc = remember(publication) { ReaderRules.flattenToc(publication.tableOfContents.map { it.toSource() }) }

    LaunchedEffect(touchExploration) { vm.setTouchExploration(touchExploration) }
    // Al pausar (antes de onStop): si la persona vuelve y reabre enseguida, la Biblioteca ya lee la última posición.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.flush() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }

    // Posición (para la etiqueta y para guardar) y gesto (para las barras), solo mientras exista el navegador.
    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect { locator ->
            currentHref = locator.href.toString()
            vm.onPosition(locator.toPosition())
        }
    }
    DisposableEffect(navigator) {
        val nav = navigator
        val listener = object : InputListener {
            override fun onDrag(event: DragEvent): Boolean {
                if (event.type != DragEvent.Type.Start) vm.onDrag(event.offset.y.toDouble())
                return false // Readium sigue desplazando el texto.
            }
        }
        nav?.addInputListener(listener)
        onDispose { nav?.removeInputListener(listener) }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidFragment<EpubNavigatorFragment>(
            // El texto nunca queda bajo la barra de estado ni la de gestos.
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        ) { nav -> navigator = nav }

        AnimatedVisibility(
            visible = barsVisible,
            enter = if (reduceMotion) EnterTransition.None else slideInVertically { -it } + fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(title = publication.metadata.title, onBack = onBack, onToc = { tocOpen = true })
        }
        AnimatedVisibility(
            visible = barsVisible && positionText(label) != null,
            enter = if (reduceMotion) EnterTransition.None else slideInVertically { it } + fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(label)
        }
    }

    if (tocOpen) {
        TocSheet(
            entries = toc,
            currentIndex = ReaderRules.currentTocIndex(toc, currentHref),
            onSelect = { entry ->
                tocOpen = false
                publication.tableOfContents.findByHref(entry.href)?.let { navigator?.go(it) }
            },
            onDismiss = { tocOpen = false },
        )
    }

    link?.let { url ->
        ConfirmDialog(
            title = stringResource(R.string.reader_external_title),
            body = stringResource(R.string.reader_external_body, url),
            confirmLabel = stringResource(R.string.reader_external_open),
            onConfirm = {
                onExternalDone()
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    // Sin navegador ni app de correo: no pasa nada.
                }
            },
            onDismiss = onExternalDone,
        )
    }
}

/** Barra superior: volver, título del libro (una línea) e Índice. Sin estado: se previsualiza sola. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(title: String?, onBack: () -> Unit, onToc: () -> Unit, modifier: Modifier = Modifier) {
    TopAppBar(
        title = {
            Text(
                title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.library_untitled),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = ReadingFontFamily),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(LectorIcons.ArrowBack), contentDescription = stringResource(R.string.reader_back))
            }
        },
        actions = {
            IconButton(onClick = onToc, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(LectorIcons.Toc), contentDescription = stringResource(R.string.reader_toc))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier,
    )
}

/** Barra inferior: "La tormenta · 42 %", "42 %" o nada. Se lee como texto (TalkBack la anuncia tal cual). */
@Composable
fun ReaderBottomBar(label: PositionLabel, modifier: Modifier = Modifier) {
    val text = positionText(label) ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(min = 48.dp)
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun positionText(label: PositionLabel): String? = when {
    label.chapter != null && label.percent != null -> stringResource(R.string.reader_position, label.chapter, label.percent)
    label.percent != null -> stringResource(R.string.reader_position_pct, label.percent)
    else -> label.chapter
}

/** ¿Está TalkBack (exploración táctil) encendido? Se actualiza si la persona lo enciende o apaga leyendo. */
@Composable
private fun rememberTouchExploration(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

private fun Locator.toPosition() = ReaderPosition(toJSON().toString(), locations.totalProgression, title)

private fun Link.toSource(): TocEntrySource = TocEntrySource(title, url().toString(), children.map { it.toSource() })

/** El enlace original del índice (con su `#fragmento`), para saltar justo al punto del capítulo. */
private fun List<Link>.findByHref(href: String): Link? {
    for (l in this) {
        if (l.url().toString() == href) return l
        l.children.findByHref(href)?.let { return it }
    }
    return null
}
