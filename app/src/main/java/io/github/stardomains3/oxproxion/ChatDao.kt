package io.github.stardomains3.oxproxion

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction

data class SessionWithMessages(
    @Embedded val session: ChatSession,
    @Relation(
        parentColumn = "id",
        entityColumn = "sessionId"
    )
    val messages: List<ChatMessage>
)

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSession): Long
    @Query("SELECT MAX(id) FROM chat_sessions") // Assuming your session table is named 'chat_sessions'
    suspend fun getMaxSessionId(): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessage>)

    @Query("SELECT * FROM chat_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): LiveData<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE mode = :mode ORDER BY timestamp DESC")
    fun getSessionsByMode(mode: String): LiveData<List<ChatSession>>
    @Query("""
    SELECT DISTINCT s.id 
    FROM chat_sessions s 
    LEFT JOIN chat_messages m ON s.id = m.sessionId 
    WHERE (s.title LIKE :query ESCAPE '\' OR m.content LIKE :query ESCAPE '\') AND s.mode = :mode
""")
    /** [query] is a LIKE pattern whose `%`, `_` and `\` are escaped with `\` (see [ChatRepository.searchSessions]). */
    suspend fun searchSessionIds(query: String, mode: String): List<Long>

    @Transaction
    @Query("SELECT * FROM chat_sessions")
    suspend fun getAllSessionsWithMessages(): List<SessionWithMessages>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getMessagesForSession(sessionId: Long): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY id DESC LIMIT 1")
    suspend fun getLastMessage(sessionId: Long): ChatMessage?

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun countMessages(sessionId: Long): Int

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): ChatSession?

    @Query("UPDATE chat_sessions SET title = :newTitle WHERE id = :sessionId")
    suspend fun updateSessionTitle(sessionId: Long, newTitle: String)

    @Query("UPDATE chat_sessions SET characterId = :newId WHERE characterId = :oldId")
    suspend fun remapSessionCharacterId(oldId: Long, newId: Long)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: Long)

    @Transaction
    suspend fun insertSessionAndMessages(session: ChatSession, messages: List<ChatMessage>): Long {
        val sessionId = insertSession(session)
        // REPLACE on session alone does not clear child rows — wipe then re-insert.
        deleteMessagesForSession(sessionId)
        val messagesWithSessionId = messages.map { it.copy(id = 0, sessionId = sessionId) }
        insertMessages(messagesWithSessionId)
        return sessionId
    }

    /** Every imported chat in one transaction, so a failure part-way leaves the list untouched. */
    @Transaction
    suspend fun insertImportedSessions(batch: List<Pair<ChatSession, List<ChatMessage>>>): List<Long> =
        batch.map { (session, messages) -> insertSessionAndMessages(session, messages) }
}
