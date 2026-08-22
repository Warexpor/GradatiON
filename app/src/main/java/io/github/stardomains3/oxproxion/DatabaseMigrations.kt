package io.github.stardomains3.oxproxion

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE chat_sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'ask'"
            )
            db.execSQL(
                "ALTER TABLE chat_sessions ADD COLUMN characterId INTEGER"
            )
            db.execSQL(
                "ALTER TABLE chat_sessions ADD COLUMN isLlm INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS rp_characters (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    personality TEXT NOT NULL,
                    style TEXT NOT NULL,
                    greeting TEXT NOT NULL,
                    scenario TEXT NOT NULL,
                    examplesJson TEXT NOT NULL,
                    photoUri TEXT,
                    prompt TEXT NOT NULL,
                    instruction TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS rp_lorebooks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    content TEXT NOT NULL,
                    isActive INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rp_characters ADD COLUMN exportKey TEXT NOT NULL DEFAULT ''")
            // Backfill unique keys for existing rows (SQLite has no UUID(); use rowid-based keys).
            db.execSQL(
                "UPDATE rp_characters SET exportKey = 'legacy-' || id || '-' || createdAt WHERE exportKey = '' OR exportKey IS NULL"
            )
        }
    }
}
