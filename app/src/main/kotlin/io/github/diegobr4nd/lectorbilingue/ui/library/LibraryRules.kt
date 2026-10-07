package io.github.diegobr4nd.lectorbilingue.ui.library

import io.github.diegobr4nd.lectorbilingue.books.ImportError
import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.models.DownloadState

/** Aviso de idiomas arriba de la Biblioteca: solo cuando hay algo que hacer o que esperar. */
sealed interface LanguageNotice {
    data class Downloading(val pair: String, val percent: Int?) : LanguageNotice
    data class Failed(val pair: String) : LanguageNotice
    data class Missing(val pair: String, val kind: EngineKind) : LanguageNotice
    data object NoLanguages : LanguageNotice
}

/** Mensaje de error de importación. UNSAFE_ARCHIVE no tiene propio: no se dan pistas del ataque. */
enum class LibraryMessage { NOT_EPUB, TOO_BIG, DRM, DAMAGED, NO_SPACE }

/** Reglas puras (sin Android) de la Biblioteca: se prueban en la JVM. */
object LibraryRules {
    /** Prioridad: descargando > última descarga fallida > motor elegido ausente > sin idiomas. */
    fun notice(pairs: List<PairStatus>, home: HomeState): LanguageNotice? {
        home.pairCards.firstNotNullOfOrNull { card -> card.downloads.firstOrNull()?.let { card.pair to it.state } }?.let { (pair, s) ->
            return LanguageNotice.Downloading(pair, if (s.total > 0) ((s.bytes * 100) / s.total).toInt().coerceIn(0, 100) else null)
        }
        pairs.firstOrNull { p -> p.rows.none { it.installed } && p.rows.any { it.download?.status == DownloadState.Status.FAILED } }
            ?.let { return LanguageNotice.Failed(it.pair) }
        home.pairCards.firstOrNull { it.missing != null }?.let { return LanguageNotice.Missing(it.pair, it.missing!!) }
        return if (home.showNoLanguages) LanguageNotice.NoLanguages else null
    }

    fun message(reason: ImportError): LibraryMessage = when (reason) {
        ImportError.NOT_EPUB, ImportError.UNSAFE_ARCHIVE -> LibraryMessage.NOT_EPUB
        ImportError.TOO_BIG -> LibraryMessage.TOO_BIG
        ImportError.DRM -> LibraryMessage.DRM
        ImportError.DAMAGED -> LibraryMessage.DAMAGED
        ImportError.NO_SPACE -> LibraryMessage.NO_SPACE
    }

    fun percent(progress: Float): Int = if (progress.isNaN()) 0 else (progress.coerceIn(0f, 1f) * 100).toInt()

    /** Título para mostrar (Biblioteca y Lector). null si está vacío: la pantalla pone "Libro sin título". */
    fun title(stored: String?): String? = stored?.takeIf { it.isNotBlank() }

    fun initial(title: String): String = title.trim().firstOrNull()?.uppercase() ?: "?"
}
