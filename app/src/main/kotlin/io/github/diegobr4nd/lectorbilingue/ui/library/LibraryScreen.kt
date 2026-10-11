package io.github.diegobr4nd.lectorbilingue.ui.library

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.diegobr4nd.lectorbilingue.LectorApp
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.books.Book
import io.github.diegobr4nd.lectorbilingue.core.ui.R as UiR
import io.github.diegobr4nd.lectorbilingue.core.ui.components.ConfirmDialog
import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.core.ui.components.LectorIcons
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ButtonShape
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.ui.LoadingLine
import io.github.diegobr4nd.lectorbilingue.ui.ScreenMaxWidth
import io.github.diegobr4nd.lectorbilingue.ui.pairDirection
import io.github.diegobr4nd.lectorbilingue.ui.rememberReduceMotion
import io.github.diegobr4nd.lectorbilingue.ui.withNoBreakArrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Biblioteca: la pantalla de inicio. Lista los libros, añade con el selector del sistema (sin permisos de
 * almacenamiento), abre y borra. [onDeveloper] es null en la versión de la tienda.
 */
@Composable
fun LibraryScreen(
    app: LectorApp,
    onLanguages: () -> Unit,
    onDeveloper: (() -> Unit)?,
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { app.settings.loadEnginePreference() } // Primera lectura fuera del hilo principal.
    // Los ajustes de lectura, listos antes de abrir un libro (el Lector arranca con ellos).
    LaunchedEffect(Unit) { app.settings.loadReadingSettings() }
    val vm: LibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    app.books,
                    opener = app.bookOpener::open,
                    languageNotices(app.hub, app.settings),
                    close = app.openBooks::close,
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val openBookNow by rememberUpdatedState(onOpenBook)
    var failedId by rememberSaveable { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        // Ni abrir el archivo ni leer su nombre ocurren aquí (hilo principal): el ViewModel lo hace en segundo plano.
        vm.import(uri?.let { u -> { resolver.openInputStream(u) } }, name = { uri?.let { displayName(appContext, it) } })
    }

    // Eventos solo con la pantalla visible: si llegan en segundo plano esperan en el canal.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) {
                    // En su propia corrutina: un snackbar en pantalla no retrasa abrir un libro.
                    is LibraryEvent.Message -> launch { snackbar.showSnackbar(resources.getString(event.message.textRes())) }
                    is LibraryEvent.Open -> openBookNow(event.bookId)
                    is LibraryEvent.OpenFailed -> failedId = event.bookId
                }
            }
        }
    }

    LibraryContent(
        state = state,
        onAdd = { picker.launch(arrayOf("application/epub+zip")) },
        onOpen = vm::open,
        onDelete = vm::delete,
        onLanguages = onLanguages,
        onDeveloper = onDeveloper,
        snackbar = snackbar,
        modifier = modifier,
    )
    failedId?.let { id ->
        OpenFailedDialog(
            onClose = { failedId = null },
            onRemove = {
                failedId = null
                vm.delete(id)
            },
        )
    }
}

/** Nombre del archivo elegido, para usarlo como título si el libro no trae uno. Nunca se registra. */
private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

/** La Biblioteca sin estado propio (salvo menús y diálogos): se dibuja igual con datos de verdad o de muestra. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    state: LibraryUiState,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onLanguages: () -> Unit,
    onDeveloper: (() -> Unit)?,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val empty = state.loaded && state.books.isEmpty() && !state.importing
    val reduceMotion = rememberReduceMotion()

    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.library_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = ReadingFontFamily),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                actions = { OverflowMenu(onLanguages, onDeveloper) },
                // En reposo, del color del fondo: sin franja de otro tono. Al desplazar la lista sí cambia (se nota el borde).
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            // Con la lista vacía el botón grande del centro hace lo mismo; mientras añade, no se puede añadir otro.
            if (state.loaded && !empty && !state.importing) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(painterResource(LectorIcons.Add), contentDescription = null) },
                    text = { Text(stringResource(R.string.library_add)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        val direction = LocalLayoutDirection.current
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = ScreenMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = inner.calculateStartPadding(direction),
                    end = inner.calculateEndPadding(direction),
                    top = inner.calculateTopPadding() + Spacing.s,
                    // Aire para que el botón flotante no tape el último libro.
                    bottom = inner.calculateBottomPadding() + 96.dp,
                ),
            ) {
                if (!state.loaded) {
                    item(key = "loading") {
                        LoadingLine(stringResource(R.string.library_loading), Modifier.padding(horizontal = Spacing.l))
                    }
                    return@LazyColumn
                }
                state.notice?.let { notice ->
                    item(key = "notice") { NoticeCard(notice, onLanguages, Modifier.animatedItem(this, reduceMotion)) }
                }
                if (empty) {
                    item(key = "empty") { EmptyLibrary(onAdd) }
                }
                if (state.importing) {
                    item(key = "importing") { ImportingRow(Modifier.animatedItem(this, reduceMotion)) }
                }
                for (book in state.books) {
                    item(key = book.id) {
                        BookRow(
                            book = book,
                            opening = state.openingId == book.id,
                            onOpen = { onOpen(book.id) },
                            onDelete = { deletingId = book.id }.takeIf { state.openingId != book.id },
                            modifier = Modifier.animatedItem(this, reduceMotion),
                        )
                    }
                }
            }
        }
    }

    deletingId?.let { id ->
        val book = state.books.firstOrNull { it.id == id }
        // Un libro que se está abriendo no se borra (el Lector lo recibiría ya borrado).
        if (book == null || state.openingId == id) {
            deletingId = null
        } else {
            ConfirmDialog(
                title = stringResource(R.string.library_delete_title, displayTitle(book)),
                body = stringResource(R.string.library_delete_body),
                confirmLabel = stringResource(R.string.library_delete_confirm),
                onConfirm = {
                    deletingId = null
                    onDelete(id)
                },
                onDismiss = { deletingId = null },
                destructive = true,
            )
        }
    }
}

/** Con "quitar animaciones" las filas aparecen y se van sin animar. */
private fun Modifier.animatedItem(scope: LazyItemScope, reduceMotion: Boolean): Modifier =
    if (reduceMotion) this else with(scope) { this@animatedItem.animateItem() }

@Composable
private fun OverflowMenu(onLanguages: () -> Unit, onDeveloper: (() -> Unit)?) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(LectorIcons.MoreVert), contentDescription = stringResource(R.string.library_menu))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_menu_languages)) },
                leadingIcon = { Icon(painterResource(LectorIcons.Translate), contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onLanguages()
                },
            )
            if (onDeveloper != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_menu_developer)) },
                    leadingIcon = { Icon(painterResource(LectorIcons.Tune), contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDeveloper()
                    },
                )
            }
        }
    }
}

@Composable
private fun engineLabel(kind: EngineKind): String =
    stringResource(if (kind == EngineKind.QUALITY) UiR.string.engine_quality else UiR.string.engine_fast)

/** Texto del aviso de idiomas. El par va en minúscula porque está dentro de una frase. */
@Composable
internal fun noticeText(notice: LanguageNotice): String = when (notice) {
    is LanguageNotice.Downloading -> {
        val pair = pairDirection(notice.pair).withNoBreakArrow()
        if (notice.percent != null) {
            stringResource(R.string.library_notice_downloading, pair, notice.percent)
        } else {
            stringResource(R.string.library_notice_downloading_nopct, pair)
        }
    }
    is LanguageNotice.Failed -> stringResource(R.string.library_notice_failed, pairDirection(notice.pair).withNoBreakArrow())
    is LanguageNotice.Missing ->
        stringResource(R.string.library_notice_missing, engineLabel(notice.kind), pairDirection(notice.pair).withNoBreakArrow())
    LanguageNotice.NoLanguages -> stringResource(R.string.library_notice_none)
}

/** Aviso de idiomas: una tarjeta que lleva a Idiomas. Ícono y texto, nunca solo color. */
@Composable
private fun NoticeCard(notice: LanguageNotice, onLanguages: () -> Unit, modifier: Modifier = Modifier) {
    val failed = notice is LanguageNotice.Failed
    @DrawableRes val icon = when (notice) {
        is LanguageNotice.Downloading, is LanguageNotice.Missing -> LectorIcons.Download
        is LanguageNotice.Failed -> LectorIcons.Error
        LanguageNotice.NoLanguages -> LectorIcons.Translate
    }
    val openLabel = stringResource(R.string.library_notice_action)
    // Card sin onClick: el clic va en el modificador para poder decirle a TalkBack qué hace ("abrir Idiomas").
    Card(
        colors = if (failed) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        },
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = openLabel, onClick = onLanguages),
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Text(noticeText(notice), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Biblioteca vacía: qué hacer y por qué es seguro, con un botón grande.
 * Con la letra grande (desde 150 %) no hay dibujo y el botón va justo debajo del título, antes de la explicación:
 * así "Añadir libro" se ve sin desplazar también en un teléfono pequeño con el aviso de idiomas arriba.
 */
@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    val largeText = LocalDensity.current.fontScale >= LargeFontScale
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = if (largeText) Spacing.l else Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        if (!largeText) {
            Icon(
                painterResource(LectorIcons.LibraryBooks),
                contentDescription = null, // Decorativo: el título dice lo mismo.
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = ReadingFontFamily),
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (largeText) AddBookButton(onAdd)
        Text(
            stringResource(R.string.library_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!largeText) {
            Spacer(Modifier.height(Spacing.s))
            AddBookButton(onAdd)
        }
    }
}

/** Desde esta escala de letra la Biblioteca vacía se compacta (ver [EmptyLibrary]). */
private const val LargeFontScale = 1.5f

@Composable
private fun AddBookButton(onAdd: () -> Unit) {
    Button(onClick = onAdd, shape = ButtonShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(painterResource(LectorIcons.Add), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(Spacing.s))
        Text(stringResource(R.string.library_add))
    }
}

/** Fila provisional mientras se añade un libro. TalkBack la anuncia con calma. */
@Composable
private fun ImportingRow(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Surface(
            shape = CoverShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(CoverWidth, CoverHeight),
        ) {}
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(R.string.library_importing), style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private val CoverWidth = 52.dp
private val CoverHeight = 76.dp
private val CoverShape = RoundedCornerShape(4.dp)

@Composable
private fun displayTitle(book: Book): String = LibraryRules.title(book.title) ?: stringResource(R.string.library_untitled)

/**
 * Un libro: portada, título, autor y cuánto se leyó. Tocar abre; mantener presionado (o la acción de TalkBack)
 * ofrece borrar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(book: Book, opening: Boolean, onOpen: () -> Unit, onDelete: (() -> Unit)?, modifier: Modifier = Modifier) {
    val title = displayTitle(book)
    val deleteLabel = stringResource(R.string.library_delete)
    val openingLabel = stringResource(R.string.library_opening)
    val openLabel = stringResource(R.string.library_open_action)
    val percent = LibraryRules.percent(book.progress)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .combinedClickable(
                onClick = onOpen,
                onClickLabel = openLabel,
                onLongClick = onDelete,
                onLongClickLabel = deleteLabel.takeIf { onDelete != null },
            )
            .semantics {
                if (onDelete != null) customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
                if (opening) stateDescription = openingLabel
            }
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Cover(book.coverFile, title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.author?.let { author ->
                Text(
                    author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // La barra es solo visual: el porcentaje se anuncia con el texto de abajo.
            LinearProgressIndicator(
                progress = { book.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs).clearAndSetSemantics {},
            )
            Text(
                stringResource(R.string.library_progress, percent),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (opening) {
            CircularProgressIndicator(Modifier.size(24.dp).clearAndSetSemantics {}, strokeWidth = 3.dp)
        }
    }
}

/**
 * Portada del libro, o un recuadro con la inicial si no tiene o no se puede leer.
 * Decorativa para TalkBack: el título ya está al lado; "Portada de…" lo repetía y la inicial suelta no dice nada.
 */
@Composable
private fun Cover(file: File?, title: String) {
    val targetPx = with(LocalDensity.current) { CoverHeight.roundToPx() }
    val image by produceState<ImageBitmap?>(null, file, targetPx) {
        value = file?.let { withContext(Dispatchers.IO) { decodeCover(it, targetPx) } }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(CoverWidth, CoverHeight).clip(CoverShape),
        )
    } else {
        Surface(
            shape = CoverShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(CoverWidth, CoverHeight).clearAndSetSemantics {},
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(LibraryRules.initial(title), style = MaterialTheme.typography.titleLarge, maxLines = 1)
            }
        }
    }
}

/** Lee la portada reducida a la altura que se muestra (no a tamaño completo). Cualquier problema = sin portada. */
private fun decodeCover(file: File, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/** El libro guardado no abrió (dañado o borrado por fuera): se puede quitar de la lista. */
@Composable
internal fun OpenFailedDialog(onClose: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(painterResource(LectorIcons.Error), contentDescription = null) },
        title = { Text(stringResource(R.string.library_open_failed_title)) },
        text = { Text(stringResource(R.string.library_open_failed_body)) },
        confirmButton = {
            TextButton(onClick = onRemove, shape = ButtonShape, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.library_open_failed_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, shape = ButtonShape, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.library_close))
            }
        },
    )
}

/** Texto de cada mensaje de importación. */
internal fun LibraryMessage.textRes(): Int = when (this) {
    LibraryMessage.NOT_EPUB -> R.string.library_error_not_epub
    LibraryMessage.TOO_BIG -> R.string.library_error_too_big
    LibraryMessage.DRM -> R.string.library_error_drm
    LibraryMessage.DAMAGED -> R.string.library_error_damaged
    LibraryMessage.NO_SPACE -> R.string.library_error_no_space
}
