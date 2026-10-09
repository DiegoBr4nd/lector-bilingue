package io.github.diegobr4nd.lectorbilingue

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.books.db.LectorDatabase
import io.github.diegobr4nd.lectorbilingue.books.db.MIGRATION_1_2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Comprueba la migración 1 a 2 con una base propia ("migracion-prueba.db"); nunca toca lector.db. */
@RunWith(AndroidJUnit4::class)
class MigrationOnDeviceTest {
    private val name = "migracion-prueba.db"

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LectorDatabase::class.java)

    @After fun cleanUp() { InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name) }

    @Test fun de1a2ConservaLosLibrosYAnadeTraducciones() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO books (id, title, author, coverPath, addedAt, lastOpenedAt, progress, locator) VALUES ('a', 'T', NULL, NULL, 1, NULL, 0.5, NULL)")
        }
        helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT title, progress, direction FROM books WHERE id = 'a'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("T", c.getString(0)); assertEquals(0.5f, c.getFloat(1), 0f); assertTrue(c.isNull(2))
            }
            db.execSQL("INSERT INTO translations (`key`, translation, createdAt) VALUES ('k', 'hola', 2)")
        }
    }
}
