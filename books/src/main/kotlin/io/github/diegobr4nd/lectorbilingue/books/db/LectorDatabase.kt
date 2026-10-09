package io.github.diegobr4nd.lectorbilingue.books.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Base de datos de la app. v2 (3b): caché de traducciones y dirección por libro (ver [MIGRATION_1_2]). */
@Database(entities = [BookEntity::class, TranslationEntity::class], version = 2, exportSchema = true)
abstract class LectorDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun translations(): TranslationDao

    companion object {
        const val NAME = "lector.db"
        fun open(context: Context): LectorDatabase =
            Room.databaseBuilder(context.applicationContext, LectorDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
