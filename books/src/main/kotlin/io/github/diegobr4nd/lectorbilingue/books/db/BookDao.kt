package io.github.diegobr4nd.lectorbilingue.books.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    /** Último abierto primero; los nunca abiertos al final, del más nuevo al más viejo. */
    @Query("SELECT * FROM books ORDER BY lastOpenedAt IS NULL, lastOpenedAt DESC, addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE books SET locator = :locator, progress = :progress WHERE id = :id")
    suspend fun savePosition(id: String, locator: String, progress: Float)

    @Query("UPDATE books SET lastOpenedAt = :at WHERE id = :id")
    suspend fun markOpened(id: String, at: Long)
}
