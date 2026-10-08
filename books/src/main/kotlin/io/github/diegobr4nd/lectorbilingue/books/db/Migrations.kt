package io.github.diegobr4nd.lectorbilingue.books.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 → v2 (3b): dirección por libro (null = automática) y caché de traducciones. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN direction TEXT DEFAULT NULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS translations (`key` TEXT NOT NULL, translation TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(`key`))")
    }
}
