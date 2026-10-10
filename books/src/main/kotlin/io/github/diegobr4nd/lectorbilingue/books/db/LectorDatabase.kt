package io.github.diegobr4nd.lectorbilingue.books.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** Base de datos de la app. v2 (3b): caché de traducciones y dirección por libro (ver [MIGRATION_1_2]). */
@Database(entities = [BookEntity::class, TranslationEntity::class], version = 2, exportSchema = true)
abstract class LectorDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun translations(): TranslationDao

    companion object {
        const val NAME = "lector.db"

        /**
         * Al abrir: `secure_delete = ON` hace que SQLite ponga en ceros lo que borra (DELETE), para que
         * el texto de las traducciones no quede en p�ginas libres ni en el WAL. [name] solo cambia en pruebas.
         */
        fun open(context: Context, name: String = NAME): LectorDatabase =
            Room.databaseBuilder(context.applicationContext, LectorDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // PRAGMA devuelve una fila: se usa query (no execSQL) y se cierra el cursor.
                        db.query("PRAGMA secure_delete = ON").close()
                    }
                })
                .build()
    }
}
