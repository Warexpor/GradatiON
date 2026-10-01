package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A chat import writes its notes before the preference commit. A kill in between
 * is finished on the next launch.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ChatImportSideLogTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class ChatImportSideLogTest {

    @Test
    fun aLeftoverLogRestoresPinsFactsAndTheFork() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val log = ChatImportSideLog.file(app)
        ChatImportSideLog.clear(log)
        ChatImportSideLog.write(
            log,
            listOf(
                ImportedChatMeta(
                    id = 4L,
                    facts = "met yesterday",
                    pinned = true,
                    forkIndex = 1,
                    forkAnchor = 0,
                    forkMessages = "[{\"role\":\"assistant\"}]",
                    swipeJson = "{\"alts\":[\"other\"],\"index\":0}",
                )
            )
        )
        assertTrue(log.isFile)

        assertTrue(ChatImportSideLog.resume(app))

        assertEquals("met yesterday", prefs.getRpFacts(4L))
        assertTrue(prefs.isSessionPinned(4L))
        assertEquals(1, prefs.getChatForkIndex(4L))
        assertEquals("[{\"role\":\"assistant\"}]", prefs.getChatForkMessagesJson(4L))
        assertEquals("{\"alts\":[\"other\"],\"index\":0}", prefs.getRpSwipeJson(4L))
        assertFalse(log.exists())
        assertTrue(ChatImportSideLog.resume(app))
        prefs.mainPrefs.edit().clear().commit()
    }

    @Test
    fun aSideFileLeftByAKilledInstallIsStillRead() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val log = ChatImportSideLog.file(app)
        ChatImportSideLog.clear(log)
        val partial = File(log.parentFile, log.name + ".partial")
        ChatImportSideLog.write(partial, listOf(ImportedChatMeta(id = 9L, facts = "kept", pinned = true)))
        assertFalse(log.exists())

        assertEquals(1, ChatImportSideLog.read(log)?.size)
        assertTrue(ChatImportSideLog.resume(app))
        assertEquals("kept", prefs.getRpFacts(9L))
        assertTrue(prefs.isSessionPinned(9L))
        assertFalse(partial.exists())
        assertNull(prefs.getChatForkMessagesJson(9L))
        prefs.mainPrefs.edit().clear().commit()
    }

    @Test
    fun aNewerSideFileWinsAndAMissingChatIsDropped() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val log = ChatImportSideLog.file(app)
        ChatImportSideLog.clear(log)
        ChatImportSideLog.write(log, listOf(ImportedChatMeta(id = 2L, facts = "old", pinned = false)))
        val partial = File(log.parentFile, log.name + ".partial")
        partial.writeText(log.readText().replace("old", "new"))
        partial.setLastModified(log.lastModified() + 5_000)

        assertEquals("new", ChatImportSideLog.read(log)!!.single().facts)

        val gone = ImportedChatMeta(
            id = 8L,
            facts = "nope",
            pinned = true,
            title = "Gone",
            timestamp = 3L,
            messageCount = 1,
        )
        assertFalse(ChatImportSideLog.matches(gone, null, 0))
        val present = ChatSession(title = "Gone", modelUsed = "m", timestamp = 3L)
        assertTrue(ChatImportSideLog.matches(gone, present, 1))
        assertFalse(ChatImportSideLog.matches(gone, present.copy(title = "Other"), 1))
        assertFalse(ChatImportSideLog.matches(gone, present, 2))
        assertTrue(ChatImportSideLog.matches(gone.copy(title = null, timestamp = null, messageCount = null), present, 9))

        ChatImportSideLog.write(log, listOf(gone))
        assertTrue(ChatImportSideLog.resume(app) { false })
        assertEquals("", prefs.getRpFacts(8L))
        assertFalse(log.exists())
        prefs.mainPrefs.edit().clear().commit()
    }
}
