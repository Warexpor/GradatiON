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

    /**
     * A Continue grew the reply the user was looking at: that alternate becomes [text] and the
     * others stay. With no alternates yet, [text] seeds the list.
     */
    fun replaceSelected(alts: List<String>, selectedIndex: Int, text: String): Pair<List<String>, Int> {
        if (alts.isEmpty()) return listOf(text) to 0
        val index = selectedIndex.coerceIn(0, alts.lastIndex)
        return alts.toMutableList().also { it[index] = text } to index
    }

    /**
     * › steps to the next stored alternate. On the last one it does nothing: a new alternate
     * only ever comes from Regenerate, so an arrow never silently costs a generation.
     */
    fun canStepNext(selectedIndex: Int, total: Int): Boolean = selectedIndex < total - 1
}
