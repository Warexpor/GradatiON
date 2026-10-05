/** Pure helpers for RP API history trimming (unit-tested). */
object RpApiMemory {
    /** Once history has already been cut, keep this many characters from the start of the card. */
    const val DEFINITION_HEAD = 4_000

    /** Rough characters per token, kept low so text in other scripts still fits. */
    const val CHARS_PER_TOKEN = 3
    /** The window when the model's context size is unknown (LAN, custom, or an old model cache). */
    const val DEFAULT_CONTEXT_TOKENS = 32_768
    /** Left free for the reply. */
    const val REPLY_RESERVE_TOKENS = 4_096
    /** Room for the card, lore, Memory and Facts before the real prompt is built. */
    const val SYSTEM_RESERVE_CHARS = 24_000
    /** What a photo takes from the window, whatever its size. */
    const val IMAGE_CHARS = 3_000
    /** Role and framing around each message. */
    const val MESSAGE_OVERHEAD_CHARS = 16
    /** A tiny or unknown window still keeps a few turns. */
    private const val MIN_HISTORY_CHARS = 8_000

    /** The style reminder starts once the request holds this many turns. */
    const val REMINDER_FROM_TURNS = 6

    /**
     * Where the style reminder goes in a request with these [roles]: just before the newest user
     * turn, so the user still has the last word. -1 while the chat is short.
     */
    fun reminderIndex(roles: List<String>): Int {
        if (roles.count { it != "system" } < REMINDER_FROM_TURNS) return -1
        val last = roles.lastIndexOf("user")
        return if (last <= 0) -1 else last
    }

    /** Characters of history that fit a [contextTokens] window next to a [systemChars] prompt. */
    fun historyChars(contextTokens: Int?, systemChars: Int = SYSTEM_RESERVE_CHARS): Int {
        val tokens = contextTokens?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS
        val room = (tokens.toLong() - REPLY_RESERVE_TOKENS) * CHARS_PER_TOKEN - systemChars
        return room.coerceIn(MIN_HISTORY_CHARS.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /** How many of the newest messages fit in [maxChars]. The newest one always counts. */
    fun fitCount(sizesOldestFirst: List<Int>, maxChars: Int): Int {
        var used = 0L
        var n = 0
        for (i in sizesOldestFirst.indices.reversed()) {
            used += sizesOldestFirst[i]
            if (n > 0 && used > maxChars) break
            n++
        }
        return n
    }

    /**
     * Keep [budget] non-system messages. Drop order: ordinary history first, then the greeting
     * if the window is still tight. The latest turn stays even if that runs past [budget].
     */
    fun <T> trimNonSystem(
        nonSystem: List<T>,
        budget: Int,
        pinCharacterGreeting: Boolean,
        isAssistant: (T) -> Boolean
    ): List<T> {
        if (nonSystem.isEmpty() || budget <= 0) return emptyList()
        if (nonSystem.size <= budget) return nonSystem
        val keep = sortedSetOf<Int>()
        keep += nonSystem.lastIndex
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
     * Put [tail] at the end, even when the window had dropped it. A rewrite has to see the reply
     * it is changing and the turn just before that reply (often the photo), which a greeting pin
     * on a tiny budget would otherwise crowd out.
     */
    fun <T> pinTail(messages: List<T>, tail: List<T>, same: (T, T) -> Boolean): List<T> {
        if (tail.isEmpty()) return messages
        val rest = messages.toMutableList()
        for (msg in tail) {
            val idx = rest.indexOfLast { same(it, msg) }
            if (idx >= 0) rest.removeAt(idx)
        }
        rest.addAll(tail)
        return rest
    }

    /**
     * Null while the chat still fits in [historyBudget]: history is dropped before the card.
     * "All messages" never cuts the card.
     */
    fun definitionCap(messageCount: Int, historyBudget: Int): Int? {
        if (historyBudget >= 10_000 || messageCount <= historyBudget) return null
        return DEFINITION_HEAD
    }
}
