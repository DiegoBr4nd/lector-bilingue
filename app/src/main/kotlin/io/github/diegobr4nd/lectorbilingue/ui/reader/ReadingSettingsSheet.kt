package io.github.diegobr4nd.lectorbilingue.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.components.LectorIcons
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.AtkinsonFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.InterFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.data.LineHeightLevel
import io.github.diegobr4nd.lectorbilingue.data.MarginLevel
import io.github.diegobr4nd.lectorbilingue.data.PageTheme
import io.github.diegobr4nd.lectorbilingue.data.ReadingFont
import io.github.diegobr4nd.lectorbilingue.data.ReadingSettings
import io.github.diegobr4nd.lectorbilingue.data.TextAlignChoice

/**
 * "Aa" de la barra del Lector: abre "Ajustes de lectura". Es texto, no ícono: crece con la letra del teléfono y el
 * botón nunca baja de 48 dp. TalkBack dice "Ajustes de lectura, botón" y la acción "Abrir ajustes de lectura".
 */
@Composable
internal fun ReadingSettingsButton(onClick: () -> Unit) {
    val description = stringResource(R.string.reading_title)
    val action = stringResource(R.string.reading_open_action)
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = Spacing.s),
        // Como DirectionButton: la acción con nombre reemplaza la del botón (la misma función).
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics {
            contentDescription = description
            onClick(label = action) { onClick(); true }
        },
    ) {
        Text(
            stringResource(R.string.reading_aa),
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = ReadingFontFamily),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** "Ajustes de lectura" en una hoja inferior. Cada cambio se aplica y se guarda al instante: no hay "Aplicar". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsSheet(
    settings: ReadingSettings,
    onChange: (ReadingSettings) -> Unit,
    onStepScale: (up: Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 560.dp, // En tableta, centrada y sin estirarse.
    ) {
        ReadingSettingsContent(settings, onChange, onStepScale, onReset)
    }
}

/** Colores reales de la página de cada tema (boceto 4a §3.1): las muestras se ven igual en la app clara u oscura. */
internal val ThemeSwatches = listOf(
    PageTheme.LIGHT to (Color(0xFFFFFFFF) to Color(0xFF121212)),
    PageTheme.SEPIA to (Color(0xFFFAF4E8) to Color(0xFF121212)),
    PageTheme.DARK to (Color(0xFF1E1E1E) to Color(0xFFE0E0E0)),
    PageTheme.BLACK to (Color(0xFF000000) to Color(0xFFE6E6E6)),
)

/**
 * Contenido de la hoja, sin la hoja (así se previsualiza y se prueba). Lo elegido va con ícono, negrita y
 * `stateDescription`: nunca solo con color. Se desplaza entera si con letra grande no cabe; con poco ancho por
 * letra (letra grande o pantalla angosta) las muestras pasan a 2 x 2 y los botones segmentados a lista.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingSettingsContent(
    settings: ReadingSettings,
    onChange: (ReadingSettings) -> Unit,
    onStepScale: (up: Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chosenM = stringResource(R.string.reading_chosen_m)
    val chosenF = stringResource(R.string.reading_chosen_f)
    val notChosenM = stringResource(R.string.reading_not_chosen_m)
    val notChosenF = stringResource(R.string.reading_not_chosen_f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Ancho útil "a letra normal": con letra al 200 % cada texto ocupa el doble.
        val perFont: Dp = (maxWidth - Spacing.l * 2) / LocalDensity.current.fontScale
        val swatchesPerRow = if (perFont >= SwatchRowMin) 4 else 2
        val stacked = perFont < SegmentedRowMin
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Spacing.l)) {
            Text(
                stringResource(R.string.reading_title),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = ReadingFontFamily),
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s).semantics { heading() },
            )

            // ----- Tema -----
            SectionHeading(stringResource(R.string.reading_theme))
            Column(Modifier.selectableGroup()) {
                ChoiceRow(
                    label = stringResource(R.string.reading_theme_system),
                    help = stringResource(R.string.reading_theme_system_help),
                    selected = settings.theme == PageTheme.SYSTEM,
                    stateLabel = if (settings.theme == PageTheme.SYSTEM) chosenM else notChosenM,
                    onClick = { onChange(settings.copy(theme = PageTheme.SYSTEM)) },
                )
                FlowRow(
                    maxItemsInEachRow = swatchesPerRow,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
                ) {
                    for ((theme, colors) in ThemeSwatches) {
                        val selected = settings.theme == theme
                        ThemeSwatch(
                            name = themeName(theme),
                            background = colors.first,
                            ink = colors.second,
                            selected = selected,
                            stateLabel = if (selected) chosenM else notChosenM,
                            onClick = { onChange(settings.copy(theme = theme)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ----- Tamaño de letra -----
            SectionHeading(stringResource(R.string.reading_size))
            SizeRow(settings.fontScale, onStepScale)
            HelpText(stringResource(R.string.reading_size_help))

            // ----- Fuente -----
            SectionHeading(stringResource(R.string.reading_font))
            Column(Modifier.selectableGroup()) {
                for (font in ReadingFont.entries) {
                    val selected = settings.font == font
                    val name = fontName(font)
                    val help = if (font == ReadingFont.ORIGINAL) stringResource(R.string.reading_font_original_help) else null
                    // La descripción reemplaza los textos de la fila: la ayuda va dentro para que TalkBack también la diga.
                    val spoken = stringResource(R.string.reading_font_option, name)
                    ChoiceRow(
                        label = name,
                        help = help,
                        selected = selected,
                        stateLabel = if (selected) chosenF else notChosenF,
                        fontFamily = fontFamilyOf(font),
                        spoken = if (help != null) stringResource(R.string.reading_spoken_with_help, spoken, help) else spoken,
                        onClick = { onChange(settings.copy(font = font)) },
                    )
                }
            }

            // ----- Interlineado, márgenes y alineación -----
            val lineHeading = stringResource(R.string.reading_line_height)
            SectionHeading(lineHeading)
            SingleChoice(
                heading = lineHeading,
                options = listOf(
                    LineHeightLevel.COMPACT to stringResource(R.string.reading_line_compact),
                    LineHeightLevel.NORMAL to stringResource(R.string.reading_line_normal),
                    LineHeightLevel.WIDE to stringResource(R.string.reading_line_wide),
                ),
                selected = settings.lineHeight,
                chosen = chosenM to notChosenM,
                stacked = stacked,
                onSelect = { onChange(settings.copy(lineHeight = it)) },
            )
            val marginsHeading = stringResource(R.string.reading_margins)
            SectionHeading(marginsHeading)
            SingleChoice(
                heading = marginsHeading,
                options = listOf(
                    MarginLevel.NARROW to stringResource(R.string.reading_margins_narrow),
                    MarginLevel.NORMAL to stringResource(R.string.reading_margins_normal),
                    MarginLevel.WIDE to stringResource(R.string.reading_margins_wide),
                ),
                selected = settings.margins,
                chosen = chosenM to notChosenM,
                stacked = stacked,
                onSelect = { onChange(settings.copy(margins = it)) },
            )
            val alignHeading = stringResource(R.string.reading_align)
            SectionHeading(alignHeading)
            SingleChoice(
                heading = alignHeading,
                options = listOf(
                    TextAlignChoice.START to stringResource(R.string.reading_align_start),
                    TextAlignChoice.JUSTIFY to stringResource(R.string.reading_align_justify),
                ),
                selected = settings.align,
                chosen = chosenF to notChosenF,
                stacked = stacked,
                onSelect = { onChange(settings.copy(align = it)) },
            )
            HelpText(stringResource(R.string.reading_align_help))

            // ----- Restablecer (sin confirmación: es reversible y solo toca la apariencia) -----
            val resetAction = stringResource(R.string.reading_reset_action)
            val canReset = !settings.isFactory
            TextButton(
                onClick = onReset,
                enabled = canReset,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.l, vertical = Spacing.s)
                    .heightIn(min = 48.dp)
                    .semantics { if (canReset) onClick(label = resetAction) { onReset(); true } },
            ) {
                Text(stringResource(R.string.reading_reset), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Debajo de esto (dp a letra normal) las cuatro muestras no caben con su nombre: pasan a 2 x 2. */
private val SwatchRowMin = 260.dp

/** Debajo de esto los segmentos ("Estrechos", "Justificado") se cortarían: pasan a lista. */
private val SegmentedRowMin = 300.dp

@Composable
private fun SectionHeading(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier
        .padding(start = Spacing.l, end = Spacing.l, top = Spacing.l, bottom = Spacing.xs)
        .semantics { heading() },
)

@Composable
private fun HelpText(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
)

/** El ✓ delante de lo elegido; sin elegir queda el hueco para alinear. */
@Composable
private fun CheckSlot(selected: Boolean) {
    if (selected) {
        Icon(
            painterResource(LectorIcons.CheckCircle),
            contentDescription = null, // stateDescription ya lo dice.
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
    } else {
        Spacer(Modifier.size(24.dp))
    }
}

/** Fila de una opción (48 dp mínimo): ✓, nombre (en [fontFamily] si se da) y ayuda opcional. */
@Composable
private fun ChoiceRow(
    label: String,
    help: String?,
    selected: Boolean,
    stateLabel: String,
    onClick: () -> Unit,
    fontFamily: FontFamily? = null,
    spoken: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics {
                if (spoken != null) contentDescription = spoken
                stateDescription = stateLabel
            }
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        CheckSlot(selected)
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge.let { if (fontFamily != null) it.copy(fontFamily = fontFamily) else it },
                fontWeight = if (selected) FontWeight.Bold else null,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (help != null) {
                Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Una muestra de tema: fondo y texto reales de la página, el nombre debajo (no depende del color). */
@Composable
private fun ThemeSwatch(
    name: String,
    background: Color,
    ink: Color,
    selected: Boolean,
    stateLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val spoken = stringResource(R.string.reading_theme_option, name)
    Column(
        modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = spoken
                stateDescription = stateLabel
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(background, shape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = shape,
                )
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.reading_aa), style = MaterialTheme.typography.titleMedium.copy(fontFamily = ReadingFontFamily), color = ink)
            if (selected) {
                // En el color del texto de la muestra: contrasta con su fondo en los cuatro temas.
                Icon(
                    painterResource(LectorIcons.CheckCircle),
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.xs).size(18.dp),
                )
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else null,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/** A− · 100 % · A+. Los botones se apagan en los topes (75 % y 250 %) y se quedan en su lugar. */
@Composable
private fun SizeRow(scale: Double, onStepScale: (up: Boolean) -> Unit) {
    val percent = ReadingRules.percent(scale)
    val spokenValue = stringResource(R.string.reading_size_spoken, percent)
    val smaller = stringResource(R.string.reading_size_smaller)
    val bigger = stringResource(R.string.reading_size_bigger)
    // Las "A" de los botones no crecen con la letra del teléfono: así la chica y la grande siempre se distinguen.
    val density = LocalDensity.current
    val smallA = with(density) { 14.dp.toSp() }
    val bigA = with(density) { 22.dp.toSp() }
    val glyph = stringResource(R.string.reading_size_glyph)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { onStepScale(false) },
            enabled = scale > ReadingSettings.MIN_SCALE + 1e-9,
            modifier = Modifier.size(48.dp).semantics { contentDescription = smaller },
        ) {
            Text(glyph, fontSize = smallA, fontWeight = FontWeight.SemiBold, fontFamily = InterFamily)
        }
        Text(
            stringResource(R.string.reading_size_value, percent),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = spokenValue
                liveRegion = LiveRegionMode.Polite // dice "110 por ciento" tras cada toque
            },
        )
        FilledTonalIconButton(
            onClick = { onStepScale(true) },
            enabled = scale < ReadingSettings.MAX_SCALE - 1e-9,
            modifier = Modifier.size(48.dp).semantics { contentDescription = bigger },
        ) {
            Text(glyph, fontSize = bigA, fontWeight = FontWeight.SemiBold, fontFamily = InterFamily)
        }
    }
}

/**
 * Una elección entre pocas opciones: botones segmentados de M3, o filas con ✓ si no caben ([stacked]).
 * [chosen]: "Elegido"/"No elegido" (o en femenino) para TalkBack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SingleChoice(
    heading: String,
    options: List<Pair<T, String>>,
    selected: T,
    chosen: Pair<String, String>,
    stacked: Boolean,
    onSelect: (T) -> Unit,
) {
    if (stacked) {
        Column(Modifier.selectableGroup()) {
            for ((value, label) in options) {
                val isSelected = value == selected
                ChoiceRow(
                    label = label,
                    help = null,
                    selected = isSelected,
                    stateLabel = if (isSelected) chosen.first else chosen.second,
                    spoken = stringResource(R.string.reading_option, heading, label),
                    onClick = { onSelect(value) },
                )
            }
        }
        return
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
        options.forEachIndexed { i, (value, label) ->
            val isSelected = value == selected
            val spoken = stringResource(R.string.reading_option, heading, label)
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = spoken
                    stateDescription = if (isSelected) chosen.first else chosen.second
                },
                label = {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun themeName(theme: PageTheme): String = stringResource(
    when (theme) {
        PageTheme.SYSTEM -> R.string.reading_theme_system
        PageTheme.LIGHT -> R.string.reading_theme_light
        PageTheme.SEPIA -> R.string.reading_theme_sepia
        PageTheme.DARK -> R.string.reading_theme_dark
        PageTheme.BLACK -> R.string.reading_theme_black
    },
)

@Composable
private fun fontName(font: ReadingFont): String = stringResource(
    when (font) {
        ReadingFont.ORIGINAL -> R.string.reading_font_original
        ReadingFont.LITERATA -> R.string.reading_font_literata
        ReadingFont.INTER -> R.string.reading_font_inter
        ReadingFont.ATKINSON -> R.string.reading_font_atkinson
    },
)

/** Cada nombre se escribe en su fuente; "Original del libro" en la de la interfaz (cada libro trae la suya). */
private fun fontFamilyOf(font: ReadingFont): FontFamily? = when (font) {
    ReadingFont.ORIGINAL -> null
    ReadingFont.LITERATA -> ReadingFontFamily
    ReadingFont.INTER -> InterFamily
    ReadingFont.ATKINSON -> AtkinsonFamily
}
