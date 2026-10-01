package io.github.stardomains3.oxproxion

import androidx.lifecycle.LiveData

class ChatRepository(private val chatDao: ChatDao) {

    val allSessions: LiveData<List<ChatSession>> = chatDao.getAllSessions()

    fun sessionsByMode(mode: ChatMode): LiveData<List<ChatSession>> =
        chatDao.getSessionsByMode(mode.storageValue)

    suspend fun getMessagesForSession(sessionId: Long): List<ChatMessage> {
        return chatDao.getMessagesForSession(sessionId)
    }

    suspend fun getLastMessage(sessionId: Long): ChatMessage? = chatDao.getLastMessage(sessionId)

    suspend fun getSessionById(sessionId: Long): ChatSession? {
        return chatDao.getSessionById(sessionId)
    }

    /** A chat with no row yet: returns the id the database generated. */
    suspend fun insertSessionAndMessages(session: ChatSession, messages: List<ChatMessage>): Long =
        chatDao.insertSessionAndMessages(session, messages)

    /** Saves an open chat again; false (and nothing written) when it was deleted meanwhile. */
    suspend fun overwriteIfExists(session: ChatSession, messages: List<ChatMessage>): Boolean =
        chatDao.overwriteIfExists(session, messages)

    /** All or nothing; returns the new session ids in order. */
    suspend fun insertImportedSessions(batch: List<Pair<ChatSession, List<ChatMessage>>>): List<Long> =
        chatDao.insertImportedSessions(batch)

    suspend fun updateSessionTitle(sessionId: Long, newTitle: String) {
        chatDao.updateSessionTitle(sessionId, newTitle)
    }

    suspend fun remapSessionCharacterId(oldId: Long, newId: Long) {
        chatDao.remapSessionCharacterId(oldId, newId)
    }

    suspend fun deleteSession(sessionId: Long) {
        chatDao.deleteSession(sessionId)
    }

    suspend fun getAllSessionsWithMessages(): List<SessionWithMessages> {
        return chatDao.getAllSessionsWithMessages()
    }
    suspend fun searchSessions(query: String, mode: ChatMode = ChatMode.ASK): List<ChatSession> {
        // Escape LIKE's wildcards so a search for "50%" or "a_b" matches the text, not everything.
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val sessionIds = chatDao.searchSessionIds("%$escaped%", mode.storageValue)
        return sessionIds.mapNotNull { chatDao.getSessionById(it) }
    }
}