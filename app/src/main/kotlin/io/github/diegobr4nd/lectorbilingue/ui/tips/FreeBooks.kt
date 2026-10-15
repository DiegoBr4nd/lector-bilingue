package io.github.diegobr4nd.lectorbilingue.ui.tips

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.Spacing

const val GUTENBERG_URL = "https://www.gutenberg.org"
const val STANDARD_EBOOKS_URL = "https://standardebooks.org"

/**
 * Abre [url] en el navegador del teléfono. La app no se conecta: lo hace el navegador. Sin navegador → false y nada
 * más (el nombre de la página queda escrito para buscarlo a mano).
 */
fun openExternal(context: Context, url: String): Boolean = startSafely(context, browserIntent(context, url))

/** Empieza [intent]; si no hay app que la abra → false (la app no se cierra). */
internal fun startSafely(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

/**
 * La Intent de [openExternal]: ver [url] con una app que abra enlaces (CATEGORY_BROWSABLE, como un enlace de la web).
 * Desde una Activity el navegador se abre encima, como siempre; FLAG_ACTIVITY_NEW_TASK solo hace falta (y solo se
 * pone) si [context] no es una Activity, porque Android lo exige para empezar una Activity desde ahí.
 */
internal fun browserIntent(context: Context, url: String): Intent {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return intent
}

/** La Activity detrás de [this] (un Context puede venir envuelto), o null. */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c != null) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * "¿No tienes libros?" + dos botones de texto (≥ 48 dp) a Project Gutenberg y Standard Ebooks. Botones y no enlaces
 * dentro del texto: Compose no tiene rol de enlace y un enlace en línea no llega a 48 dp; TalkBack dice
 * "Abrir Project Gutenberg en el navegador, botón".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FreeBooks(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.free_books_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally)) {
            for ((name, url) in listOf(R.string.free_books_gutenberg to GUTENBERG_URL, R.string.free_books_standard to STANDARD_EBOOKS_URL)) {
                val label = stringResource(name)
                val desc = stringResource(R.string.free_books_open_desc, label)
                TextButton(
                    onClick = { openExternal(context, url) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc },
                ) { Text(label) }
            }
        }
    }
}
