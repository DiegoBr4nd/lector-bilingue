package io.github.diegobr4nd.lectorbilingue.ui.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import io.github.diegobr4nd.lectorbilingue.LectorApp
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.AbsoluteUrl

/**
 * Lector en su propia actividad: Readium exige instalar su FragmentFactory (la "fábrica" que crea su pantalla de
 * lectura) ANTES de super.onCreate(), con el libro ya abierto. No exportada: solo la abre la Biblioteca.
 *
 * Depuración del WebView: Readium 3.4.0 llama `WebView.setWebContentsDebuggingEnabled(false)` al crear cada
 * WebView (su BuildConfig.DEBUG es false en la versión publicada), así que no hace falta apagarla aquí.
 */
class ReaderActivity : FragmentActivity() {
    /** Enlace externo que el libro pidió abrir; la pantalla pregunta antes. Solo http, https y mailto. */
    private val externalLink = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalReadiumApi::class)
    private val linkListener = object : EpubNavigatorFragment.Listener {
        override fun onExternalLinkActivated(url: AbsoluteUrl) {
            val text = url.toString()
            if (ReaderRules.externalLinkAllowed(text)) externalLink.value = text
        }
    }

    @OptIn(ExperimentalReadiumApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as LectorApp
        val decision = ReaderStart.decide(intent.getStringExtra(EXTRA_BOOK_ID)) { app.openBooks.get(it) != null }
        val publication = (decision as? ReaderStart.Decision.Show)?.let { app.openBooks.get(it.id) }
        if (decision !is ReaderStart.Decision.Show || publication == null) {
            // Sin estado guardado: así Android no intenta recrear el fragmento de Readium sin su fábrica.
            super.onCreate(null)
            finish()
            return
        }
        val id = decision.id
        // La posición guardada la leyó la Biblioteca (Room no se lee en el hilo principal).
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator = app.openBooks.initialLocator(id),
            initialPreferences = EpubPreferences(scroll = true),
            listener = linkListener,
        )
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LectorTheme {
                ReaderScreen(
                    app = app,
                    bookId = id,
                    publication = publication,
                    externalLink = externalLink,
                    onExternalDone = { externalLink.value = null },
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        // Al salir de verdad (no al girar la pantalla) se suelta el libro de la memoria.
        if (isFinishing) intent.getStringExtra(EXTRA_BOOK_ID)?.let { (application as LectorApp).openBooks.close(it) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        fun intent(context: Context, id: String): Intent = Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, id)
    }
}
