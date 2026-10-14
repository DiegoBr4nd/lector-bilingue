package io.github.diegobr4nd.lectorbilingue.ui.reader

import io.github.diegobr4nd.lectorbilingue.engine.api.EngineId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Textos del aviso único del capítulo (3c-2, boceto 5a §4): los mismos de las tarjetas de la 3b. */
class NoticeTextsTest {
    private val texts = CardTexts(
        "No se pudo traducir este párrafo", "No se pudo preparar el traductor", "Reintentar", "Descargar",
        "Este párrafo es demasiado largo para traducirlo", "Traduciendo…", "Original",
        missing = { e -> "Falta el idioma inglés → español (${if (e == EngineId.OPUS) 45 else 120} MB)" },
        missingSpoken = { e -> "Falta el idioma inglés a español, ${if (e == EngineId.OPUS) 45 else 120} megabytes. Descargar" },
    )

    @Test fun `falta el modelo no es un fallo y lleva Descargar con el tamano de su motor`() {
        assertEquals(
            NoticeTexts(
                "Falta el idioma inglés → español (45 MB)", "Falta el idioma inglés a español, 45 megabytes. Descargar",
                "Descargar", error = false,
            ),
            noticeTexts(ChapterNotice.MissingModel(EngineId.OPUS), texts),
        )
        assertEquals("Falta el idioma inglés → español (120 MB)", noticeTexts(ChapterNotice.MissingModel(EngineId.FIREFOX), texts).message)
    }

    @Test fun `el motor que no carga es un fallo con Reintentar`() {
        assertEquals(
            NoticeTexts("No se pudo preparar el traductor", "No se pudo preparar el traductor", "Reintentar", error = true),
            noticeTexts(ChapterNotice.EngineFailed, texts),
        )
    }
}
