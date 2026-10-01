package io.github.stardomains3.oxproxion

import android.app.Application
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
    fun theRecoveryNoticeIsShownOnce() {
        val prefs = SharedPreferencesHelper(ApplicationProvider.getApplicationContext<Application>())
        assertFalse(prefs.consumeChatDbRecovered())

        prefs.markChatDbRecovered()
        assertTrue(prefs.consumeChatDbRecovered())
        assertFalse(prefs.consumeChatDbRecovered())
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
