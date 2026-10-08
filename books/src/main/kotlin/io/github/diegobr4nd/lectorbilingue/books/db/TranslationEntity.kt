package io.github.diegobr4nd.lectorbilingue.books.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Traducción guardada de un párrafo entero. [key] = huella SHA-256 (ver TranslationRules.cacheKey en :app). */
@Entity(tableName = "translations")
data class TranslationEntity(@PrimaryKey val key: String, val translation: String, val createdAt: Long)
