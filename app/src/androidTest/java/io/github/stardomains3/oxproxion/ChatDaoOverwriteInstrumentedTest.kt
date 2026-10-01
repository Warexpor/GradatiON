package io.github.stardomains3.oxproxion

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDaoOverwriteInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChatDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.chatDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun overwriteReplacesMessagesWithoutDuplicating() = runBlocking {
        val sessionId = dao.insertSessionAndMessages(
            ChatSession(title = "t", modelUsed = "m"),
            listOf(
                ChatMessage(sessionId = 0, role = "user", content = "\"hi\""),
                ChatMessage(sessionId = 0, role = "assistant", content = "\"hello\"")
            )
        )
        assertEquals(2, dao.getMessagesForSession(sessionId).size)

        val longer = listOf(
            ChatMessage(sessionId = sessionId, role = "user", content = "\"hi\""),
            ChatMessage(sessionId = sessionId, role = "assistant", content = "\"hello\""),
            ChatMessage(sessionId = sessionId, role = "user", content = "\"again\""),
            ChatMessage(sessionId = sessionId, role = "assistant", content = "\"ok\"")
        )
        val session = ChatSession(id = sessionId, title = "t2", modelUsed = "m")
        assertTrue(dao.overwriteIfExists(session, longer))
        assertEquals(4, dao.getMessagesForSession(sessionId).size)

        assertTrue(dao.overwriteIfExists(session, longer))
        assertEquals(4, dao.getMessagesForSession(sessionId).size)
    }
}
