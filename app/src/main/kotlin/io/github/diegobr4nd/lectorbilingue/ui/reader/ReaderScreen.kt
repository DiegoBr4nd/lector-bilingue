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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
import io.github.diegobr4nd.lectorbilingue.data.ReadingMode
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // El recurso cuya página ya recibió el modo y su reposición (efecto de "página lista"). Hasta entonces la posición
    // no pide lo visible: así ninguna tarjeta de la guía llega a la página antes que setMode.
    var readyHref by remember { mutableStateOf<String?>(null) }
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
        stringResource(R.string.reader_card_original),
    )
    val currentLabels by rememberUpdatedState(labels)
    val announceTranslating by rememberUpdatedState(stringResource(R.string.reader_card_skeleton))
    val announceHidden by rememberUpdatedState(stringResource(R.string.reader_card_hidden))
    val announceTranslation by rememberUpdatedState(stringResource(R.string.reader_card_announce))
    // Ajustes de lectura (iguales para todos los libros) y modo del sistema, para "Como el teléfono".
    val readingSettings by vm.readingSettings.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val cardTheme by rememberUpdatedState(ReadingRules.cardTheme(readingSettings, systemDark))
    val currentSystemDark by rememberUpdatedState(systemDark)
    val notice by vm.chapterNotice.collectAsStateWithLifecycle()
    // Las últimas preferencias mandadas a Readium: si no cambian, no se vuelven a mandar (ni se mueve la página).
    var appliedPreferences by remember { mutableStateOf(startPreferences) }
    // Un envío de preferencias a la vez: quien llega segundo espera a que Readium aplique el primero.
    val preferencesLock = remember { Mutex() }

    /**
     * Manda a Readium los ajustes de lectura de ahora si cambiaron, y espera a que la página los aplique. Lo llaman el
     * efecto de los ajustes y, ANTES de cada tarjeta, la fila de tarjetas: en Solo traducción la tarjeta copia el
     * estilo del original en px y el ViewModel la repone apenas cambia un ajuste (revisión de la Tarea 3); puesta
     * antes de que Readium cambie la letra, copiaría el estilo viejo.
     */
    suspend fun syncPreferences() = preferencesLock.withLock {
        val nav = navigator ?: return@withLock
        val preferences = ReadingRules.preferences(vm.readingSettings.value, currentSystemDark)
        if (preferences == appliedPreferences) return@withLock
        appliedPreferences = preferences
        val saved = nav.currentLocator.value
        nav.submitPreferences(preferences)
        // Si un paso de capítulo nuestro aún no llegó (la posición guardada es del de antes), no se vuelve atrás.
        val sinceTurn = SystemClock.uptimeMillis() - chapterTurn.turnedAt
        if (ReadingRules.restoreAfterSubmit(saved.href.toString(), chapterTurn.turningTo, sinceTurn)) {
            nav.go(saved, animated = false)
        }
        // Readium pasa las preferencias a la página por sus propios flujos (en el hilo principal): se les da tiempo
        // para que su script llegue al WebView antes que los nuestros.
        delay(PreferencesApplyMs)
    }

    LaunchedEffect(touchExploration) { vm.setTouchExploration(touchExploration) }
    // Por si se abrió el Lector antes de que la Biblioteca leyera los ajustes (fuera del hilo principal).
    LaunchedEffect(Unit) { app.settings.loadReadingSettings() }
    // Ajustes al instante, sin recargar la página. Readium deja la página en el mismo píxel al cambiar el tamaño (y el
    // texto se corre), así que se guarda la posición antes y se vuelve a ella enseguida (spec §11, C3). La tarjeta
    // cambia de paleta con el atributo del tema (también lo pone la página al quedar lista, más abajo).
    LaunchedEffect(readingSettings, systemDark, navigator) {
        if (navigator == null) return@LaunchedEffect
        syncPreferences()
        bridge.setTheme(cardTheme)
    }
    // Al pausar (antes de onStop): si la persona vuelve y reabre enseguida, la Biblioteca ya lee la última posición.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.flush() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    // Al volver al frente se reponen las tarjetas (por si la página se recargó mientras tanto).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }

    // Tarjetas: se aplican durante toda la composición (no solo al frente: ninguna se pierde) y solo en el recurso
    // visible; las de otro recurso se reponen al volver a él (onResourceShown).
    // El ancla viene en cada op (Ruling F): en Tocar el propio párrafo (la tarjeta sale justo debajo del tocado); en
    // Intercalado y Solo traducción, lo que se lee en pantalla. El modo de la página llega en la misma fila, antes de
    // las tarjetas ya adaptadas. Una tarjeta Solo puede pedir además mostrar u ocultar el original.
    // Un solo receptor para toda la composición: la fila es un Channel y dos receptores se repartirían las ops.
    LaunchedEffect(vm) {
        vm.cardOps.collect { op ->
            // Primero los ajustes nuevos en la página (si cambiaron) y luego la tarjeta: así copia el estilo de ahora.
            syncPreferences()
            when (op) {
                is CardOp.Show -> if (op.resource == currentHref) {
                    bridge.show(op.index, CardRules.card(op.card, cardTexts), currentLabels, op.anchor)
                    CardRules.originalShown(op.card)?.let { bridge.showOriginal(op.index, it, op.anchor) }
                }
                is CardOp.Hide -> if (op.resource == currentHref) bridge.hide(op.index, op.anchor)
                is CardOp.RemoveWaiting -> if (op.resource == currentHref) bridge.hideWaiting()
                is CardOp.Mode -> {
                    bridge.setMode(ModeRules.pageMode(op.mode))
                    // Al entrar en un modo nuevo (sobre todo desde Tocar, donde el ViewModel olvidó lo visible), lo que
                    // se ve ahora. El ViewModel ya está en ese modo: emitió esta op.
                    // Página aún sin preparar: su efecto de "página lista" pedirá lo visible.
                    val href = currentHref
                    if (op.mode != ReadingMode.TAP && href != null && href == readyHref) {
                        vm.onVisible(href, bridge.visible(ModeRules.FOLLOWING))
                    }
                }
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
        readyHref = null // recurso nuevo o vuelta al frente: la página pudo recargarse sin el modo
        var tries = 0
        while (!bridge.hideAll(href) && tries++ < PageReadyTries) delay(PageReadyDelayMs)
        // El tema de la tarjeta va en cada página nueva, antes de reponer las tarjetas: así nunca salen con otro color.
        bridge.setTheme(cardTheme)
        // Y el modo (Tarea 4): una página recargada no lo tiene y sin él una tarjeta Solo no se pone. El ViewModel no
        // manda CardOp.Mode al reponer: va aquí, antes de onResourceShown.
        val mode = vm.readingSettings.value.mode
        bridge.setMode(ModeRules.pageMode(mode))
        // Aun si la página nunca respondió, el ViewModel debe saber cuál es el recurso visible.
        vm.onResourceShown(href)
        readyHref = href
        // En Intercalado y Solo traducción se pide lo que se ve (y los siguientes) apenas la página está lista.
        if (mode != ReadingMode.TAP) vm.onVisible(href, bridge.visible(ModeRules.FOLLOWING))
    }

    // Posición (para la etiqueta y para guardar) y gesto (para las barras), solo mientras exista el navegador.
    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect { locator ->
            currentHref = locator.href.toString()
            vm.onPosition(locator.toPosition())
        }
    }
    // Intercalado y Solo traducción (spec §5.3): con cada posición nueva, ya quieta la página, lo que se ve y los
    // siguientes. Si la posición cambia antes de asentarse, la vuelta anterior se descarta (collectLatest).
    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collectLatest { locator ->
            if (vm.readingSettings.value.mode == ReadingMode.TAP) return@collectLatest
            delay(VisibleSettleMs)
            val href = locator.href.toString()
            // Un capítulo nuevo lo pide el efecto de "página lista", tras reponer sus tarjetas.
            if (href != currentHref || href != readyHref || vm.readingSettings.value.mode == ReadingMode.TAP) return@collectLatest
            vm.onVisible(href, bridge.visible(ModeRules.FOLLOWING))
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
                        val now = vm.cardState(href, hit[0].index)
                        // Solo si de verdad se abrió o cerró (un párrafo sin texto normalizado no abre nada). Lo que
                        // vuelve a verse con su texto (Intercalado: mostrar la oculta; Solo: alternar el original) lo
                        // anuncia el ViewModel ("Traducción: …", "Original"); aquí solo la espera.
                        if (had && now == null) view.announce(announceHidden)
                        else if (!had && now != null && now.isWaiting()) view.announce(announceTranslating)
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
                            // Tocar la tarjeta es tocar su párrafo (contrato del ViewModel: sin texto). En Tocar la
                            // cierra, en Intercalado la oculta y en Solo traducción alterna el original (eso lo anuncia
                            // el ViewModel).
                            vm.onTap(href, listOf(PageParagraph(index, "")))
                            if (vm.cardState(href, index) == null) view.announce(announceHidden)
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

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            AnimatedVisibility(
                visible = barsVisible,
                enter = if (reduceMotion) EnterTransition.None else slideInVertically { -it } + fadeIn(),
                exit = if (reduceMotion) ExitTransition.None else slideOutVertically { -it } + fadeOut(),
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
            // Aviso único del capítulo (boceto 5a §4): bajo la barra, o arriba del todo si la barra se ocultó. Solo en
            // los modos nuevos (en Tocar cada párrafo lleva su propia tarjeta de la 3b).
            val shown = notice.takeIf { readingSettings.mode != ReadingMode.TAP }
            if (shown != null) {
                ChapterNoticeBand(
                    texts = noticeTexts(shown, cardTexts),
                    onAction = {
                        when (shown) {
                            // Idiomas sobre el Lector (el de la 3b); al cerrarlo, vm.onLanguagesClosed() quita el aviso.
                            is ChapterNotice.MissingModel -> languagesOpen = true
                            ChapterNotice.EngineFailed -> currentHref?.let(vm::retryChapter)
                        }
                    },
                    modifier = if (barsVisible) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars),
                )
            }
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
            onChange = vm::updateReadingSettings, // el libro cambia enseguida (efecto de arriba)
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

/** Lo que dice el aviso del capítulo: el texto, lo que oye TalkBack, el botón (o ninguno) y si es un fallo. */
data class NoticeTexts(val message: String, val spoken: String, val action: String?, val error: Boolean)

/** Textos del aviso: los mismos de las tarjetas de la 3b ("Falta el idioma… (227 MB)", "No se pudo preparar…"). */
internal fun noticeTexts(notice: ChapterNotice, texts: CardTexts): NoticeTexts = when (notice) {
    is ChapterNotice.MissingModel ->
        NoticeTexts(texts.missing(notice.engine), texts.missingSpoken(notice.engine), texts.download, error = false)
    ChapterNotice.EngineFailed -> NoticeTexts(texts.prepareFailed, texts.prepareFailed, texts.retry, error = true)
}

/**
 * Aviso único del capítulo en Intercalado y Solo traducción (boceto 5a §4): una banda bajo la barra superior.
 * - Fallo del motor: `errorContainer` con "Reintentar"; falta el idioma: `secondaryContainer` con "Descargar" (no es
 *   un fallo). Ícono + texto + botón: nunca solo el color.
 * - Botón de 48 dp a la derecha; con letra grande (poco ancho por letra) baja debajo del texto. El texto no se corta.
 * - TalkBack: el texto es una región viva (se anuncia al aparecer, con "a" y "megabytes"); el botón va aparte.
 * Sin estado: se previsualiza sola.
 */
@Composable
fun ChapterNoticeBand(texts: NoticeTexts, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val container = if (texts.error) colors.errorContainer else colors.secondaryContainer
    val content = if (texts.error) colors.onErrorContainer else colors.onSecondaryContainer
    Surface(color = container, contentColor = content, modifier = modifier.fillMaxWidth()) {
        Column {
            BoxWithConstraints(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(start = Spacing.l, end = Spacing.s, top = Spacing.xs, bottom = Spacing.xs),
            ) {
                // Ancho "a letra normal": con letra al 200 % cada texto ocupa el doble (la misma idea que la hoja "Aa").
                val stacked = maxWidth / LocalDensity.current.fontScale < NoticeRowMin
                val message = @Composable { m: Modifier ->
                    Row(
                        m.clearAndSetSemantics {
                            contentDescription = texts.spoken
                            liveRegion = LiveRegionMode.Polite
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    ) {
                        Icon(
                            painterResource(if (texts.error) LectorIcons.Error else LectorIcons.Download),
                            contentDescription = null, // decorativo: el texto lo dice
                            modifier = Modifier.size(24.dp),
                        )
                        // Máximo 2 líneas (con letra al 200 % la banda no tapa media página): lo completo lo dice la descripción.
                        Text(texts.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                val button = @Composable {
                    if (texts.action != null) {
                        TextButton(
                            onClick = onAction,
                            colors = ButtonDefaults.textButtonColors(contentColor = content),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(texts.action, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                if (stacked) {
                    Column {
                        message(Modifier.fillMaxWidth().padding(vertical = Spacing.xs))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { button() }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        message(Modifier.weight(1f).padding(vertical = Spacing.xs))
                        button()
                    }
                }
            }
            // Borde inferior: en oscuro la banda no se funde con la barra ni con la página.
            HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
        }
    }
}

/** Debajo de esto (dp a letra normal) el texto del aviso y su botón no caben en una fila: el botón baja. */
private val NoticeRowMin = 180.dp

/** Estados de espera de una tarjeta: "Traduciendo…", "Preparando…" o, en Solo traducción, el original en gris. */
private fun CardState.isWaiting() = this == CardState.Skeleton || this == CardState.Preparing || this == CardState.Waiting

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
    val waiting = stringResource(R.string.reader_card_skeleton)
    val original = stringResource(R.string.reader_card_original)
    return remember(pairs, wire, name, missing, missingNoSize, resources, spokenNoSize, failed, prepareFailed, retry, download, tooLong, waiting, original) {
        CardTexts(
            failed, prepareFailed, retry, download, tooLong, waiting, original,
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

/** Espera tras mandar preferencias a Readium, para que su script llegue a la página antes que los nuestros. */
private const val PreferencesApplyMs = 120L

/** Página "quieta": tanto sin posición nueva antes de pedir lo visible (Intercalado y Solo traducción). */
private const val VisibleSettleMs = 150L

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
