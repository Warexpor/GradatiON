package io.github.stardomains3.oxproxion.code

/**
 * Pure helpers for tool-output card preview and the full viewer (search filter).
 * Phone-side storage stays capped at [AcpAdapter.MAX_OUTPUT]; there is no bridge
 * full-log RPC yet, so the viewer shows whatever is already in the event.
 */
object ToolOutputText {

    /** Lines shown in the transcript card. */
    const val CARD_LINES = 40

    enum class KeptEnd { NONE, HEAD, TAIL }

    /**
     * File reads keep the start (the part you opened the file for). Shell and search
     * logs keep the end, where the failure usually is. Unknown kinds keep the tail,
     * which is what every tool used to do.
     */
    fun clip(kind: String?, text: String, max: Int): String {
        if (text.length <= max) return text
        return if (keepsHead(kind)) text.take(max) + "…" else "…" + text.takeLast(max)
    }

    fun keepsHead(kind: String?): Boolean = when (kind) {
        "read", "edit", "delete", "move" -> true
        else -> false
    }

    /**
     * Shell and search logs are a terminal. File tools are not: a read can contain
     * an escape on purpose. An update that has not named a kind yet is treated as a log.
     */
    fun stripsTerminal(kind: String?): Boolean = !keepsHead(kind)

    /**
     * @param head true for a file tool: the card shows the first lines, matching [clip].
     */
    fun cardPreview(output: String, maxLines: Int = CARD_LINES, head: Boolean = false): String {
        val lines = output.lines()
        val shown = if (head) lines.take(maxLines) else lines.takeLast(maxLines)
        return shown.joinToString("\n")
    }

    /** Case-insensitive line filter; blank query returns [output] unchanged. */
    fun filterLines(output: String, query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return output
        return output.lineSequence()
            .filter { it.contains(q, ignoreCase = true) }
            .joinToString("\n")
    }

    /**
     * True when [clip] prefixed a trimmed tail with an ellipsis because the raw log
     * exceeded [AcpAdapter.MAX_OUTPUT].
     */
    fun isPhoneTailTruncated(output: String): Boolean = keptEnd(output) == KeptEnd.TAIL

    /** Which end [clip] kept, so the full viewer can say first vs last. */
    fun keptEnd(output: String): KeptEnd = when {
        output.startsWith('…') || output.startsWith("...") -> KeptEnd.TAIL
        output.endsWith('…') || output.endsWith("...") -> KeptEnd.HEAD
        else -> KeptEnd.NONE
    }
}
