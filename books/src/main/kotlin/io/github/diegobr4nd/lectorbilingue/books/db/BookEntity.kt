package io.github.diegobr4nd.lectorbilingue.books.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Una fila de la Biblioteca. [locator] es la posición exacta de Readium en JSON; [progress] va de 0 a 1.
 * [direction] es la dirección de traducción elegida para el libro (null = automática).
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val progress: Float,
    val locator: String?,
    @ColumnInfo(defaultValue = "NULL") val direction: String? = null,
)
