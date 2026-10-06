package io.github.diegobr4nd.lectorbilingue.books

import java.io.File
import java.util.UUID

/** Dónde viven los libros: carpeta privada de la app, que ninguna otra app puede leer. */
class BookFiles(root: File) {
    val booksDir = File(root, "books")
    val tmpDir = File(booksDir, "tmp")

    fun epub(id: String) = File(booksDir, "${checkId(id)}.epub")
    fun cover(id: String) = File(booksDir, "${checkId(id)}.cover.png")

    fun newTmp(): File {
        tmpDir.mkdirs()
        return File(tmpDir, "${UUID.randomUUID()}.epub")
    }

    /** Borra lo que dejó una importación cortada (la app se cerró a mitad). */
    fun cleanTmp() {
        tmpDir.listFiles()?.forEach { it.delete() }
    }

    fun deleteBook(id: String) {
        epub(id).delete()
        cover(id).delete()
    }

    companion object {
        /** Solo UUID: un id raro nunca se convierte en una ruta fuera de books/. */
        fun checkId(id: String): String {
            require(isValidId(id)) { "id de libro inválido" }
            return id
        }

        fun isValidId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    }
}
