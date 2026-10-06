package io.github.diegobr4nd.lectorbilingue.books.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Base de datos de la app. La 3b añade la tabla de traducciones con una migración a la versión 2. */
@Database(entities = [BookEntity::class], version = 1, exportSchema = true)
abstract class LectorDatabase : RoomDatabase() {
    abstract fun books(): BookDao

    companion object {
        const val NAME = "lector.db"
        fun open(context: Context): LectorDatabase =
            Room.databaseBuilder(context.applicationContext, LectorDatabase::class.java, NAME).build()
    }
}
