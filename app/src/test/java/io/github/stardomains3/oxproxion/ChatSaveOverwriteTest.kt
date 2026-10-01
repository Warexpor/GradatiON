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
    fun scenePhotoNamesStayWithTheChatThatStillHasThem() = runBlocking {
        val name = "11111111-1111-1111-1111-111111111111.jpg"
        val link = "/owned/scene_photos/$name"
        val kept = dao.insertSessionAndMessages(
            ChatSession(title = "kept", modelUsed = "m"),
            listOf(message("user", "plain"), message("assistant", "see $link later")),
        )
        val gone = dao.insertSessionAndMessages(
            ChatSession(title = "gone", modelUsed = "m"),
            listOf(message("user", "photo $link")),
        )
        val repo = ChatRepository(dao)
        assertEquals(listOf(name), repo.scenePhotoNames(gone))
        assertEquals(listOf(name), repo.scenePhotoNames(kept))
        assertTrue(repo.scenePhotoStillUsed(name))
        dao.deleteSession(gone)
        assertEquals(listOf(name), repo.scenePhotoNames(kept))
        assertTrue(repo.scenePhotoStillUsed(name))
        dao.deleteSession(kept)
        assertFalse(repo.scenePhotoStillUsed(name))
        assertTrue(repo.scenePhotoNames(kept).isEmpty())
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
        val hit = repository.searchWindows(
            repository.searchSessions("100%").map { it.id },
            "100%",
        ).single()
        assertTrue(hit.content.contains("100%"))
        assertTrue(repository.searchWindows(emptyList(), "100%").isEmpty())
        assertTrue(repository.searchWindows(listOf(hit.sessionId), "   ").isEmpty())
    }

    @Test
    fun searchWindowIsTheMatchingMessageNotTheLatestLine() = runBlocking {
        val repository = ChatRepository(dao)
        val id = dao.insertSessionAndMessages(
            ChatSession(title = "notes", modelUsed = "m"),
            listOf(
                message("user", "the secret word is lantern"),
                message("assistant", "goodbye"),
            ),
        )
        dao.insertSessionAndMessages(
            ChatSession(title = "zebra title only", modelUsed = "m"),
            listOf(message("assistant", "nothing to see")),
        )
        val hit = repository.searchWindows(listOf(id), "Lantern").single()
        assertEquals("user", hit.role)
        assertTrue(hit.content.contains("lantern"))
        assertFalse(hit.content.contains("goodbye"))
        val line = HistoryList.searchLine(hit.role, hit.content, "lantern", { "You: $it" }, "Photo")
        assertTrue(line.contains("lantern"))
        assertFalse(line.contains("goodbye"))
        assertTrue(repository.searchWindows(repository.searchSessions("zebra").map { it.id }, "zebra").isEmpty())
        assertEquals(listOf("notes"), repository.searchSessions("  lantern").map { it.title })
        assertTrue(repository.searchSessions("   ").isEmpty())
    }

    @Test
    fun searchSkipsPhotoBytesAndJsonKeys() = runBlocking {
        val repository = ChatRepository(dao)
        val photo = """[{"type":"text","text":"sunset on the pier"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,${"A".repeat(4000)}JPEGDATA"}}]"""
        dao.insertSessionAndMessages(
            ChatSession(title = "morning", modelUsed = "m"),
            listOf(ChatMessage(sessionId = 0, role = "user", content = photo)),
        )
        dao.insertSessionAndMessages(
            ChatSession(title = "plain", modelUsed = "m"),
            listOf(message("user", "text")),
        )
        dao.insertSessionAndMessages(
            ChatSession(title = "hello chat", modelUsed = "m"),
            listOf(ChatMessage(sessionId = 0, role = "user", content = """[{"type":"text","text":"hello"}]""")),
        )

        assertEquals(listOf("morning"), repository.searchSessions("sunset").map { it.title })
        assertEquals(listOf("morning"), repository.searchSessions("pier").map { it.title })
        assertTrue(repository.searchSessions("JPEGDATA").isEmpty())
        assertTrue(repository.searchSessions("jpeg").isEmpty())
        assertTrue(repository.searchSessions("image").isEmpty())
        assertTrue(repository.searchSessions("url").isEmpty())
        assertEquals(listOf("plain"), repository.searchSessions("text").map { it.title })
        assertEquals(listOf("hello chat"), repository.searchSessions("hello").map { it.title })
        assertTrue(repository.searchSessions("type").isEmpty())

        val hit = repository.searchWindows(repository.searchSessions("pier").map { it.id }, "pier").single()
        assertTrue(hit.content.contains("pier"))
        assertFalse(hit.content.contains("JPEGDATA"))
        assertFalse(hit.content.contains("base64"))
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

        val oversized = viewModel.importChatsFromJsonInternal("x".repeat(ImportBounds.MAX_TEXT_BYTES + 1))
        assertTrue(oversized is ChatImportResult.Error)
        assertEquals(
            app.getString(R.string.import_error_too_large, ImportBounds.MAX_TEXT_BYTES / (1024 * 1024)),
            (oversized as ChatImportResult.Error).message
        )
        assertEquals(1, dao.getAllSessionsWithMessages().size)
    }

    @Test
    fun importAcceptsAUtf8Bom() = runBlocking {
        dao.insertSessionAndMessages(
            ChatSession(title = "Trip", modelUsed = "m"),
            listOf(message("user", "plan")),
        )
        val viewModel = SavedChatsViewModel(app)
        val exported = "\uFEFF" + viewModel.getChatsAsJson()
        dao.getAllSessionsWithMessages().forEach { dao.deleteSession(it.session.id) }

        assertTrue(viewModel.importChatsFromJsonInternal(exported) is ChatImportResult.Success)
        assertEquals(listOf("Trip"), dao.getAllSessionsWithMessages().map { it.session.title })
    }

    @Test
    fun aLongMessageIsReadBackInSlicesAndRoundTrips() = runBlocking {
        val body = "abcdefghij" + "\uD83D\uDE00" + "XYZ"
        ChatMessageText.safeCharsForTest = 10
        ChatMessageText.sliceCharsForTest = 4
        try {
            dao.insertSessionAndMessages(
                ChatSession(title = "long", modelUsed = "m"),
                listOf(ChatMessage(sessionId = 0, role = "user", content = body)),
            )
            assertEquals(body, dao.getMessagesForSession(dao.getAllSessionsOnce().single().id).single().content)
            assertEquals(body, dao.getAllSessionsWithMessages().single().messages.single().content)
            assertEquals(body, dao.getLastMessage(dao.getAllSessionsOnce().single().id)!!.content)

            val viewModel = SavedChatsViewModel(app)
            val exported = viewModel.getChatsAsJson()
            dao.getAllSessionsWithMessages().forEach { dao.deleteSession(it.session.id) }
            assertTrue(viewModel.importChatsFromJsonInternal(exported) is ChatImportResult.Success)
            val restored = dao.getMessagesForSession(dao.getAllSessionsOnce().single().id).single()
            assertEquals(body, restored.content)
        } finally {
            ChatMessageText.safeCharsForTest = null
            ChatMessageText.sliceCharsForTest = null
        }
    }

    @Test
    fun exportKeepsTheDateThePinTheFactsAndOddCharacters() = runBlocking {
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val whenSaved = 1_700_000_000_000L
        val id = dao.insertSessionAndMessages(
            ChatSession(title = "A \"b\"", modelUsed = "m/x", timestamp = whenSaved, mode = ChatMode.RP.storageValue),
            listOf(ChatMessage(sessionId = 0, role = "user", content = "say \"hi\"\nnext")),
        )
        prefs.saveRpFacts(id, "fact \"one\"")
        prefs.setSessionPinned(id, true)

        val viewModel = SavedChatsViewModel(app)
        val exported = viewModel.getChatsAsJson()
        dao.getAllSessionsWithMessages().forEach { dao.deleteSession(it.session.id) }
        prefs.clearSessionPrefs(id)
        prefs.setSessionPinned(id, false)

        assertTrue(viewModel.importChatsFromJsonInternal(exported) is ChatImportResult.Success)
        val restored = dao.getAllSessionsWithMessages().single()
        assertEquals("A \"b\"", restored.session.title)
        assertEquals(whenSaved, restored.session.timestamp)
        assertEquals(ChatMode.RP.storageValue, restored.session.mode)
        assertEquals("say \"hi\"\nnext", restored.messages.single().content)
        assertEquals("fact \"one\"", prefs.getRpFacts(restored.session.id))
        assertTrue(prefs.isSessionPinned(restored.session.id))
    }

    @Test
    fun anOlderChatBackupStillImports() = runBlocking {
        val prefs = SharedPreferencesHelper(app)
        prefs.mainPrefs.edit().clear().commit()
        val viewModel = SavedChatsViewModel(app)
        val older = """{"sessions":[{"title":"Old","modelUsed":"m","messages":[{"role":"user","content":"\"hi\""}]}]}"""
        assertTrue(viewModel.importChatsFromJsonInternal(older) is ChatImportResult.Success)
        val restored = dao.getAllSessionsWithMessages().single()
        assertEquals("Old", restored.session.title)
        assertEquals("\"hi\"", restored.messages.single().content)
        assertTrue(restored.session.timestamp > 0L)
        assertEquals("", prefs.getRpFacts(restored.session.id))
        assertFalse(prefs.isSessionPinned(restored.session.id))
    }
}
