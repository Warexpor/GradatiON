package io.github.stardomains3.oxproxion

import android.app.Application
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The pieces of "never crash-loop on an unreadable chat database" that run without the SQLCipher
 * native library: setting the files aside (never deleting them), and the one-shot notice flag.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ChatDatabaseRecoveryTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class ChatDatabaseRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun unreadableFilesAreMovedAsideWithTheirSidecarsAndNothingIsDeleted() {
        val db = tmp.newFile("chat_database").apply { writeText("main") }
        File(db.path + "-wal").writeText("wal")
        File(db.path + "-shm").writeText("shm")

        val moved = AppDatabase.setAside(db, 1234L)!!

        assertEquals("chat_database.unreadable-1234", moved.name)
        assertEquals("main", moved.readText())
        assertEquals("wal", File(moved.path + "-wal").readText())
        assertEquals("shm", File(moved.path + "-shm").readText())
        assertFalse(db.exists())
        assertFalse(File(db.path + "-wal").exists())
        assertFalse(File(db.path + "-shm").exists())
    }

    @Test
    fun nothingToMoveIsNotAnError() {
        val db = File(tmp.root, "chat_database")
        assertNull(AppDatabase.setAside(db, 1L))
    }

    @Test
    fun twoRecoveriesKeepBothOldDatabases() {
        val db = tmp.newFile("chat_database").apply { writeText("first") }
        AppDatabase.setAside(db, 1L)
        db.writeText("second")
        AppDatabase.setAside(db, 2L)

        assertEquals("first", File(tmp.root, "chat_database.unreadable-1").readText())
        assertEquals("second", File(tmp.root, "chat_database.unreadable-2").readText())
    }

    @Test
    fun aStampThatIsAlreadyTakenUsesTheNextFreeOne() {
        val db = tmp.newFile("chat_database").apply { writeText("first") }
        AppDatabase.setAside(db, 5L)
        db.writeText("second")
        val moved = AppDatabase.setAside(db, 5L)!!

        assertEquals("chat_database.unreadable-6", moved.name)
        assertEquals("second", moved.readText())
        assertEquals("first", File(tmp.root, "chat_database.unreadable-5").readText())
    }

    @Test
    fun aFailedMovePutsTheFilesBack() {
        val db = tmp.newFile("chat_database").apply { writeText("main") }
        File(db.path + "-wal").writeText("wal")
        AppDatabase.movesBeforeFailure = 1
        try {
            assertThrows(Exception::class.java) { AppDatabase.setAside(db, 9L) }
        } finally {
            AppDatabase.movesBeforeFailure = null
        }

        assertEquals("main", db.readText())
        assertEquals("wal", File(db.path + "-wal").readText())
        assertFalse(File(tmp.root, "chat_database.unreadable-9").exists())
        assertFalse(File(tmp.root, "chat_database.unreadable-9-wal").exists())
    }

    @Test
    fun theRecoveryPassphraseIsArchivedAndTheActiveCopyCanBeDropped() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("ApiKeysPrefsStore", 0)
        prefs.edit().clear().commit()
        prefs.edit()
            .putString("chat_db_passphrase_encrypted", "wrapped-key")
            .putString("chat_db_passphrase_iv", "wrapped-iv")
            .commit()

        val helper = SharedPreferencesHelper(app)
        assertTrue(helper.archiveChatDbPassphrase(42L))
        helper.discardActiveChatDbPassphrase()

        val prefix = SharedPreferencesHelper.chatDbPassphraseArchivePrefix(42L)
        assertEquals("wrapped-key", prefs.getString("${prefix}_encrypted", null))
        assertEquals("wrapped-iv", prefs.getString("${prefix}_iv", null))
        assertNull(prefs.getString("chat_db_passphrase_encrypted", null))
        assertNull(prefs.getString("chat_db_passphrase_iv", null))
        assertFalse(helper.archiveChatDbPassphrase(43L))
    }

    @Test
    fun aPassphraseMustBe32Bytes() {
        val key = ByteArray(32) { it.toByte() }
        val encoded = Base64.encodeToString(key, Base64.NO_WRAP)
        assertArrayEquals(key, SharedPreferencesHelper.decodeChatDbPassphrase(encoded))
        // DEFAULT encoding inserts newlines; that still has to decode to the same key.
        val wrapped = Base64.encodeToString(key, Base64.DEFAULT)
        assertArrayEquals(key, SharedPreferencesHelper.decodeChatDbPassphrase(wrapped))
        assertThrows(IllegalStateException::class.java) {
            SharedPreferencesHelper.decodeChatDbPassphrase(
                Base64.encodeToString(ByteArray(8), Base64.NO_WRAP)
            )
        }
    }

    @Test
    fun aPendingRecoveryIsClearedWhenTheNoticeIsRecorded() {
        val prefs = SharedPreferencesHelper(ApplicationProvider.getApplicationContext<Application>())
        assertNull(prefs.recoveryPendingStamp())
        prefs.markRecoveryPending(99L)
        assertEquals(99L, prefs.recoveryPendingStamp())
        prefs.markChatDbRecovered()
        assertNull(prefs.recoveryPendingStamp())
        assertTrue(prefs.consumeChatDbRecovered())
    }

    @Test
    fun theRecoveryNoticeIsShownOnce() {
        val prefs = SharedPreferencesHelper(ApplicationProvider.getApplicationContext<Application>())
        assertFalse(prefs.consumeChatDbRecovered())

        prefs.markChatDbRecovered()
        assertTrue(prefs.consumeChatDbRecovered())
        assertFalse(prefs.consumeChatDbRecovered())
    }

    @Test
    fun aTakenRecoveryFileNameUsesTheNextFreeOne() {
        File(tmp.root, "chat_database.recovered-5").writeText("taken")
        assertEquals("chat_database.recovered-6", AppDatabase.recoveredFileName(tmp.root, 5L))
        assertEquals("chat_database.recovered-7", AppDatabase.recoveredFileName(tmp.root, 7L))
    }

    @Test
    fun anUnsafeDatabaseFileNameIsIgnored() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().putString("chat_db_file", "../chat_database").commit()
        assertEquals(AppDatabase.DB_NAME, prefs.chatDbFileName())
        prefs.saveChatDbFileName("chat_database.recovered-5")
        assertEquals("chat_database.recovered-5", prefs.chatDbFileName())
    }

    @Test
    fun theRecoveryNoticeTextIsTheAgreedOne() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(
            "Your chat history couldn't be opened, so it was set aside and a fresh one started.",
            app.getString(R.string.notice_chat_db_recovered)
        )
    }
}
