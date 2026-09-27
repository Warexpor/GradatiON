package io.github.stardomains3.oxproxion

/**
 * Keeps a roleplay character's Memory current by itself. Long chats lose their oldest turns
 * from the API window ([RpApiMemory]); before that costs the story its facts, the model folds
 * what matters into the Memory note that rides in every prompt. Pure helpers; the call itself
 * lives in ChatViewModel.
 */
object RpAutoMemory {

    /** Start once this many messages or fewer are left before turns fall out of the window. */
    const val WINDOW_MARGIN = 6
    /** Then refresh at most once every this many messages. */
    const val EVERY = 6
    /** Transcript sent to the summarizer, newest last. */
    const val TRANSCRIPT_CHARS = 14_000
    const val MEMORY_CHARS = 1_600
    /**
     * With "All messages" nothing leaves the window, but long stories still outgrow the model's
     * context and bury early facts; treat that as a window of this many.
     */
    const val UNLIMITED_WINDOW = 60

    /**
     * Whether to run now. [messageCount] counts user and assistant turns in the chat, [budget]
     * is how many the API sees, [lastRunAt] the count at the previous update (0 = never).
     */
    fun shouldUpdate(messageCount: Int, budget: Int, lastRunAt: Int): Boolean {
        val window = if (budget >= 10_000) UNLIMITED_WINDOW else budget
        if (messageCount < window - WINDOW_MARGIN) return false
        return lastRunAt == 0 || messageCount - lastRunAt >= EVERY
    }

    /** "Name: text" lines, newest last, trimmed from the front to [TRANSCRIPT_CHARS]. */
    fun transcript(turns: List<Pair<String, String>>, charName: String, userName: String): String {
        val lines = turns.filter { it.second.isNotBlank() }.map { (role, text) ->
            val who = if (role == "assistant") charName else userName
            "$who: ${text.trim()}"
        }
        val out = ArrayDeque<String>()
        var size = 0
        for (line in lines.asReversed()) {
            if (size + line.length > TRANSCRIPT_CHARS && out.isNotEmpty()) break
            out.addFirst(line.take(TRANSCRIPT_CHARS))
            size += line.length + 1
        }
        return out.joinToString("\n")
    }

    fun prompt(charName: String, userName: String, memory: String, transcript: String): String = buildString {
        append("You keep the memory notes for an ongoing story between ")
        append(charName).append(" and ").append(userName).append(".\n\n")
        append("Current memory:\n").append(memory.ifBlank { "(empty)" }).append("\n\n")
        append("Recent story:\n```\n").append(transcript).append("\n```\n\n")
        append("Rewrite the memory so it holds the facts the story needs later: names, relationships, ")
        append("promises and debts, where things are, what happened that matters, and how ")
        append(charName).append(" feels about ").append(userName).append(". ")
        append("Keep facts from the current memory unless the story changed them. Drop small talk and scenery. ")
        append("Write short plain lines starting with \"- \", at most 12 lines, no headings, no Markdown ")
        append("beyond the dashes, no commentary. Reply with the lines only.")
    }

    /** The model's reply as a memory note, or null when it isn't usable. */
    fun clean(reply: String?): String? {
        if (reply.isNullOrBlank()) return null
        var t = reply.trim()
        if (t.startsWith("Error:")) return null
        t = t.removePrefix("```").removeSuffix("```").trim()
        // Reasoning models sometimes leak their scratchpad.
        t = t.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it.startsWith("* ") || it.startsWith("• ")) "- " + it.drop(2) else it }
        if (lines.isEmpty()) return null
        val note = lines.joinToString("\n")
        return if (note.length > MEMORY_CHARS) note.take(MEMORY_CHARS).substringBeforeLast('\n') else note
    }
}
