package io.github.stardomains3.oxproxion

/**
 * Session persistence helpers. Ids come from the database ([ChatDao.insertSessionAndMessages]);
 * an open chat is rewritten in place by [ChatDao.overwriteIfExists].
 */
object ChatSessionSaver {
    /**
     * Saves [session] and returns the id it ended up under. [existingId] is the open chat's row:
     * when it is gone (deleted while this save was queued) nothing is written and the result is
     * null, so the caller can drop the save instead of resurrecting the chat.
     */
    suspend fun save(
        repository: ChatRepository,
        existingId: Long?,
        session: ChatSession,
        messages: List<ChatMessage>
    ): Long? =
        if (existingId != null) {
            if (repository.overwriteIfExists(session.copy(id = existingId), messages)) existingId else null
        } else {
            repository.insertSessionAndMessages(session.copy(id = 0), messages)
        }
}
