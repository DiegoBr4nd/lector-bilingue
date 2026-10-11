package io.github.diegobr4nd.lectorbilingue.books.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translations WHERE `key` = :key")
    suspend fun get(key: String): TranslationEntity?

    /** Varias a la vez (pretraducción). SQLite admite 999 parámetros: quien llama manda tandas pequeñas. */
    @Query("SELECT * FROM translations WHERE `key` IN (:keys)")
    suspend fun getAll(keys: List<String>): List<TranslationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: TranslationEntity)

    @Query("SELECT COUNT(*) FROM translations")
    suspend fun count(): Int

    /** Tamaño aproximado: caracteres de clave y traducción × 2 (UTF-16). Solo para mostrar "aprox.". */
    @Query("SELECT COALESCE(SUM(LENGTH(`key`) + LENGTH(translation)), 0) * 2 FROM translations")
    suspend fun approxBytes(): Long

    @Query("DELETE FROM translations")
    suspend fun deleteAll()

    /**
     * Pasa el WAL (el diario de escrituras recientes, "lector.db-wal") al archivo principal y lo deja en cero bytes.
     * Tras [deleteAll] es lo que saca del WAL las copias de las páginas con el texto viejo. Devuelve 0 si se pudo
     * (1 = otra conexión estaba leyendo y quedó a medias). Room lee la fila, así que el PRAGMA se ejecuta de verdad.
     */
    suspend fun checkpointWal(): Int = pragma(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))

    /** Solo para [checkpointWal]: un PRAGMA que devuelve una fila (se lee su primera columna). */
    @RawQuery
    suspend fun pragma(query: SupportSQLiteQuery): Int
}
