package io.github.diegobr4nd.lectorbilingue.ui.about

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.components.LectorIcons
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ButtonShape
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.ReadingFontFamily
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing
import io.github.diegobr4nd.lectorbilingue.ui.BottomInsetSpacer
import io.github.diegobr4nd.lectorbilingue.ui.ScreenFrame
import io.github.diegobr4nd.lectorbilingue.ui.tips.openExternal
import io.github.diegobr4nd.lectorbilingue.ui.tips.startSafely

const val OPUS_EN_ES_URL = "https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-en-es"
const val OPUS_ES_EN_URL = "https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-cat_oci_spa-en"
const val CC_BY_40_URL = "https://creativecommons.org/licenses/by/4.0/"
const val FIREFOX_TRANSLATIONS_URL = "https://github.com/mozilla/translations"

/**
 * «Acerca de» (spec 6a §3): nombre y versión, la frase de privacidad, «Enviar comentarios», la atribución de los
 * modelos (CC-BY-4.0 la exige visible) y la entrada a «Licencias». Sin sección de código fuente (va en «Licencias»).
 * [startMail] devuelve false si no hay app de correo: entonces la dirección queda escrita para copiarla.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    modifier: Modifier = Modifier,
    info: DeviceInfo = remember { currentDeviceInfo() },
    startMail: ((Intent) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val start = startMail ?: { intent: Intent -> startSafely(context, intent) }
    var noMailApp by rememberSaveable { mutableStateOf(false) }
    val appName = stringResource(R.string.app_name)
    val subject = stringResource(R.string.feedback_subject)
    val body = stringResource(R.string.feedback_body)
    ScreenFrame(modifier) {
        TitleRow(stringResource(R.string.about_title), onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                // El círculo de marca, como el ícono del teléfono: el libro blanco solo no se ve sobre el fondo crema.
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(colorResource(R.color.ic_launcher_background)),
                    contentAlignment = Alignment.Center,
                ) {
                    // La capa del ícono adaptable trae 1/3 de margen alrededor: se dibuja más grande y el círculo la recorta.
                    Image(
                        painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.requiredSize(84.dp),
                    )
                }
                Column {
                    Text(appName, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = ReadingFontFamily))
                    Text(
                        stringResource(R.string.about_version, info.versionName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = { noMailApp = !start(feedbackIntent(feedbackMail(appName, subject, body, info))) },
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.about_feedback)) }
            if (noMailApp) {
                // La dirección va en su propia línea: con letra grande no se parte a mitad de palabra. Una sola región
                // «polite» para las dos líneas: TalkBack lee el aviso y la dirección juntos, una vez.
                SelectionContainer {
                    Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                        Text(stringResource(R.string.about_feedback_no_app), style = MaterialTheme.typography.bodyMedium)
                        Text(FEEDBACK_EMAIL, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Text(
                stringResource(R.string.about_models_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.about_model_opus), style = MaterialTheme.typography.bodyMedium)
            // Los enlaces ya no traen relleno a los lados: el espacio entre ellos lo pone la fila.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                LinkButton(stringResource(R.string.about_model_opus_en_es)) { openExternal(context, OPUS_EN_ES_URL) }
                LinkButton(stringResource(R.string.about_model_opus_es_en)) { openExternal(context, OPUS_ES_EN_URL) }
                LinkButton(stringResource(R.string.about_model_opus_license)) { openExternal(context, CC_BY_40_URL) }
            }
            Text(stringResource(R.string.about_model_firefox), style = MaterialTheme.typography.bodyMedium)
            LinkButton(stringResource(R.string.about_model_firefox_open)) { openExternal(context, FIREFOX_TRANSLATIONS_URL) }
            OutlinedButton(
                onClick = onLicenses,
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.about_licenses), textAlign = TextAlign.Center) }
            BottomInsetSpacer()
        }
    }
}

/**
 * Botón de texto que abre el navegador; TalkBack dice «Abrir <nombre> en el navegador, botón». Sin relleno a los lados:
 * el texto queda alineado con los párrafos de encima (la altura táctil sigue siendo de 48 dp).
 */
@Composable
internal fun LinkButton(label: String, onClick: () -> Unit) {
    val desc = stringResource(R.string.free_books_open_desc, label)
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 0.dp),
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc },
    ) { Text(label) }
}

/** Fila de arriba con atrás y título (igual que «Cómo se usa»). */
@Composable
internal fun TitleRow(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(LectorIcons.ArrowBack), contentDescription = stringResource(R.string.languages_back))
        }
        Spacer(Modifier.width(Spacing.s))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = ReadingFontFamily),
            modifier = Modifier.semantics { heading() },
        )
    }
}
