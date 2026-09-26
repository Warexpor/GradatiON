package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.stardomains3.oxproxion.code.CodeHub
import io.github.stardomains3.oxproxion.code.CodeSessionDao
import io.github.stardomains3.oxproxion.code.CodeSessionEntity
import io.github.stardomains3.oxproxion.code.CodeSessionSummary
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.PermissionMode
import io.github.stardomains3.oxproxion.code.store.CodeStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room DAO + prefs→Room session migration for Code mode.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*CodeSessionDaoTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class CodeSessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: CodeSessionDao
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.codeSessionDao()
        AppDatabase.setInstanceForTesting(db)
        CodeHub.resetForTesting()
        ctx.getSharedPreferences(CodeStore.PREFS_NAME, 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        CodeHub.resetForTesting()
        AppDatabase.setInstanceForTesting(null)
        db.close()
    }

    @Test
    fun upsertRoundTripIncludesLastSeq() = runBlocking {
        val entity = CodeSessionEntity(
            id = "s1",
            hostId = "h1",
            harness = HarnessKind.OPENCODE.id,
            cwd = "~/code/GradatiON",
            title = "Fix resume",
            createdAt = 1000L,
            updatedAt = 2000L,
            mode = PermissionMode.AUTO_EDIT.id,
            branch = "gradation/app-pass",
            preview = "seeding lastSeq",
            lastSeq = 42L,
            model = "gpt-test"
        )
        dao.upsert(entity)
        val got = dao.getById("s1")!!
        assertEquals(entity, got)
        val summary = got.toSummary()
        assertEquals(PermissionMode.AUTO_EDIT, summary.permissionMode)
        assertEquals(HarnessKind.OPENCODE, summary.harness)
        assertEquals(42L, summary.lastSeq)
        assertEquals("~/code/GradatiON", summary.workspace)
    }

    @Test
    fun replaceAllAndDeleteForHost() = runBlocking {
        dao.upsertAll(
            listOf(
                sample("a", "h1", 3),
                sample("b", "h1", 2),
                sample("c", "h2", 1)
            )
        )
        assertEquals(listOf("a", "b", "c"), dao.getAll().map { it.id })
        dao.deleteForHost("h1")
        assertEquals(listOf("c"), dao.getAll().map { it.id })
        dao.replaceAll(listOf(sample("z", "h9", 9)))
        assertEquals(listOf("z"), dao.getAll().map { it.id })
        dao.replaceAll(emptyList())
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun migration3to4CreatesCodeSessionTable() {
        // Apply the migration SQL against a bare v3-shaped DB (no Room entities required).
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(null) // in-memory
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // Minimal v3 stub — only need a migratable database version.
                        db.execSQL("CREATE TABLE IF NOT EXISTS chat_sessions (id INTEGER PRIMARY KEY NOT NULL)")
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase
        assertEquals(3, db.version)
        DatabaseMigrations.MIGRATION_3_4.migrate(db)
        db.version = 4
        // Table usable for inserts.
        db.execSQL(
            """
            INSERT INTO code_session
            (id, hostId, harness, cwd, title, createdAt, updatedAt, mode, branch, preview, lastSeq, model)
            VALUES ('s','h','opencode','/w','t',1,2,'ask',NULL,'',99,NULL)
            """.trimIndent()
        )
        db.query("SELECT lastSeq FROM code_session WHERE id = 's'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(99L, c.getLong(0))
        }
        db.close()
    }

    @Test
    fun hubMigratesPrefsSessionsOnceIntoRoom() {
        val json = Json { encodeDefaults = true }
        val legacy = listOf(
            CodeSessionSummary(
                id = "legacy-1",
                hostId = "demo",
                harness = HarnessKind.CLAUDE_CODE,
                workspace = "~/code",
                title = "From prefs",
                createdAt = 10L,
                updatedAt = 20L,
                permissionMode = PermissionMode.PLAN,
                preview = "hi",
                lastSeq = 7L
            )
        )
        ctx.getSharedPreferences(CodeStore.PREFS_NAME, 0).edit()
            .putString(
                CodeStore.KEY_SESSIONS,
                json.encodeToString(ListSerializer(CodeSessionSummary.serializer()), legacy)
            )
            .commit()

        val hub = CodeHub.get(ctx)
        val loaded = hub.sessions.value["legacy-1"]
        assertEquals("From prefs", loaded?.summary?.title)
        assertEquals(7L, loaded?.summary?.lastSeq)
        assertEquals(PermissionMode.PLAN, loaded?.summary?.permissionMode)
        assertTrue(hub.store.sessionsMigratedToRoom)
        assertNull(
            ctx.getSharedPreferences(CodeStore.PREFS_NAME, 0).getString(CodeStore.KEY_SESSIONS, null)
        )

        // Second construction must not re-import (prefs already cleared / flagged).
        CodeHub.resetForTesting()
        runBlocking {
            dao.replaceAll(emptyList()) // simulate empty until hub loads — migration flag still set
            dao.upsert(CodeSessionEntity.from(legacy[0].copy(title = "Already in Room")))
        }
        val hub2 = CodeHub.get(ctx)
        assertEquals("Already in Room", hub2.sessions.value["legacy-1"]?.summary?.title)
    }

    @Test
    fun hubMergesPrefsWhenRoomAlreadyHasRows() {
        // Residual review#6 Q2: non-empty Room must not discard prefs legacy sessions.
        runBlocking {
            dao.upsert(
                CodeSessionEntity.from(
                    CodeSessionSummary(
                        id = "room-only",
                        hostId = "demo",
                        harness = HarnessKind.CLAUDE_CODE,
                        workspace = "~/code",
                        title = "Already in Room",
                        createdAt = 1L,
                        updatedAt = 2L,
                        lastSeq = 3L
                    )
                )
            )
            dao.upsert(
                CodeSessionEntity.from(
                    CodeSessionSummary(
                        id = "shared",
                        hostId = "demo",
                        harness = HarnessKind.CLAUDE_CODE,
                        workspace = "~/code",
                        title = "RoomTitle",
                        createdAt = 1L,
                        updatedAt = 2L,
                        lastSeq = 4L
                    )
                )
            )
        }
        val json = Json { encodeDefaults = true }
        val legacy = listOf(
            CodeSessionSummary(
                id = "prefs-only",
                hostId = "demo",
                harness = HarnessKind.OPENCODE,
                workspace = "~/other",
                title = "From prefs",
                createdAt = 5L,
                updatedAt = 6L,
                permissionMode = PermissionMode.PLAN,
                preview = "legacy",
                lastSeq = 9L
            ),
            CodeSessionSummary(
                id = "shared",
                hostId = "demo",
                harness = HarnessKind.CLAUDE_CODE,
                workspace = "~/code",
                title = "PrefsTitle",
                createdAt = 1L,
                updatedAt = 2L,
                lastSeq = 12L // newer than Room's 4
            )
        )
        ctx.getSharedPreferences(CodeStore.PREFS_NAME, 0).edit()
            .putString(
                CodeStore.KEY_SESSIONS,
                json.encodeToString(ListSerializer(CodeSessionSummary.serializer()), legacy)
            )
            .putBoolean("sessions_migrated_to_room", false)
            .commit()

        val hub = CodeHub.get(ctx)
        assertEquals("Already in Room", hub.sessions.value["room-only"]?.summary?.title)
        assertEquals("From prefs", hub.sessions.value["prefs-only"]?.summary?.title)
        assertEquals(9L, hub.sessions.value["prefs-only"]?.summary?.lastSeq)
        // Prefer newer lastSeq from prefs; keep Room title
        assertEquals("RoomTitle", hub.sessions.value["shared"]?.summary?.title)
        assertEquals(12L, hub.sessions.value["shared"]?.summary?.lastSeq)
        assertTrue(hub.store.sessionsMigratedToRoom)
        assertNull(
            ctx.getSharedPreferences(CodeStore.PREFS_NAME, 0).getString(CodeStore.KEY_SESSIONS, null)
        )
    }

    @Test
    fun hubPersistSessionsWritesRoom() = runBlocking {
        val hub = CodeHub.get(ctx)
        // Seed via DAO path used by persist: start with empty, write entity, reload hub.
        val summary = CodeSessionSummary(
            id = "persist-1",
            hostId = "h",
            harness = HarnessKind.CODEX,
            workspace = "/tmp",
            title = "Persisted",
            createdAt = 1L,
            updatedAt = 2L,
            lastSeq = 11L
        )
        dao.upsert(CodeSessionEntity.from(summary))
        CodeHub.resetForTesting()
        val reloaded = CodeHub.get(ctx)
        assertEquals(11L, reloaded.sessions.value["persist-1"]?.summary?.lastSeq)
        assertEquals("Persisted", reloaded.sessions.value["persist-1"]?.summary?.title)

        reloaded.forget("persist-1")
        runBlocking {
            withTimeout(3_000) {
                while (dao.getById("persist-1") != null) delay(20)
            }
        }
        assertNull(dao.getById("persist-1"))
    }

    private fun sample(id: String, hostId: String, updatedAt: Long) = CodeSessionEntity(
        id = id,
        hostId = hostId,
        harness = HarnessKind.CLAUDE_CODE.id,
        cwd = "/",
        title = id,
        createdAt = 0L,
        updatedAt = updatedAt,
        mode = PermissionMode.ASK.id,
        preview = "",
        lastSeq = null
    )
}
