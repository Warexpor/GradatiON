/** Pure helpers for RP API history trimming (unit-tested). */
object RpApiMemory {
    /** Once history has already been cut, keep this many characters from the start of the card. */
    const val DEFINITION_HEAD = 4_000

    /**
     * Keep [budget] non-system messages. Drop order: ordinary history first, then the greeting
     * if the window is still tight. A user-pinned line and the latest turn stay even if that
     * runs past [budget].
     */
    fun <T> trimNonSystem(
        nonSystem: List<T>,
        budget: Int,
        pinCharacterGreeting: Boolean,
        isAssistant: (T) -> Boolean,
        isPinned: (T) -> Boolean = { false }
    ): List<T> {
        if (nonSystem.isEmpty() || budget <= 0) return emptyList()
        if (nonSystem.size <= budget) return nonSystem
        val keep = sortedSetOf<Int>()
        keep += nonSystem.lastIndex
        nonSystem.forEachIndexed { i, message -> if (isPinned(message)) keep += i }
        var room = budget - keep.size
        if (pinCharacterGreeting && room > 0) {
            val greeting = nonSystem.indexOfFirst(isAssistant)
            if (greeting >= 0 && greeting !in keep) {
                keep += greeting
                room -= 1
            }
        }
        if (room > 0) {
            for (i in nonSystem.indices.reversed()) {
                if (room <= 0) break
                if (i in keep) continue
                keep += i
                room -= 1
            }
        }
        return keep.sorted().map { nonSystem[it] }
    }

    /**
     * Local models: keep the newest run of history that fits [tokenBudget]. Same priorities as
     * [trimNonSystem] (latest turn and pinned lines always stay, then the greeting, then the
     * newest history), but sized in tokens because a local window is fixed and small. Stops at
     * the first line that doesn't fit so the kept history stays contiguous.
     */
    fun <T> trimToTokenBudget(
        nonSystem: List<T>,
        tokenBudget: Int,
        tokensOf: (T) -> Int,
        pinCharacterGreeting: Boolean,
        isAssistant: (T) -> Boolean,
        isPinned: (T) -> Boolean = { false }
    ): List<T> {
        if (nonSystem.isEmpty()) return emptyList()
        val keep = sortedSetOf<Int>()
        keep += nonSystem.lastIndex
        nonSystem.forEachIndexed { i, message -> if (isPinned(message)) keep += i }
        var used = keep.sumOf { tokensOf(nonSystem[it]) }
        if (pinCharacterGreeting) {
            val greeting = nonSystem.indexOfFirst(isAssistant)
            if (greeting >= 0 && greeting !in keep) {
                val cost = tokensOf(nonSystem[greeting])
                if (used + cost <= tokenBudget) {
                    keep += greeting
                    used += cost
                }
            }
        }
        for (i in nonSystem.indices.reversed()) {
            if (i in keep) continue
            val cost = tokensOf(nonSystem[i])
            if (used + cost > tokenBudget) break
            keep += i
            used += cost
        }
        return keep.map { nonSystem[it] }
    }

    /**
     * Null while the chat still fits in [historyBudget]: history is dropped before the card.
     * "All messages" never cuts the card.
     */
    fun definitionCap(messageCount: Int, historyBudget: Int): Int? {
        if (historyBudget >= 10_000 || messageCount <= historyBudget) return null
        return DEFINITION_HEAD
    }

    /** Stable id for a pinned line. The chat table only stores role and content. */
    fun pinKey(role: String, text: String): String {
        val body = text.trim().replace(Regex("\\s+"), " ").take(180)
        return "$role:$body"
    }
}
