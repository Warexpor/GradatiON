package io.github.stardomains3.oxproxion

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/** A short slice of one message around a search hit. [content] is not the whole row. */
data class MessageWindow(
    val sessionId: Long,
    val role: String,
    val content: String,
)

data class SessionWithMessages(
    val session: ChatSession,
    val messages: List<ChatMessage>
)

@Dao
interface ChatDao {
    // ABORT, not REPLACE: REPLACE deletes the old row first, which cascades away its messages and
    // quietly resurrects a chat that was deleted while a save was in flight.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: ChatSession): Long
    @Update
    suspend fun updateSession(session: ChatSession): Int
    @Insert(onConflict = OnConflictStrategy.ABORT)
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

    /**
     * Every chat with its messages. Not a Room relation: that loads `SELECT *` and a long
     * attachment used to throw once the row no longer fit in the cursor window.
     */
    suspend fun getAllSessionsWithMessages(): List<SessionWithMessages> =
        getAllSessionsOnce().map { session ->
            SessionWithMessages(session, getMessagesForSession(session.id))
        }

    /** Sessions only, oldest id first so a backup is the same file twice in a row. */
    @Query("SELECT * FROM chat_sessions ORDER BY id ASC")
    suspend fun getAllSessionsOnce(): List<ChatSession>

    /**
     * Id, role and character length only. The text itself is loaded by [getMessagesForSession],
     * in slices when one row would not fit in Android's cursor window.
     */
    @Query(
        """
        SELECT id, sessionId, role, length(content) AS contentLength
        FROM chat_messages
        WHERE sessionId = :sessionId
        ORDER BY id ASC
        """
    )
    suspend fun messageHeads(sessionId: Long): List<ChatMessageHead>

    @Query(
        """
        SELECT id, sessionId, role, length(content) AS contentLength
        FROM chat_messages
        WHERE sessionId = :sessionId
        ORDER BY id DESC
        LIMIT 1
        """
    )
    suspend fun lastMessageHead(sessionId: Long): ChatMessageHead?

    @Query("SELECT content FROM chat_messages WHERE id = :id")
    suspend fun messageContent(id: Long): String?

    /** [startInclusive] is 1-based, matching SQLite substr. */
    @Query("SELECT substr(content, :startInclusive, :length) AS content FROM chat_messages WHERE id = :id")
    suspend fun messageContentSlice(id: Long, startInclusive: Int, length: Int): String?

    @Transaction
    suspend fun getMessagesForSession(sessionId: Long): List<ChatMessage> =
        messageHeads(sessionId).map { it.load(this) }

    @Transaction
    suspend fun getLastMessage(sessionId: Long): ChatMessage? =
        lastMessageHead(sessionId)?.load(this)

    /**
     * The newest message of each session, with content cut to the first 480 characters.
     * A photo is stored as a multi-megabyte data URL; a history row only needs the opening.
     */
    @Query(
        """
        SELECT id, sessionId, role, substr(content, 1, 480) AS content
        FROM chat_messages
        WHERE id IN (
            SELECT MAX(id) FROM chat_messages
            WHERE sessionId IN (:sessionIds)
            GROUP BY sessionId
        )
        """
    )
    suspend fun lastMessagePrefixes(sessionIds: List<Long>): List<ChatMessage>

    /**
     * The newest message in each session that matches [pattern], cut to a window around
     * [needle]. History only needs the line that matched, not a multi-megabyte photo.
     */
    @Query(
        """
        SELECT m.sessionId AS sessionId, m.role AS role,
            substr(
                m.content,
                MAX(1, instr(lower(m.content), lower(:needle)) - 48),
                :span
            ) AS content
        FROM chat_messages m
        INNER JOIN (
            SELECT sessionId, MAX(id) AS mid
            FROM chat_messages
            WHERE sessionId IN (:sessionIds)
              AND content LIKE :pattern ESCAPE '\'
            GROUP BY sessionId
        ) hit ON m.id = hit.mid
        """
    )
    suspend fun searchMessageWindows(
        sessionIds: List<Long>,
        pattern: String,
        needle: String,
        span: Int,
    ): List<MessageWindow>

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

    /**
     * Saves a chat that has no row yet and returns the id SQLite generated. The id on [session]
     * is ignored (AUTOINCREMENT never hands one out twice, even after the newest chat is deleted),
     * so two saves racing each other cannot land on the same id.
     */
    @Transaction
    suspend fun insertSessionAndMessages(session: ChatSession, messages: List<ChatMessage>): Long {
        val sessionId = insertSession(session.copy(id = 0))
        insertMessages(messages.map { it.copy(id = 0, sessionId = sessionId) })
        return sessionId
    }

    /**
     * Saves an open chat again. Returns false and writes nothing when its row is gone (deleted
     * while the save was queued), so a late autosave cannot bring a deleted chat back.
     */
    @Transaction
    suspend fun overwriteIfExists(session: ChatSession, messages: List<ChatMessage>): Boolean {
        if (updateSession(session) == 0) return false
        deleteMessagesForSession(session.id)
        insertMessages(messages.map { it.copy(id = 0, sessionId = session.id) })
        return true
    }

    /** Every imported chat in one transaction, so a failure part-way leaves the list untouched. */
    @Transaction
    suspend fun insertImportedSessions(batch: List<Pair<ChatSession, List<ChatMessage>>>): List<Long> =
        batch.map { (session, messages) -> insertSessionAndMessages(session, messages) }
}

internal suspend fun ChatMessageHead.load(dao: ChatDao): ChatMessage =
    ChatMessage(
        id = id,
        sessionId = sessionId,
        role = role,
        content = ChatMessageText.read(
            sqliteLength = contentLength,
            full = { dao.messageContent(id).orEmpty() },
            slice = { start, len -> dao.messageContentSlice(id, start, len).orEmpty() }
        )
    )
