package io.github.stardomains3.oxproxion

import androidx.lifecycle.LiveData

class ChatRepository(private val chatDao: ChatDao) {

    val allSessions: LiveData<List<ChatSession>> = chatDao.getAllSessions()

    fun sessionsByMode(mode: ChatMode): LiveData<List<ChatSession>> =
        chatDao.getSessionsByMode(mode.storageValue)

    suspend fun getMessagesForSession(sessionId: Long): List<ChatMessage> {
        return chatDao.getMessagesForSession(sessionId)
    }

    /** One message at a time, so an export can write a chat without holding every row. */
    suspend fun forEachMessage(sessionId: Long, block: suspend (ChatMessage) -> Unit) {
        for (head in chatDao.messageHeads(sessionId)) {
            block(head.load(chatDao))
        }
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

    suspend fun getAllSessionsWithMessages(): List<SessionWithMessages> =
        chatDao.getAllSessionsWithMessages()

    suspend fun getAllSessionsOnce(): List<ChatSession> = chatDao.getAllSessionsOnce()
    suspend fun searchSessions(query: String, mode: ChatMode = ChatMode.ASK): List<ChatSession> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val sessionIds = chatDao.searchSessionIds(likeContains(needle), mode.storageValue)
        return sessionIds.mapNotNull { chatDao.getSessionById(it) }
    }

    /**
     * A window around the newest matching message in each session. Empty [query] and an
     * empty id list stay out of the DAO.
     */
    suspend fun searchWindows(sessionIds: List<Long>, query: String): List<MessageWindow> {
        val needle = query.trim()
        if (sessionIds.isEmpty() || needle.isEmpty()) return emptyList()
        val pattern = likeContains(needle)
        return sessionIds.distinct().chunked(200).flatMap {
            chatDao.searchMessageWindows(it, pattern, needle, SEARCH_WINDOW)
        }
    }

    /** Empty input stays out of the DAO: SQLite rejects `IN ()`. Chunked under the variable cap. */
    suspend fun lastMessagePrefixes(sessionIds: List<Long>): List<ChatMessage> {
        if (sessionIds.isEmpty()) return emptyList()
        return sessionIds.distinct().chunked(200).flatMap { chatDao.lastMessagePrefixes(it) }
    }

    /** LIKE pattern that matches [query] as text. `%`, `_` and `\` are escaped with `\`. */
    private fun likeContains(query: String): String {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    private companion object {
        const val SEARCH_WINDOW = 240
    }
}