package io.github.diegobr4nd.lectorbilingue.ui.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import io.github.diegobr4nd.lectorbilingue.data.TranslationRules
import io.github.diegobr4nd.lectorbilingue.engine.api.LanguagePair
import io.github.diegobr4nd.lectorbilingue.ui.languages.LanguagesScreen
import io.github.diegobr4nd.lectorbilingue.ui.pairDirection
import io.github.diegobr4nd.lectorbilingue.ui.withNoBreakArrow
import io.github.diegobr4nd.lectorbilingue.ui.library.LibraryRules
import io.github.diegobr4nd.lectorbilingue.ui.rememberReduceMotion
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
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
    /** Las preferencias con que se creó el navegador de Readium (los ajustes de lectura al abrir). */
    startPreferences: EpubPreferences,
    /** El título guardado en la Biblioteca (no el del OPF): así las dos pantallas muestran el mismo. */
    title: String?,
    externalLink: StateFlow<String?>,
    onExternalDone: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: ReaderViewModel = viewModel(
        factory = viewModelFactory {
            // Idiomas del OPF (`dc:language`, p. ej. "en", "es-MX"): solo para la dirección automática.
            initializer { ReaderViewModel(bookId, app.books, app.translations, publication.metadata.languages, app.settings) }
        },
    )
    val barsVisible by vm.barsVisible.collectAsStateWithLifecycle()
    val label by vm.label.collectAsStateWithLifecycle()
    val link by externalLink.collectAsStateWithLifecycle()
    val direction by vm.direction.collectAsStateWithLifecycle()
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var currentHref by remember { mutableStateOf<String?>(null) }
    var tocOpen by rememberSaveable { mutableStateOf(false) }
    var directionOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var languagesOpen by rememberSaveable { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val view by rememberUpdatedState(LocalView.current)
    val pageDensity by rememberUpdatedState(LocalDensity.current) // px del aparato por px CSS (el WebView no aplica zoom)
    val scope = rememberCoroutineScope()
    val touchExploration = rememberTouchExploration()
    val reduceMotion = rememberReduceMotion()
    val fixedLayout = remember(publication) { publication.metadata.layout == Layout.FIXED }
    // Guardia del paso de capítulo, compartido por el gesto del borde y los botones (ver ReaderRules.dragAllowed).
    val chapterTurn = remember { ChapterTurn() }
    val toc = remember(publication) { ReaderRules.flattenToc(publication.tableOfContents.map { it.toSource() }) }
    // El navegador se lee en cada llamada: el fragmento de Readium llega (o se recrea) después.
    val bridge = remember { ParagraphBridge { navigator } }
    val cardTexts by rememberUpdatedState(rememberCardTexts(app, direction))
    val labels = CardLabels(
        stringResource(R.string.reader_card_translation),
        stringResource(R.string.reader_card_skeleton),
        stringResource(R.string.reader_card_preparing),
    )
    val currentLabels by rememberUpdatedState(labels)
    val announceTranslating by rememberUpdatedState(stringResource(R.string.reader_card_skeleton))
    val announceHidden by rememberUpdatedState(stringResource(R.string.reader_card_hidden))
    val announceTranslation by rememberUpdatedState(stringResource(R.string.reader_card_announce))
    // Ajustes de lectura (iguales para todos los libros) y modo del sistema, para "Como el teléfono".
    val readingSettings by vm.readingSettings.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val cardTheme by rememberUpdatedState(ReadingRules.cardTheme(readingSettings, systemDark))
    // Las últimas preferencias mandadas a Readium: si no cambian, no se vuelven a mandar (ni se mueve la página).
    var appliedPreferences by remember { mutableStateOf(startPreferences) }

    LaunchedEffect(touchExploration) { vm.setTouchExploration(touchExploration) }
    // Por si se abrió el Lector antes de que la Biblioteca leyera los ajustes (fuera del hilo principal).
    LaunchedEffect(Unit) { app.settings.loadReadingSettings() }
    // Ajustes al instante, sin recargar la página. Readium deja la página en el mismo píxel al cambiar el tamaño (y el
    // texto se corre), así que se guarda la posición antes y se vuelve a ella enseguida (spec §11, C3). La tarjeta
    // cambia de paleta con el atributo del tema (también lo pone la página al quedar lista, más abajo).
    LaunchedEffect(readingSettings, systemDark, navigator) {
        val nav = navigator ?: return@LaunchedEffect
        val preferences = ReadingRules.preferences(readingSettings, systemDark)
        if (preferences != appliedPreferences) {
            val saved = nav.currentLocator.value
            nav.submitPreferences(preferences)
            // Si ya se está pasando a otro capítulo (la posición guardada es de otro), no se vuelve atrás.
            if (ReadingRules.restoreAfterSubmit(saved.href.toString(), currentHref)) nav.go(saved, animated = false)
            appliedPreferences = preferences
        }
        bridge.setTheme(cardTheme)
    }
    // Al pausar (antes de onStop): si la persona vuelve y reabre enseguida, la Biblioteca ya lee la última posición.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.flush() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    // Al volver al frente se reponen las tarjetas (por si la página se recargó mientras tanto).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }

    // Tarjetas: se aplican durante toda la composición (no solo al frente: ninguna se pierde) y solo en el recurso
    // visible; las de otro recurso se reponen al volver a él (onResourceShown).
    LaunchedEffect(vm) {
        vm.cardOps.collect { op ->
            when (op) {
                is CardOp.Show -> if (op.resource == currentHref) bridge.show(op.index, CardRules.card(op.card, cardTexts), currentLabels)
                is CardOp.Hide -> if (op.resource == currentHref) bridge.hide(op.index)
            }
        }
    }
    // Ruling L: TalkBack dice el resultado de un toque ("Traducción: …", "Falta el idioma…", "No se pudo…"). El texto
    // solo va al servicio de accesibilidad: nunca a registros ni a la red.
    LaunchedEffect(vm) {
        vm.announcements.collect { op ->
            if (op.resource != currentHref) return@collect
            val spoken = CardRules.spoken(op.card, cardTexts) ?: return@collect
            view.announce(if (op.card is CardState.Text) announceTranslation.format(spoken) else spoken)
        }
    }
    // Recurso nuevo (o vuelta al frente): cuando la página está lista y es ese recurso, primero se quitan todas las
    // tarjetas (las viejas de un capítulo vecino ya cargado) y luego el ViewModel repone las abiertas (spec §12).
    LaunchedEffect(currentHref, resumes) {
        val href = currentHref ?: return@LaunchedEffect
        var tries = 0
        while (!bridge.hideAll(href) && tries++ < PageReadyTries) delay(PageReadyDelayMs)
        // El tema de la tarjeta va en cada página nueva, antes de reponer las tarjetas: así nunca salen con otro color.
        bridge.setTheme(cardTheme)
        // Aun si la página nunca respondió, el ViewModel debe saber cuál es el recurso visible.
        vm.onResourceShown(href)
    }

    // Posición (para la etiqueta y para guardar) y gesto (para las barras), solo mientras exista el navegador.
    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect { locator ->
            currentHref = locator.href.toString()
            vm.onPosition(locator.toPosition())
        }
    }
    val chapterUntitled by rememberUpdatedState(stringResource(R.string.reader_toc_untitled))
    val readingOrder = remember(publication) { publication.readingOrder.map { it.url().toString() } }
    /**
     * Un paso de capítulo (+1 siguiente, -1 anterior) para el gesto del borde y para los botones: misma función de
     * Readium, mismo guardia, mismo anuncio de TalkBack con el título del capítulo nuevo. [toEnd]: al final del
     * capítulo anterior (gesto) o al principio (botón).
     */
    fun stepChapter(href: String, step: Int, toEnd: Boolean = step < 0) {
        // El navegador de ahora (no el de cuando se puso el oyente) y con su vista viva.
        val current = navigator?.takeIf { it.view != null } ?: return
        current.turnChapter(publication, href, step, toEnd)?.let { newHref ->
            chapterTurn.turningTo = newHref
            chapterTurn.turnedAt = SystemClock.uptimeMillis()
            view.announce(ReaderRules.chapterTitle(toc, newHref) ?: chapterUntitled)
        }
    }
    val (hasPrevious, hasNext) = ReaderRules.chapterButtons(readingOrder, currentHref)
    /** Toque en "Capítulo anterior/siguiente": ignorado mientras un paso nuestro aún no se asienta (no saltar dos). */
    fun onChapterButton(step: Int) {
        val href = currentHref ?: return
        if (!ReaderRules.dragAllowed(chapterTurn.turningTo, href, SystemClock.uptimeMillis() - chapterTurn.turnedAt)) return
        chapterTurn.turningTo = null
        stepChapter(href, step, toEnd = false)
    }
    DisposableEffect(navigator) {
        val nav = navigator
        val listener = object : InputListener {
            /** Bordes de la página y capítulo visible al empezar el gesto (los bordes se leen enseguida). */
            var edgesAtStart: Deferred<PageEdges?>? = null
            var startHref: String? = null

            override fun onDrag(event: DragEvent): Boolean {
                if (event.type != DragEvent.Type.Start) vm.onDrag(event.offset.y.toDouble())
                // Paso de capítulo SOLO en el borde: Readium ya no lo hace por su cuenta (ReaderActivity enciende
                // disablePageTurnsWhileScrolling); si la página ya estaba abajo del todo y el dedo sigue subiendo,
                // se pasa al siguiente; arriba del todo y bajando, al final del anterior. Solo en libros que se
                // desplazan: en los de diseño fijo cada página cabe entera y cualquier gesto pasaría de capítulo.
                if (fixedLayout) return false
                when (event.type) {
                    DragEvent.Type.Start -> {
                        val now = SystemClock.uptimeMillis()
                        if (!ReaderRules.dragAllowed(chapterTurn.turningTo, currentHref, now - chapterTurn.turnedAt)) {
                            edgesAtStart = null
                            return false
                        }
                        chapterTurn.turningTo = null
                        startHref = currentHref
                        // Sin esperar al despachador: los bordes se leen antes de que la página se mueva más.
                        edgesAtStart = scope.async(start = CoroutineStart.UNDISPATCHED) { bridge.edges() }
                    }
                    DragEvent.Type.End -> {
                        val edges = edgesAtStart ?: return false
                        edgesAtStart = null
                        val href = startHref ?: return false
                        val dy = event.offset.y.toDouble()
                        val minDy = ReaderRules.CHAPTER_TURN_MIN_DP * pageDensity.density
                        scope.launch {
                            val step = ReaderRules.chapterStep(edges.await(), dy, minDy)
                            if (step == 0 || href != currentHref) return@launch
                            stepChapter(href, step)
                        }
                    }
                    else -> Unit
                }
                return false // Readium sigue desplazando el texto.
            }

            // Toque (también el toque doble de TalkBack, que llega en el centro del párrafo). La página se lee de forma
            // asíncrona, así que no se sabe aquí si había un párrafo: se devuelve false y Readium sigue igual (no hay
            // otros oyentes de toque). Ojo: si algún día se suma un DirectionalNavigationAdapter, también pasaría de
            // página con los toques que traducen.
            override fun onTap(event: TapEvent): Boolean {
                val href = currentHref ?: return false
                val x = event.point.x
                val y = event.point.y
                val density = pageDensity.density
                scope.launch {
                    val hit = bridge.paragraphsAt(x, y, density)
                    if (href != currentHref) return@launch
                    if (hit.isNotEmpty()) {
                        val had = vm.cardState(href, hit[0].index) != null
                        vm.onTap(href, hit)
                        val has = vm.cardState(href, hit[0].index) != null
                        // Solo si de verdad se abrió o cerró (un párrafo sin texto normalizado no abre nada).
                        if (had && !has) view.announce(announceHidden) else if (!had && has) view.announce(announceTranslating)
                        return@launch
                    }
                    // ¿Sobre una tarjeta? (find no la cuenta como párrafo.)
                    val index = bridge.indexAt(x, y, density) ?: return@launch
                    when (vm.cardState(href, index)) {
                        null -> Unit
                        is CardState.Failed -> {
                            vm.retry(href, index)
                            view.announce(announceTranslating)
                        }
                        is CardState.MissingModel -> languagesOpen = true
                        else -> {
                            vm.onTap(href, listOf(PageParagraph(index, "")))
                            view.announce(announceHidden)
                        }
                    }
                }
                return false
            }
        }
        nav?.addInputListener(listener)
        onDispose { nav?.removeInputListener(listener) }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidFragment<EpubNavigatorFragment>(
            // El texto nunca queda bajo la barra de estado ni la de gestos.
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        ) { nav ->
            // Un fragmento nuevo nace con las preferencias de la fábrica (las del arranque).
            if (nav !== navigator) {
                navigator = nav
                appliedPreferences = startPreferences
            }
        }

        AnimatedVisibility(
            visible = barsVisible,
            enter = if (reduceMotion) EnterTransition.None else slideInVertically { -it } + fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                title = title,
                direction = direction,
                onBack = onBack,
                onSettings = { settingsOpen = true },
                onDirection = { directionOpen = true },
                onToc = { tocOpen = true },
            )
        }
        AnimatedVisibility(
            visible = barsVisible && (positionText(label) != null || hasPrevious || hasNext),
            enter = if (reduceMotion) EnterTransition.None else slideInVertically { it } + fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                label = label,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onPrevious = { onChapterButton(-1) },
                onNext = { onChapterButton(1) },
            )
        }

        // Idiomas a pantalla completa sobre el Lector (desde una tarjeta "Falta el idioma"): el libro sigue abierto debajo.
        if (languagesOpen) {
            val close = {
                languagesOpen = false
                vm.onLanguagesClosed() // suelta el motor y vuelve a pedir las tarjetas sin modelo
            }
            BackHandler(onBack = close)
            LanguagesScreen(app.hub, app.settings, onBack = close, cache = app.translations)
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

    if (directionOpen) {
        // "Traducir de español a inglés": TalkBack lo dice al elegir (el botón de la barra ya cambió).
        val changed = ReaderDirections.associateWith {
            stringResource(R.string.reader_direction_changed, languageName(it.source), languageName(it.target))
        }
        DirectionSheet(
            current = direction,
            onSelect = { pair ->
                directionOpen = false
                if (pair != direction) {
                    vm.setDirection(pair) // guarda para el libro y cierra las tarjetas abiertas
                    changed[pair]?.let(view::announce)
                }
            },
            onDismiss = { directionOpen = false },
        )
    }

    if (settingsOpen) {
        val resetDone = stringResource(R.string.reading_reset_done)
        ReadingSettingsSheet(
            settings = readingSettings,
            onChange = vm::setReadingSettings, // el libro cambia enseguida (efecto de arriba)
            onStepScale = vm::stepFontScale,
            onReset = {
                vm.resetReadingSettings()
                view.announce(resetDone) // sin snackbar: no tapa la hoja
            },
            onDismiss = { settingsOpen = false },
        )
    }

    // Mientras Idiomas tapa el libro, TalkBack no debe entrar en la página de debajo.
    LaunchedEffect(languagesOpen, navigator) {
        navigator?.view?.importantForAccessibility =
            if (languagesOpen) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
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

/**
 * Barra superior: volver, título del libro (una línea), "Aa" (ajustes de lectura), dirección de traducción
 * ("EN → ES") e Índice. Con letra grande el título cede espacio: los botones nunca se recortan ni se esconden.
 * Sin estado: se previsualiza sola.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    title: String?,
    direction: LanguagePair,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onDirection: () -> Unit,
    onToc: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = {
            Text(
                LibraryRules.title(title) ?: stringResource(R.string.library_untitled),
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
            ReadingSettingsButton(onSettings)
            DirectionButton(direction, onDirection)
            IconButton(onClick = onToc, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(LectorIcons.Toc), contentDescription = stringResource(R.string.reader_toc))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier,
    )
}

/** Textos de las tarjetas; "Falta el idioma" lleva el tamaño del modelo según el catálogo (si ya se conoce). */
@Composable
private fun rememberCardTexts(app: LectorApp, direction: LanguagePair): CardTexts {
    val pairs by app.hub.pairs.collectAsStateWithLifecycle()
    val wire = TranslationRules.wire(direction)
    val name = pairDirection(wire).withNoBreakArrow()
    val missing = stringResource(R.string.reader_card_missing)
    val missingNoSize = stringResource(R.string.reader_card_missing_nosize, name)
    val source = languageName(direction.source)
    val target = languageName(direction.target)
    val resources = LocalResources.current
    val spokenNoSize = stringResource(R.string.reader_card_missing_spoken_nosize, source, target)
    val failed = stringResource(R.string.reader_card_failed)
    val prepareFailed = stringResource(R.string.reader_card_prepare_failed)
    val retry = stringResource(R.string.reader_card_retry)
    val download = stringResource(R.string.reader_card_download)
    val tooLong = stringResource(R.string.reader_card_too_long)
    return remember(pairs, wire, name, missing, missingNoSize, resources, spokenNoSize, failed, prepareFailed, retry, download, tooLong) {
        CardTexts(
            failed, prepareFailed, retry, download, tooLong,
            missing = { engine -> CardRules.modelMegabytes(pairs, wire, engine)?.let { mb -> missing.format(name, mb) } ?: missingNoSize },
            // Para TalkBack: "a" en vez de la flecha y "megabytes" en vez de "MB".
            missingSpoken = { engine ->
                CardRules.modelMegabytes(pairs, wire, engine)?.let { mb ->
                    resources.getQuantityString(R.plurals.reader_card_missing_spoken, mb.toInt(), source, target, mb)
                } ?: spokenNoSize
            },
        )
    }
}

/** Aviso de TalkBack ("Traduciendo…", "Traducción oculta"). Sin TalkBack no hace nada. */
@Suppress("DEPRECATION") // Sin alternativa para un aviso puntual dentro de un WebView (minSdk 26).
private fun View.announce(text: String) = announceForAccessibility(text)

/** Paso de capítulo nuestro aún sin asentar (ver ReaderRules.dragAllowed) y cuándo se pidió. */
private class ChapterTurn {
    var turningTo: String? = null
    var turnedAt = 0L
}

/** Hasta ~5 s esperando a que la página del recurso nuevo esté lista. */
private const val PageReadyTries = 50
private const val PageReadyDelayMs = 100L

/**
 * Barra inferior: "[<] La tormenta · 42 % [>]". Los botones (48 dp) pasan al capítulo anterior/siguiente y se
 * apagan en los extremos. El texto se lee tal cual (TalkBack lo anuncia).
 */
@Composable
fun ReaderBottomBar(
    label: PositionLabel,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = positionText(label)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(min = 48.dp)
                .padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = hasPrevious, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(LectorIcons.ChevronLeft), contentDescription = stringResource(R.string.reader_chapter_previous))
            }
            Box(Modifier.weight(1f).padding(vertical = Spacing.s), contentAlignment = Alignment.Center) {
                if (text != null) Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onNext, enabled = hasNext, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(LectorIcons.ChevronRight), contentDescription = stringResource(R.string.reader_chapter_next))
            }
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

/**
 * Pasa al capítulo vecino en el orden de lectura: al principio del siguiente o al FINAL del anterior (se cruzó el
 * borde de arriba). Devuelve el href del capítulo pedido, o null si no hay vecino (primer o último capítulo).
 */
private fun EpubNavigatorFragment.turnChapter(publication: Publication, href: String, step: Int, toEnd: Boolean = step < 0): String? {
    val order = publication.readingOrder.map { it.url().toString() }
    val i = ReaderRules.neighborChapter(order, href, step) ?: return null
    val start = publication.locatorFromLink(publication.readingOrder[i]) ?: return null
    return if (go(if (toEnd) start.copyWithLocations(progression = 1.0) else start)) order[i] else null
}

/** El enlace original del índice (con su `#fragmento`), para saltar justo al punto del capítulo. */
private fun List<Link>.findByHref(href: String): Link? {
    for (l in this) {
        if (l.url().toString() == href) return l
        l.children.findByHref(href)?.let { return it }
    }
    return null
}
