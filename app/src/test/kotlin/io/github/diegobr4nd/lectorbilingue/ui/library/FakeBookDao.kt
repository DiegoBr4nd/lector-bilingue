package io.github.diegobr4nd.lectorbilingue.ui.library

import io.github.diegobr4nd.lectorbilingue.books.db.BookDao
import io.github.diegobr4nd.lectorbilingue.books.db.BookEntity
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** BookDao en memoria con el mismo orden que la consulta real. */
class FakeBookDao : BookDao {
    val rows = MutableStateFlow<Map<String, BookEntity>>(emptyMap())
    var failInsert = false
    /** Borrados y PRAGMA en el orden en que llegan (para ver que el WAL se vacía después de borrar). */
    val maintenance = mutableListOf<String>()
    var failSetDirection = false

    override fun observeAll(): Flow<List<BookEntity>> = rows.map { m ->
        m.values.sortedWith(compareBy<BookEntity> { it.lastOpenedAt == null }.thenByDescending { it.lastOpenedAt ?: 0 }.thenByDescending { it.addedAt })
    }
    override suspend fun get(id: String) = rows.value[id]
    override suspend fun insert(book: BookEntity) {
        if (failInsert) throw IllegalStateException("falla de prueba")
        check(book.id !in rows.value); rows.value = rows.value + (book.id to book)
    }
    override suspend fun delete(id: String) { rows.value = rows.value - id; maintenance += "delete" }
    override suspend fun pragma(query: SupportSQLiteQuery): Int { maintenance += query.sql; return 0 }
    override suspend fun savePosition(id: String, locator: String, progress: Float) {
        rows.value[id]?.let { rows.value = rows.value + (id to it.copy(locator = locator, progress = progress)) }
    }
    override suspend fun setDirection(id: String, direction: String?) {
        if (failSetDirection) throw IllegalStateException("falla de prueba")
        rows.value[id]?.let { rows.value = rows.value + (id to it.copy(direction = direction)) }
    }
    override suspend fun markOpened(id: String, at: Long) {
        rows.value[id]?.let { rows.value = rows.value + (id to it.copy(lastOpenedAt = at)) }
    }
}
