package io.github.stardomains3.oxproxion

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A fresh chat database reuses row ids. Notes and portraits keyed by the old ids must not
 * attach to the next chat or character.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*DbPrefQuarantineTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class DbPrefQuarantineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun rowScopedKeysAreTheOnesAFreshDatabaseWouldReuse() {
        assertTrue(DbPrefQuarantine.isRowScoped("rp_facts_4"))
        assertTrue(DbPrefQuarantine.isRowScoped("chat_fork_idx_4"))
        assertTrue(DbPrefQuarantine.isRowScoped("rp_memory_2"))
        assertTrue(DbPrefQuarantine.isRowScoped("rp_voice_pitch_2"))
        assertTrue(DbPrefQuarantine.isRowScoped("rp_lorebook_pending_2"))
        assertTrue(DbPrefQuarantine.isRowScoped("bg_photo_version_char_2"))
        assertTrue(DbPrefQuarantine.isRowScoped("pinned_session_ids"))
        assertTrue(DbPrefQuarantine.isRowScoped("ask_composer_drafts.unreadable"))
        assertTrue(DbPrefQuarantine.isRowScoped("rp_active_character_id"))
        assertFalse(DbPrefQuarantine.isRowScoped("rp_memory_llm"))
        assertFalse(DbPrefQuarantine.isRowScoped("rp_voice_pitch_llm"))
        assertFalse(DbPrefQuarantine.isRowScoped("rp_persona"))
        assertFalse(DbPrefQuarantine.isRowScoped("custom_models"))
        assertFalse(DbPrefQuarantine.isRowScoped("bg_photo_version"))
        assertFalse(DbPrefQuarantine.isRowScoped("aside.3.rp_facts_4"))
        assertFalse(DbPrefQuarantine.isRowScoped("chat_db_quarantine_due"))
    }

    @Test
    fun aFreshDatabaseDoesNotInheritThePreviousChatsNotes() {
        val prefs = prefs()
        prefs.edit()
            .putString("rp_facts_1", "met yesterday")
            .putString("rp_memory_1", "afraid of the dark")
            .putString("rp_memory_llm", "llm note")
            .putString("rp_persona", "Ada")
            .putString("custom_models", "[]")
            .putString("ask_composer_drafts", "{\"1\":\"unsent\"}")
            .putString("ask_composer_drafts.unreadable", "{torn")
            .putString("rp_pending_instruct", "shorter")
            .putLong("rp_active_character_id", 1L)
            .putLong("rp_draft_session_ask", 1L)
            .putFloat("rp_voice_pitch_1", 0.5f)
            .putFloat("rp_voice_pitch_llm", 1.2f)
            .putStringSet("pinned_session_ids", setOf("1"))
            .putString("aside.1.rp_facts_8", "older archive")
            .commit()

        assertTrue(DbPrefQuarantine.quarantine(prefs, tmp.newFolder("files"), tmp.newFolder("vault"), 4L))

        assertEquals("met yesterday", prefs.getString("aside.4.rp_facts_1", null))
        assertEquals("afraid of the dark", prefs.getString("aside.4.rp_memory_1", null))
        assertEquals("{\"1\":\"unsent\"}", prefs.getString("aside.4.ask_composer_drafts", null))
        assertEquals("{torn", prefs.getString("aside.4.ask_composer_drafts.unreadable", null))
        assertEquals("shorter", prefs.getString("aside.4.rp_pending_instruct", null))
        assertEquals(1L, prefs.getLong("aside.4.rp_active_character_id", -1L))
        assertEquals(1L, prefs.getLong("aside.4.rp_draft_session_ask", -1L))
        assertEquals(0.5f, prefs.getFloat("aside.4.rp_voice_pitch_1", 1f))
        assertEquals(setOf("1"), prefs.getStringSet("aside.4.pinned_session_ids", emptySet()))
        assertFalse(prefs.contains("rp_facts_1"))
        assertFalse(prefs.contains("rp_memory_1"))
        assertFalse(prefs.contains("ask_composer_drafts"))
        assertFalse(prefs.contains("rp_active_character_id"))
        assertFalse(prefs.contains("pinned_session_ids"))
        assertEquals("llm note", prefs.getString("rp_memory_llm", null))
        assertEquals(1.2f, prefs.getFloat("rp_voice_pitch_llm", 1f))
        assertEquals("Ada", prefs.getString("rp_persona", null))
        assertEquals("[]", prefs.getString("custom_models", null))
        assertEquals("older archive", prefs.getString("aside.1.rp_facts_8", null))
        assertFalse(prefs.contains("aside.4.aside.1.rp_facts_8"))
    }

    @Test
    fun aSecondRecoveryKeepsBothArchives() {
        val prefs = prefs()
        prefs.edit().putString("rp_facts_1", "first").commit()
        val files = tmp.newFolder("files")
        val vault = tmp.newFolder("vault")
        assertTrue(DbPrefQuarantine.quarantine(prefs, files, vault, 3L))
        prefs.edit().putString("rp_facts_1", "second").commit()
        assertTrue(DbPrefQuarantine.quarantine(prefs, files, vault, 3L))

        assertEquals("first", prefs.getString("aside.3.rp_facts_1", null))
        assertEquals("second", prefs.getString("aside.4.rp_facts_1", null))
        assertFalse(prefs.contains("rp_facts_1"))
    }

    @Test
    fun characterPicturesMoveAndTheAppBackgroundStays() {
        val files = tmp.newFolder("files")
        val vault = tmp.newFolder("vault")
        File(files, "rp_avatars").mkdirs()
        File(files, "backgrounds").mkdirs()
        File(files, "rp_avatars/char_3.jpg").writeText("face")
        File(files, "backgrounds/char_3.jpg").writeText("wall")
        File(files, "backgrounds/photo.jpg").writeText("app")
        File(files, "rp_persona_avatars").mkdirs()
        File(files, "rp_persona_avatars/persona_1.jpg").writeText("me")

        assertTrue(DbPrefQuarantine.quarantine(prefs(), files, vault, 6L))

        assertEquals("face", File(vault, "aside-6/rp_avatars/char_3.jpg").readText())
        assertEquals("wall", File(vault, "aside-6/backgrounds/char_3.jpg").readText())
        assertFalse(File(files, "rp_avatars/char_3.jpg").exists())
        assertFalse(File(files, "backgrounds/char_3.jpg").exists())
        assertEquals("app", File(files, "backgrounds/photo.jpg").readText())
        assertEquals("me", File(files, "rp_persona_avatars/persona_1.jpg").readText())
    }

    @Test
    fun aKilledReplaceDoesNotComeBackOnTheReusedId() {
        val files = tmp.newFolder("files")
        val vault = tmp.newFolder("vault")
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0xFF.toByte(), 0xD9.toByte())
        File(files, "rp_avatars").mkdirs()
        File(files, "backgrounds").mkdirs()
        // The live names were already moved, or never finished. Only the side files remain.
        File(files, "rp_avatars/char_3.jpg.bak").writeBytes(jpeg)
        File(files, "rp_avatars/char_3.jpg.partial.incoming").writeBytes(jpeg)
        File(files, "backgrounds/char_3.jpg.partial").writeBytes(jpeg)
        File(files, "backgrounds/photo.jpg.bak").writeBytes(jpeg)

        assertTrue(DbPrefQuarantine.quarantine(prefs(), files, vault, 6L))

        val avatar = File(files, "rp_avatars/char_3.jpg")
        val wall = File(files, "backgrounds/char_3.jpg")
        assertFalse(File(files, "rp_avatars/char_3.jpg.bak").exists())
        assertFalse(File(files, "rp_avatars/char_3.jpg.partial.incoming").exists())
        assertFalse(File(files, "backgrounds/char_3.jpg.partial").exists())
        assertTrue(jpeg.contentEquals(File(vault, "aside-6/rp_avatars/char_3.jpg.bak").readBytes()))
        assertTrue(jpeg.contentEquals(File(vault, "aside-6/rp_avatars/char_3.jpg.partial.incoming").readBytes()))
        assertTrue(jpeg.contentEquals(File(vault, "aside-6/backgrounds/char_3.jpg.partial").readBytes()))
        // The app background's side file is not a character picture.
        assertTrue(jpeg.contentEquals(File(files, "backgrounds/photo.jpg.bak").readBytes()))
        // recover would have installed the side file onto the live name.
        assertFalse(ScenePhoto.recover(avatar))
        assertFalse(avatar.exists())
        assertFalse(ScenePhoto.recover(wall))
        assertFalse(wall.exists())
    }

    @Test
    fun aPictureAlreadyArchivedIsNotOverwritten() {
        val files = tmp.newFolder("files")
        val vault = tmp.newFolder("vault")
        val archived = File(vault, "aside-2/rp_avatars")
        archived.mkdirs()
        File(archived, "char_3.jpg").writeText("old")
        File(files, "rp_avatars").mkdirs()
        File(files, "rp_avatars/char_3.jpg").writeText("new")

        assertTrue(DbPrefQuarantine.quarantine(prefs(), files, vault, 2L))

        assertEquals("old", File(archived, "char_3.jpg").readText())
        assertEquals("new", File(archived, "char_3.kept-1.jpg").readText())
        assertFalse(File(files, "rp_avatars/char_3.jpg").exists())
    }

    @Test
    fun deletingAChatDropsItsPinAndFacts() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val helper = SharedPreferencesHelper(app)
        helper.mainPrefs.edit().clear().commit()
        try {
            helper.saveRpFacts(4L, "note")
            helper.setSessionPinned(4L, true)
            helper.setSessionPinned(9L, true)
            helper.saveChatFork(4L, 1, 1, "[]")

            helper.clearSessionPrefs(4L)

            assertEquals("", helper.getRpFacts(4L))
            assertFalse(helper.isSessionPinned(4L))
            assertTrue(helper.isSessionPinned(9L))
            assertNull(helper.getChatForkMessagesJson(4L))
        } finally {
            helper.mainPrefs.edit().clear().commit()
        }
    }

    @Test
    fun oldBlobsMoveOutOfMainPrefs() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val main = app.getSharedPreferences(SharedPreferencesHelper.MAIN_PREFS, Context.MODE_PRIVATE)
        main.edit().clear()
            .putString("rp_swipe_7", """{"alts":["a"]}""")
            .putString("chat_fork_7", "[]")
            .putInt("chat_fork_idx_7", 2)
            .putString("rp_facts_7", "f")
            .commit()
        SharedPreferencesHelper.resetBlobMoveForTest()
        val helper = SharedPreferencesHelper(app)
        assertEquals("""{"alts":["a"]}""", helper.getRpSwipeJson(7L))
        assertEquals("[]", helper.getChatForkMessagesJson(7L))
        assertEquals(2, helper.getChatForkIndex(7L))
        assertEquals("f", helper.getRpFacts(7L))
        assertFalse("swipe left in the main file", main.contains("rp_swipe_7"))
        assertFalse("fork left in the main file", main.contains("chat_fork_7"))
        assertTrue(main.contains("chat_fork_idx_7"))
        // A second helper does not move anything again.
        helper.saveRpSwipeJson(7L, "{}")
        SharedPreferencesHelper(app)
        assertEquals("{}", helper.getRpSwipeJson(7L))
    }

    private fun prefs() =
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("db_pref_quarantine_test", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }
}
