package io.github.diegobr4nd.lectorbilingue.ui.reader

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import io.github.diegobr4nd.lectorbilingue.LectorApp
import io.github.diegobr4nd.lectorbilingue.core.ui.theme.LectorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
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

    /** El libro que muestra ESTE Lector (id y objeto): al terminar suelta solo este, no uno reabierto después. */
    private var shown: Pair<String, Publication>? = null

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
        shown = id to publication
        val startPreferences = ReadingRules.preferences(app.settings.readingSettingsFlow.value, systemDark())
        // La posición guardada la leyó la Biblioteca (Room no se lee en el hilo principal).
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator = app.openBooks.initialLocator(id),
            // Los ajustes de lectura ya aplicados al abrir (la Biblioteca los leyó fuera del hilo principal; si aún no,
            // son los de fábrica y ReaderScreen los aplica en cuanto llegan): así no hay un reajuste visible al entrar.
            initialPreferences = startPreferences,
            listener = linkListener,
            // Readium sirve también assets/lector/ de la app en https://readium_assets/lector/ (se suma a su readium/):
            // ahí está la hoja de las tarjetas de traducción, que HtmlSanitizer enlaza tras la CSP (spec 3b §12, T2).
            // disablePageTurnsWhileScrolling: en el modo desplazamiento Readium cambia de capítulo con un gesto CORTO
            // (< ~200 px en vertical) que se desvíe > 42 px en horizontal, en cualquier punto del capítulo (al volver,
            // además, abre el anterior por el final). Se apaga, y el paso de capítulo al llegar al borde lo hace
            // ReaderScreen (ReaderRules.chapterStep). Ver fix/cambio-de-capitulo.
            configuration = EpubNavigatorFragment.Configuration(servedAssets = listOf("lector/.*")).apply {
                disablePageTurnsWhileScrolling = true
                declareReadingFonts()
            },
        )
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LectorTheme {
                ReaderScreen(
                    app = app,
                    bookId = id,
                    publication = publication,
                    startPreferences = startPreferences,
                    title = app.openBooks.title(id),
                    externalLink = externalLink,
                    onExternalDone = { externalLink.value = null },
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        val finishing = isFinishing
        // Primero se destruyen el fragmento de Readium y su WebView: así ninguna petición tardía de la página lee del
        // ZIP ya cerrado.
        super.onDestroy()
        // Al salir de verdad (no al girar la pantalla) se suelta el libro de la memoria. Solo si sigue siendo el mismo
        // objeto: si la persona ya reabrió el libro, onDestroy puede llegar tarde y no debe cerrar el del Lector nuevo.
        if (finishing) shown?.let { (id, pub) -> (application as LectorApp).openBooks.close(id, pub) }
    }

    private fun systemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        fun intent(context: Context, id: String): Intent = Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, id)
    }
}

/**
 * Las fuentes propias de los ajustes de lectura, con los nombres de familia que usa [ReadingRules]. Readium las sirve
 * desde assets/lector/fuentes/ (https://readium_assets/lector/fuentes/…, dentro de `servedAssets`) y solo las descarga
 * la página si se eligen. Una cara por archivo; Inter es variable (un archivo para todos los pesos).
 */
@OptIn(ExperimentalReadiumApi::class)
private fun EpubNavigatorFragment.Configuration.declareReadingFonts() {
    addFontFamilyDeclaration(ReadingRules.LITERATA) {
        addFontFace { addSource(FONTS + "literata_regular.ttf"); setFontStyle(FontStyle.NORMAL); setFontWeight(FontWeight.NORMAL) }
        addFontFace { addSource(FONTS + "literata_italic.ttf"); setFontStyle(FontStyle.ITALIC); setFontWeight(FontWeight.NORMAL) }
        addFontFace { addSource(FONTS + "literata_semibold.ttf"); setFontStyle(FontStyle.NORMAL); setFontWeight(FontWeight.SEMI_BOLD) }
    }
    addFontFamilyDeclaration(ReadingRules.INTER) {
        addFontFace { addSource(FONTS + "inter_variable.ttf"); setFontStyle(FontStyle.NORMAL); setFontWeight(100..900) }
    }
    addFontFamilyDeclaration(ReadingRules.ATKINSON) {
        addFontFace { addSource(FONTS + "atkinson_hyperlegible_regular.ttf"); setFontStyle(FontStyle.NORMAL); setFontWeight(FontWeight.NORMAL) }
        addFontFace { addSource(FONTS + "atkinson_hyperlegible_italic.ttf"); setFontStyle(FontStyle.ITALIC); setFontWeight(FontWeight.NORMAL) }
        addFontFace { addSource(FONTS + "atkinson_hyperlegible_bold.ttf"); setFontStyle(FontStyle.NORMAL); setFontWeight(FontWeight.BOLD) }
    }
}

/** Relativa a assets/: Readium la resuelve contra https://readium_assets/. */
private const val FONTS = "lector/fuentes/"
