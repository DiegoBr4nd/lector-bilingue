package io.github.diegobr4nd.lectorbilingue.ui.library

import io.github.diegobr4nd.lectorbilingue.books.ImportError
import io.github.diegobr4nd.lectorbilingue.core.ui.components.EngineKind
import io.github.diegobr4nd.lectorbilingue.data.PairStatus
import io.github.diegobr4nd.lectorbilingue.data.RowStatus
import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import io.github.diegobr4nd.lectorbilingue.models.DownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryRulesTest {
    private fun row(installed: Boolean, dl: DownloadState? = null, engine: EngineId = EngineId.OPUS) =
        RowStatus("m-${engine.name}", engine, 100, installed, dl)
    private fun home(vararg cards: PairCard) = HomeState(cards.toList(), showNoLanguages = cards.isEmpty())

    @Test fun `todo listo no muestra aviso`() {
        val pairs = listOf(PairStatus("en-es", listOf(row(true))))
        assertNull(LibraryRules.notice(pairs, home(PairCard("en-es", EngineKind.QUALITY))))
    }

    @Test fun `descarga en curso con porcentaje`() {
        val dl = DownloadState(DownloadState.Status.RUNNING, 45, 100, null)
        val pairs = listOf(PairStatus("en-es", listOf(row(false, dl))))
        val card = PairCard("en-es", null, listOf(PairDownload("m-OPUS", EngineKind.QUALITY, dl)))
        assertEquals(LanguageNotice.Downloading("en-es", 45), LibraryRules.notice(pairs, home(card)))
    }

    @Test fun `descarga sin total no inventa porcentaje`() {
        val dl = DownloadState(DownloadState.Status.QUEUED, 0, 0, null)
        val card = PairCard("en-es", null, listOf(PairDownload("m-OPUS", EngineKind.QUALITY, dl)))
        assertEquals(LanguageNotice.Downloading("en-es", null), LibraryRules.notice(emptyList(), home(card)))
    }

    @Test fun `ultima descarga fallida sin modelo instalado`() {
        val dl = DownloadState(DownloadState.Status.FAILED, 10, 100, "red")
        val pairs = listOf(PairStatus("en-es", listOf(row(false, dl))))
        assertEquals(LanguageNotice.Failed("en-es"), LibraryRules.notice(pairs, home()))
    }

    @Test fun `motor elegido ausente`() {
        val pairs = listOf(PairStatus("en-es", listOf(row(true))))
        assertEquals(
            LanguageNotice.Missing("en-es", EngineKind.FAST),
            LibraryRules.notice(pairs, home(PairCard("en-es", null, missing = EngineKind.FAST))),
        )
    }

    @Test fun `sin idiomas`() = assertEquals(LanguageNotice.NoLanguages, LibraryRules.notice(emptyList(), home()))

    @Test fun `descarga gana a fallo`() {
        val running = DownloadState(DownloadState.Status.RUNNING, 1, 2, null)
        val failed = DownloadState(DownloadState.Status.FAILED, 0, 2, "x")
        val pairs = listOf(PairStatus("es-en", listOf(row(false, failed))), PairStatus("en-es", listOf(row(false, running))))
        val card = PairCard("en-es", null, listOf(PairDownload("m-OPUS", EngineKind.QUALITY, running)))
        assertEquals(LanguageNotice.Downloading("en-es", 50), LibraryRules.notice(pairs, home(card)))
    }

    @Test fun `mensajes por motivo y UNSAFE se disfraza de NOT_EPUB`() {
        assertEquals(LibraryMessage.NOT_EPUB, LibraryRules.message(ImportError.UNSAFE_ARCHIVE))
        for (r in ImportError.entries.filter { it != ImportError.UNSAFE_ARCHIVE }) assertEquals(r.name, LibraryRules.message(r).name)
    }

    @Test fun `porcentaje redondea hacia abajo y se recorta`() {
        assertEquals(0, LibraryRules.percent(0f)); assertEquals(42, LibraryRules.percent(0.429f))
        assertEquals(100, LibraryRules.percent(1f)); assertEquals(100, LibraryRules.percent(3f)); assertEquals(0, LibraryRules.percent(-1f))
        assertEquals(0, LibraryRules.percent(Float.NaN))
    }

    @Test fun `inicial para libros sin portada`() {
        assertEquals("E", LibraryRules.initial("el principito")); assertEquals("Á", LibraryRules.initial("  ábaco"))
        assertEquals("?", LibraryRules.initial("")); assertEquals("1", LibraryRules.initial("1984"))
    }
}
