package io.github.diegobr4nd.lectorbilingue.books

import java.io.File

/** Un libro de la Biblioteca, listo para la interfaz. */
data class Book(val id: String, val title: String, val author: String?, val coverFile: File?, val progress: Float, val locator: String?, val direction: String? = null)

sealed interface ImportResult {
    data class Ok(val bookId: String) : ImportResult
    data class Error(val reason: ImportError) : ImportResult
}

sealed interface MetadataRead {
    data class Ok(val metadata: BookMetadata) : MetadataRead
    data class Failed(val reason: ImportError) : MetadataRead
}
