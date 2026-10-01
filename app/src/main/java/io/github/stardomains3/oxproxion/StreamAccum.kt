package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.JsonPrimitive

/**
 * Growing assistant text for one SSE turn.
 *
 * `string += token` inside the read loop copies the whole reply on every token. The UI pump
 * already keeps only the latest partial and applies it at most once a frame, so those copies
 * never reach the screen. This appends into buffers and snapshots a [FlexibleMessage] at most
 * every [INTERVAL_NS], which is under that frame, so the words the reveal animator sees are
 * the same ones it would have been given.
 */
internal class StreamAccum {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private var lastNs = Long.MIN_VALUE
    private var generation = 0
    private var snapshotted = -1

    /** Set once either buffer has been cut. The stream should stop asking for more. */
    var capped: Boolean = false
        private set

    fun appendContent(text: String) {
        if (text.isEmpty()) return
        appendCapped(content, text)
    }

    fun appendReasoning(text: String) {
        if (text.isEmpty()) return
        appendCapped(reasoning, text)
    }

    private fun appendCapped(buffer: StringBuilder, text: String) {
        val cap = maxCharsForTest ?: MAX_CHARS
        if (buffer.length >= cap) {
            capped = true
            return
        }
        val room = cap - buffer.length
        if (text.length > room) {
            buffer.append(text, 0, room)
            capped = true
        } else {
            buffer.append(text)
        }
        generation++
    }

    fun clearReasoning() {
        if (reasoning.isEmpty()) return
        reasoning.setLength(0)
        generation++
    }

    fun content(): String = content.toString()

    fun reasoning(): String = reasoning.toString()

    /**
     * Latest partial, or null when nothing changed or the last snapshot is still fresh.
     * [force] publishes a dirty buffer even inside the interval (end of stream).
     */
    fun partial(nowNs: Long, force: Boolean = false): FlexibleMessage? {
        if (content.isEmpty() && reasoning.isEmpty()) return null
        if (generation == snapshotted) return null
        if (!force && lastNs != Long.MIN_VALUE && nowNs - lastNs < INTERVAL_NS) return null
        lastNs = nowNs
        snapshotted = generation
        val thoughts = reasoning.toString()
        return FlexibleMessage(
            role = "assistant",
            content = JsonPrimitive(content.toString()),
            reasoning = thoughts.ifBlank { null },
        )
    }

    companion object {
        const val INTERVAL_NS = 12_000_000L

        /** Well past a normal reply. Past this, more tokens are dropped instead of filling the heap. */
        const val MAX_CHARS = 1_500_000

        /** Tests shrink the cap. Null in production. */
        @androidx.annotation.VisibleForTesting
        var maxCharsForTest: Int? = null
    }
}
