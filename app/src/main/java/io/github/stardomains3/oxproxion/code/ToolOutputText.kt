package io.github.stardomains3.oxproxion.code

/**
 * Pure helpers for tool-output card preview and the full viewer (search filter).
 * Phone-side storage stays capped at [AcpAdapter.MAX_OUTPUT]; there is no bridge
 * full-log RPC yet, so the viewer shows whatever is already in the event.
 */
object ToolOutputText {

    /** Lines shown in the transcript card (tail). */
    const val CARD_LINES = 40

    fun cardPreview(output: String, maxLines: Int = CARD_LINES): String =
        output.lines().takeLast(maxLines).joinToString("\n")

    /** Case-insensitive line filter; blank query returns [output] unchanged. */
    fun filterLines(output: String, query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return output
        return output.lineSequence()
            .filter { it.contains(q, ignoreCase = true) }
            .joinToString("\n")
    }

    /**
     * True when [AcpAdapter.textContent] prefixed a trimmed tail with an ellipsis
     * because the raw log exceeded [AcpAdapter.MAX_OUTPUT].
     */
    fun isPhoneTailTruncated(output: String): Boolean =
        output.startsWith('…') || output.startsWith("...")
}
