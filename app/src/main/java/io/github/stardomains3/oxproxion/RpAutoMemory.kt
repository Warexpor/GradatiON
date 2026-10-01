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
    /** A photo the user sent with no caption, so the summary still knows it was in the scene. */
    const val PHOTO_BEAT = "(shows a photo)"
    /** Watermark for a chat that has not been saved yet. */
    const val UNSAVED_KEY = -1L
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

    /**
     * Remember [messageCount] only after the note was saved. A failed rewrite keeps [previous]
     * so the next reply can try again instead of waiting another [EVERY] messages.
     */
    fun watermarkAfter(previous: Int, messageCount: Int, saved: Boolean): Int =
        if (saved) messageCount else previous

    /**
     * Which chat the watermark belongs to. A chat that was unsaved when the note started and
     * has an id by the time it lands keeps the mark on that id. A chat that was left behind
     * does not lend its mark to whatever is open now.
     */
    fun runKey(launchSessionId: Long?, currentSessionId: Long?, sameChat: Boolean): Long? =
        if (sameChat) currentSessionId ?: launchSessionId ?: UNSAVED_KEY else launchSessionId

    /** Caption when there is one. A picture with no words is still a beat. */
    fun turnBody(text: String, showedPhoto: Boolean): String {
        val body = text.trim()
        if (body.isNotBlank()) return body
        return if (showedPhoto) PHOTO_BEAT else ""
    }

    /** Hidden rewrite and photo-boilerplate turns are not story, so they never become facts. */
    fun isMachinery(text: String): Boolean {
        val t = text.trim()
        return t == RpPromptEngine.PHOTO_TURN ||
            (t.startsWith("(OOC:") && "Rewrite your last reply" in t)
    }

    /** "Name: text" lines, newest last, trimmed from the front to [TRANSCRIPT_CHARS]. */
    fun transcript(turns: List<Pair<String, String>>, charName: String, userName: String): String {
        val lines = turns.filter { it.second.isNotBlank() && !isMachinery(it.second) }.map { (role, text) ->
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

    fun prompt(charName: String, userName: String, userMemory: String, facts: String, transcript: String): String = buildString {
        append("You keep the fact notes for an ongoing story between ")
        append(charName).append(" and ").append(userName).append(".\n\n")
        append("Memory the user wrote (already kept; do not repeat it and do not change it):\n")
        append(userMemory.ifBlank { "(empty)" }).append("\n\n")
        append("Current facts:\n").append(facts.ifBlank { "(empty)" }).append("\n\n")
        append("Recent story:\n```\n").append(transcript).append("\n```\n\n")
        append("Rewrite the facts so they hold what the story needs later. Use exactly these sections, ")
        append("and omit a section if it has nothing:\n")
        append("About you:\n- lines about ").append(userName).append("\n")
        append("About the character:\n- lines about ").append(charName).append("\n")
        append("Others:\n- Name: one fact about someone who is neither of them\n\n")
        append("Keep facts that are still true. Drop small talk and scenery. ")
        append("Write short plain lines starting with \"- \", at most 12 lines in all, no commentary. ")
        append("Reply with the sections only.")
    }

    /**
     * The model's reply as a memory note, or null when it isn't usable.
     * [userMemory] is the note the user wrote; lines copied from it are dropped, because that
     * note already rides in every prompt and must not be saved again as facts.
     */
    fun clean(reply: String?, userMemory: String = ""): String? {
        if (reply.isNullOrBlank()) return null
        var t = reply.trim()
        if (t.startsWith("Error:")) return null
        // Reasoning models sometimes leak their scratchpad, ahead of any fence. An unclosed
        // tag means the rest of the reply is still scratch, same as a story reply.
        t = t.replace(Regex("(?s)<think>.*?</think>"), "")
        t = t.replace(Regex("(?s)<think>.*"), "")
        if (t.contains("</think>")) t = t.substringAfterLast("</think>")
        t = t.trim()
        // The opening fence can carry a language tag ("```text"); it must go with it, not become a fact.
        t = t.replace(Regex("^```[A-Za-z0-9_-]*[ \\t]*\\n?"), "").removeSuffix("```").trim()
        val protected = memoryLines(userMemory)
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it.startsWith("* ") || it.startsWith("• ")) "- " + it.drop(2) else it }
            .filterNot { repeatsMemory(it, protected) }
        if (lines.isEmpty()) return null
        val note = lines.joinToString("\n")
        if (note.length <= MEMORY_CHARS) return note
        val cut = note.take(MEMORY_CHARS)
        // Whole lines only, unless the cut already lands on a line's end (then nothing is half-written) or there is no break to fall back on.
        return if (note[MEMORY_CHARS] == '\n') cut else cut.substringBeforeLast('\n', cut)
    }

    private fun memoryLines(userMemory: String): Set<String> =
        userMemory.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun repeatsMemory(line: String, protected: Set<String>): Boolean {
        if (protected.isEmpty()) return false
        if (line in protected) return true
        val bare = line.removePrefix("- ").trim()
        return bare in protected || "- $bare" in protected
    }
}
