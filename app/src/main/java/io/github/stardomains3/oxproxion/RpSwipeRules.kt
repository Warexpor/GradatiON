package io.github.stardomains3.oxproxion

/** Pure helpers for RP swipe eligibility (unit-tested). */
object RpSwipeRules {
    /**
     * Swipe alts apply only to an assistant reply that follows a user turn —
     * never to a greeting-only thread.
     */
    fun isSwipeableTranscript(
        hasUserTurn: Boolean,
        lastUserIndex: Int,
        lastAssistantIndex: Int
    ): Boolean =
        hasUserTurn && lastUserIndex >= 0 && lastAssistantIndex > lastUserIndex

    fun <T> isSwipeableMessages(
        messages: List<T>,
        isUser: (T) -> Boolean,
        isAssistant: (T) -> Boolean
    ): Boolean {
        val lastUserIndex = messages.indexOfLast(isUser)
        val lastAssistantIndex = messages.indexOfLast(isAssistant)
        return isSwipeableTranscript(
            hasUserTurn = lastUserIndex >= 0,
            lastUserIndex = lastUserIndex,
            lastAssistantIndex = lastAssistantIndex
        )
    }

    /**
     * After truncating/deleting a suffix, keep alts only if the remaining last reply
     * is still one of them; otherwise reseed to that single reply.
     */
    fun reconcileAltsAfterTruncate(
        alts: List<String>,
        lastAssistantText: String
    ): Pair<List<String>, Int> {
        val matchIndex = alts.indexOf(lastAssistantText)
        return if (matchIndex >= 0) {
            alts to matchIndex
        } else {
            listOf(lastAssistantText) to 0
        }
    }

    /**
     * Before regen, ensure [currentText] is in [alts] without duplicating the already-selected seed.
     * Still appends when the visible bubble differs from the selected alt.
     * Returns the updated alts and the index to keep selected (stay on seed when not appending).
     */
    fun stashCurrentAlt(
        alts: List<String>,
        selectedIndex: Int,
        currentText: String
    ): Pair<List<String>, Int> {
        if (alts.getOrNull(selectedIndex) == currentText) return alts to selectedIndex
        if (alts.lastOrNull() == currentText) return alts to alts.lastIndex
        val next = alts + currentText
        return next to next.lastIndex
    }
}
