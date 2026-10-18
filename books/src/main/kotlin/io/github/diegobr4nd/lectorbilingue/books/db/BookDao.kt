package io.github.diegobr4nd.lectorbilingue.books.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
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

    @Query("UPDATE books SET direction = :direction WHERE id = :id")
    suspend fun setDirection(id: String, direction: String?)

    @Query("UPDATE books SET lastOpenedAt = :at WHERE id = :id")
    suspend fun markOpened(id: String, at: Long)

    /**
     * Tras [delete]: pasa el WAL ("lector.db-wal") al archivo principal y lo deja en cero bytes, para que título, autor
     * y posición del libro borrado no sigan en sus copias de páginas (igual que [TranslationDao.checkpointWal]).
     * Devuelve 0 si se pudo (1 = otra conexión estaba leyendo y quedó a medias).
     */
    suspend fun checkpointWal(): Int = pragma(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))

    /** Solo para [checkpointWal]: un PRAGMA que devuelve una fila (se lee su primera columna). */
    @RawQuery
    suspend fun pragma(query: SupportSQLiteQuery): Int
}
