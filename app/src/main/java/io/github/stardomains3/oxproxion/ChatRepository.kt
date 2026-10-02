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

    /** All or nothing; returns the new session ids in order. [beforeCommit] runs inside the transaction. */
    suspend fun insertImportedSessions(
        batch: List<Pair<ChatSession, List<ChatMessage>>>,
        beforeCommit: suspend (List<Long>) -> Unit = {},
    ): List<Long> = chatDao.insertImportedSessions(batch, beforeCommit)

    suspend fun messageCount(sessionId: Long): Int = chatDao.messageCount(sessionId)

    suspend fun updateSessionTitle(sessionId: Long, newTitle: String) {
        chatDao.updateSessionTitle(sessionId, newTitle)
    }

    suspend fun remapSessionCharacterId(oldId: Long, newId: Long) {
        chatDao.remapSessionCharacterId(oldId, newId)
    }

    suspend fun deleteSession(sessionId: Long) {
        chatDao.deleteSession(sessionId)
    }

    /** Scene-photo file names stored on this chat's messages. */
    suspend fun scenePhotoNames(sessionId: Long): List<String> =
        chatDao.scenePhotoSlices(sessionId).mapNotNull { ScenePhoto.fileNameIn(it) }.distinct()

    /** True when a saved message still names this scene photo. */
    suspend fun scenePhotoStillUsed(name: String): Boolean {
        if (!ScenePhoto.isSceneFileName(name)) return false
        return chatDao.messageMentioning("/owned/scene_photos/$name") != null
    }

    suspend fun getAllSessionsWithMessages(): List<SessionWithMessages> =
        chatDao.getAllSessionsWithMessages()

    suspend fun getAllSessionsOnce(): List<ChatSession> = chatDao.getAllSessionsOnce()
    suspend fun searchSessions(query: String, mode: ChatMode = ChatMode.ASK): List<ChatSession> {
        val needle = HistoryList.normalizeQuery(query)
        if (needle.isEmpty()) return emptyList()
        val sessionIds = chatDao.searchSessionIds(HistoryList.likeContains(needle), mode.storageValue)
        return sessionIds.mapNotNull { chatDao.getSessionById(it) }
    }

    /**
     * A window around the newest matching message in each session. Empty [query] and an
     * empty id list stay out of the DAO.
     */
    suspend fun searchWindows(sessionIds: List<Long>, query: String): List<MessageWindow> {
        val needle = HistoryList.normalizeQuery(query)
        if (sessionIds.isEmpty() || needle.isEmpty()) return emptyList()
        val pattern = HistoryList.likeContains(needle)
        // The phrase may span a newline in storage; center on the first word.
        val anchor = HistoryList.searchAnchor(needle)
        return sessionIds.distinct().chunked(200).flatMap {
            chatDao.searchMessageWindows(it, pattern, anchor, SEARCH_WINDOW)
        }
    }

    /** Empty input stays out of the DAO: SQLite rejects `IN ()`. Chunked under the variable cap. */
    suspend fun lastMessagePrefixes(sessionIds: List<Long>): List<ChatMessage> {
        if (sessionIds.isEmpty()) return emptyList()
        return sessionIds.distinct().chunked(200).flatMap { chatDao.lastMessagePrefixes(it) }
    }

    private companion object {
        const val SEARCH_WINDOW = 240
    }
}