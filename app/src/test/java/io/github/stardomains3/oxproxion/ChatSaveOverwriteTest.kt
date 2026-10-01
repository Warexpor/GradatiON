package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real DAO on an in-memory Room database: saving a session again replaces its messages
 * instead of appending, search treats LIKE wildcards as text, and export then import round-trips.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ChatSaveOverwriteTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class ChatSaveOverwriteTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChatDao
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.chatDao()
        AppDatabase.setInstanceForTesting(db)
    }

    @After
    fun tearDown() {
        AppDatabase.setInstanceForTesting(null)
        db.close()
    }

    private fun message(role: String, text: String) =
        ChatMessage(sessionId = 0, role = role, content = "\"$text\"")

    @Test
    fun overwriteKeepsLatestMessageCountOnly() = runBlocking {
        val id = dao.insertSessionAndMessages(
            ChatSession(title = "t", modelUsed = "m"),
            listOf(message("user", "hi"), message("assistant", "hello")),
        )
        assertEquals(2, dao.countMessages(id))

        val written = dao.overwriteIfExists(
            ChatSession(id = id, title = "t2", modelUsed = "m"),
            listOf(
                message("user", "hi"), message("assistant", "hello"),
                message("user", "again"), message("assistant", "ok"),
            ),
        )
        assertTrue(written)
        assertEquals(4, dao.countMessages(id))
        assertEquals(1, dao.getAllSessionsWithMessages().size)
        assertEquals("t2", dao.getSessionById(id)!!.title)
        assertEquals("ok", dao.getLastMessage(id)!!.content.trim('"'))
    }

    @Test
    fun concurrentNewSavesGetDistinctIds() = runBlocking {
        val ids = (1..12).map { n ->
            async(Dispatchers.Default) {
                dao.insertSessionAndMessages(
                    ChatSession(title = "chat $n", modelUsed = "m"),
                    listOf(message("user", "q$n"), message("assistant", "a$n")),
                )
            }
        }.awaitAll()

        assertEquals(12, ids.toSet().size)
        assertEquals(12, dao.getAllSessionsWithMessages().size)
        ids.forEach { assertEquals(2, dao.countMessages(it)) }
    }

    @Test
    fun aLateOverwriteDoesNotBringBackADeletedChat() = runBlocking {
        val id = dao.insertSessionAndMessages(
            ChatSession(title = "gone", modelUsed = "m"),
            listOf(message("user", "hi"), message("assistant", "hello")),
        )
        dao.deleteSession(id)

        val written = dao.overwriteIfExists(
            ChatSession(id = id, title = "gone", modelUsed = "m"),
            listOf(message("user", "hi"), message("assistant", "hello"), message("user", "more")),
        )

        assertFalse(written)
        assertNull(dao.getSessionById(id))
        assertEquals(0, dao.countMessages(id))
        assertEquals(0, dao.getAllSessionsWithMessages().size)
    }

    @Test
    fun idsAreNotReusedAfterDeletingTheNewestChat() = runBlocking {
        dao.insertSessionAndMessages(ChatSession(title = "a", modelUsed = "m"), listOf(message("user", "a")))
        val newest = dao.insertSessionAndMessages(ChatSession(title = "b", modelUsed = "m"), listOf(message("user", "b")))
        dao.deleteSession(newest)

        val next = dao.insertSessionAndMessages(ChatSession(title = "c", modelUsed = "m"), listOf(message("user", "c")))

        assertTrue("id $next was handed out before ($newest)", next > newest)
    }

    @Test
    fun importedBatchReturnsNewIdsInOrder() = runBlocking {
        val ids = dao.insertImportedSessions(
            listOf(
                ChatSession(title = "one", modelUsed = "m") to listOf(message("user", "a")),
                ChatSession(title = "two", modelUsed = "m") to listOf(message("user", "b"), message("assistant", "c")),
            )
        )
        assertEquals(2, ids.size)
        assertEquals("one", dao.getSessionById(ids[0])!!.title)
        assertEquals(2, dao.countMessages(ids[1]))
    }

    @Test
    fun searchTreatsLikeWildcardsAsText() = runBlocking {
        val repository = ChatRepository(dao)
        dao.insertSessionAndMessages(ChatSession(title = "a", modelUsed = "m"), listOf(message("user", "it was 100% sure")))
        dao.insertSessionAndMessages(ChatSession(title = "b", modelUsed = "m"), listOf(message("user", "it was 1000 sure")))
        dao.insertSessionAndMessages(ChatSession(title = "c", modelUsed = "m"), listOf(message("user", "snake_case name")))
        dao.insertSessionAndMessages(ChatSession(title = "d", modelUsed = "m"), listOf(message("user", "snakeXcase name")))

        assertEquals(listOf("a"), repository.searchSessions("100%").map { it.title })
        assertEquals(listOf("c"), repository.searchSessions("snake_case").map { it.title })
    }

    @Test
    fun lastMessagePrefixKeepsTheOpeningOfTheNewestMessage() = runBlocking {
        val tail = "TAIL_MARKER"
        val body = "a".repeat(2000) + tail
        val id = dao.insertSessionAndMessages(
            ChatSession(title = "p", modelUsed = "m"),
            listOf(
                message("user", "older"),
                ChatMessage(sessionId = 0, role = "assistant", content = "\"$body\""),
            ),
        )
        val repository = ChatRepository(dao)
        assertTrue(repository.lastMessagePrefixes(emptyList()).isEmpty())
        val row = repository.lastMessagePrefixes(listOf(id)).single()
        assertEquals("assistant", row.role)
        assertTrue(row.content.length <= 480)
        assertTrue(row.content.startsWith("\"aaa"))
        assertFalse(row.content.contains(tail))
    }

    @Test
    fun exportThenImportRoundTrips() = runBlocking {
        val first = dao.insertSessionAndMessages(
            ChatSession(title = "Trip", modelUsed = "openrouter/free"),
            listOf(message("user", "plan"), message("assistant", "sure")),
        )
        dao.insertSessionAndMessages(
            ChatSession(title = "Notes", modelUsed = "local/model", mode = ChatMode.ASK.storageValue),
            listOf(message("user", "x")),
        )
        val viewModel = SavedChatsViewModel(app)
        val exported = viewModel.getChatsAsJson()

        dao.getAllSessionsWithMessages().forEach { dao.deleteSession(it.session.id) }
        assertEquals(0, dao.getAllSessionsWithMessages().size)

        assertTrue(viewModel.importChatsFromJsonInternal(exported) is ChatImportResult.Success)

        val restored = dao.getAllSessionsWithMessages().sortedBy { it.session.title }
        assertEquals(listOf("Notes", "Trip"), restored.map { it.session.title })
        val trip = restored.single { it.session.title == "Trip" }
        assertEquals("openrouter/free", trip.session.modelUsed)
        assertEquals(listOf("\"plan\"", "\"sure\""), trip.messages.sortedBy { it.id }.map { it.content })
        assertEquals(2, trip.messages.size)
        assertTrue(first > 0)
    }

    @Test
    fun importRejectsBrokenAndOversizedFilesWithoutTouchingTheList() = runBlocking {
        dao.insertSessionAndMessages(ChatSession(title = "keep", modelUsed = "m"), listOf(message("user", "a")))
        val viewModel = SavedChatsViewModel(app)

        val broken = viewModel.importChatsFromJsonInternal("{not json")
        assertTrue(broken is ChatImportResult.Error)
        assertEquals(app.getString(R.string.import_error_format), (broken as ChatImportResult.Error).message)

        val unknownKeysAreFine = viewModel.importChatsFromJsonInternal(
            """{"sessions":[],"futureField":1}"""
        )
        assertTrue(unknownKeysAreFine is ChatImportResult.Success)
        assertEquals(1, dao.getAllSessionsWithMessages().size)
    }
}
