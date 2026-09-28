package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Test

class RpChatSummariesTest {
    private fun rp(id: Long, characterId: Long?, llm: Boolean = false) = ChatSession(
        id = id, title = "t$id", modelUsed = "m", mode = ChatMode.RP.storageValue,
        characterId = characterId, isLlm = llm
    )

    @Test fun aRowsDeleteTakesEveryChatWithThatCharacter() {
        val sessions = listOf(rp(1, 7), rp(2, 7), rp(3, 8), rp(4, null, llm = true))
        assertEquals(listOf(1L, 2L), RpChatSummaries.sessionIdsInRowOf(sessions, 2))
        assertEquals(listOf(3L), RpChatSummaries.sessionIdsInRowOf(sessions, 3))
        assertEquals(listOf(4L), RpChatSummaries.sessionIdsInRowOf(sessions, 4))
    }

    @Test fun askChatsNeverJoinARow() {
        val ask = ChatSession(id = 9, title = "a", modelUsed = "m", characterId = 7)
        assertEquals(listOf(1L), RpChatSummaries.sessionIdsInRowOf(listOf(rp(1, 7), ask), 1))
    }
}
