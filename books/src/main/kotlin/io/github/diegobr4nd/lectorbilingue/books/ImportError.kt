package io.github.diegobr4nd.lectorbilingue.books

/** Por qué no se pudo añadir un libro. La interfaz lo convierte en un mensaje humano. */
enum class ImportError { NOT_EPUB, TOO_BIG, UNSAFE_ARCHIVE, DRM, DAMAGED, NO_SPACE }

/** Topes de la importación (docs/agentes/03-seguridad.md, amenaza A3). */
object ImportLimits {
    const val MAX_FILE_BYTES: Long = 100L * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES: Long = 500L * 1024 * 1024
    const val MAX_ENTRIES: Int = 10_000
    /** Tope por entrada del ZIP, descomprimida (ver EpubArchiveCheck). */
    const val MAX_ENTRY_BYTES: Long = 32L * 1024 * 1024
}
