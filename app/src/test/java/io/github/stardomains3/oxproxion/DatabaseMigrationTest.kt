package io.github.stardomains3.oxproxion

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every Room migration, run against a database created from the exported schema of its old version
 * and validated against the schema of the new one (app/schemas/.../N.json).
 *
 * Versions 1 to 3 were rebuilt by hand from the migration SQL (no schema was exported before);
 * 4.json is written by the Room compiler at build. Run:
 * ./gradlew :app:testDebugUnitTest --tests '*DatabaseMigrationTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class DatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val name = "migration-test"
    private val all = arrayOf(
        DatabaseMigrations.MIGRATION_1_2,
        DatabaseMigrations.MIGRATION_2_3,
        DatabaseMigrations.MIGRATION_3_4,
    )

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
        query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { it.count == 1 }

    @Test
    fun v1ToV2AddsModeAndCharacterColumnsAndRpTables() {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO chat_sessions (id, title, modelUsed, timestamp) VALUES (1, 'old chat', 'm', 100)")
            execSQL("INSERT INTO chat_messages (id, sessionId, role, content) VALUES (1, 1, 'user', '\"hi\"')")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 2, true, DatabaseMigrations.MIGRATION_1_2)

        db.query("SELECT mode, characterId, isLlm FROM chat_sessions WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("ask", it.getString(0))
            assertTrue(it.isNull(1))
            assertEquals(0, it.getInt(2))
        }
        assertEquals(1, db.count("chat_messages"))
        assertTrue(db.hasTable("rp_characters"))
        assertTrue(db.hasTable("rp_lorebooks"))
    }

    @Test
    fun v2ToV3GivesEveryExistingCharacterAUniqueExportKey() {
        helper.createDatabase(name, 2).apply {
            for (id in 1..2) {
                execSQL(
                    "INSERT INTO rp_characters (id, name, personality, style, greeting, scenario, examplesJson, " +
                        "photoUri, prompt, instruction, createdAt, updatedAt) " +
                        "VALUES ($id, 'c$id', '', '', '', '', '[]', NULL, '', '', 500, 600)"
                )
            }
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 3, true, DatabaseMigrations.MIGRATION_2_3)

        val keys = db.query("SELECT exportKey FROM rp_characters ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertEquals(listOf("legacy-1-500", "legacy-2-500"), keys)
    }

    @Test
    fun v3ToV4AddsTheCodeSessionTable() {
        helper.createDatabase(name, 3).close()
        val db = helper.runMigrationsAndValidate(name, 4, true, DatabaseMigrations.MIGRATION_3_4)

        assertTrue(db.hasTable("code_session"))
        db.execSQL(
            "INSERT INTO code_session (id, hostId, harness, cwd, title, createdAt, updatedAt, mode, preview) " +
                "VALUES ('s1', 'h1', 'claude', '/w', 't', 1, 2, 'default', '')"
        )
        assertEquals(1, db.count("code_session"))
    }

    @Test
    fun v1ThroughV4KeepsChatsMessagesAndCharacters() {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO chat_sessions (id, title, modelUsed, timestamp) VALUES (7, 'kept', 'm', 100)")
            execSQL("INSERT INTO chat_messages (id, sessionId, role, content) VALUES (1, 7, 'user', '\"a\"')")
            execSQL("INSERT INTO chat_messages (id, sessionId, role, content) VALUES (2, 7, 'assistant', '\"b\"')")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 4, true, *all)

        db.query("SELECT title, mode FROM chat_sessions WHERE id = 7").use {
            assertTrue(it.moveToFirst())
            assertEquals("kept", it.getString(0))
            assertEquals("ask", it.getString(1))
        }
        assertEquals(2, db.count("chat_messages"))
        assertTrue(db.hasTable("code_session"))
    }
}
