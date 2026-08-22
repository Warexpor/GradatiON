package io.github.stardomains3.oxproxion

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine

/**
 * Shared SSE / NDJSON payload reader for chat streaming.
 * Extracted from ChatViewModel — behavior preserved.
 */
object SseJsonReader {
    /**
     * Reads SSE (`data:` lines, blank-line delimited) with NDJSON fallback.
     * Stops after OpenAI-style `[DONE]`, or when [shouldStop] returns true, so keep-alive
     * connections do not leave the UI stuck on Stop.
     */
    suspend fun forEachJsonPayload(
        channel: ByteReadChannel,
        onPayload: suspend (String) -> Unit,
        shouldStop: (() -> Boolean)? = null
    ) {
        val dataLines = mutableListOf<String>()
        var sawDone = false
        suspend fun flushData(): Boolean {
            if (dataLines.isEmpty()) return false
            val payload = dataLines.joinToString("\n").trim()
            dataLines.clear()
            if (payload.isEmpty()) return false
            if (payload == "[DONE]" || payload.equals("DONE", ignoreCase = true)) {
                sawDone = true
                return true
            }
            onPayload(payload)
            return shouldStop?.invoke() == true
        }
        try {
            while (!channel.isClosedForRead && !sawDone && shouldStop?.invoke() != true) {
                val line = channel.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (flushData()) break
                    }
                    line.startsWith(":") -> Unit // comment / keepalive
                    line.startsWith("data:") -> {
                        val value = when {
                            line.startsWith("data: ") -> line.substring(6)
                            line.startsWith("data:\t") -> line.substring(6)
                            else -> line.substring(5).trimStart()
                        }
                        dataLines.add(value)
                    }
                    line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:") -> Unit
                    line.trimStart().startsWith("{") -> {
                        if (flushData()) break
                        onPayload(line.trim())
                        if (shouldStop?.invoke() == true) break
                    }
                    dataLines.isNotEmpty() -> dataLines.add(line)
                }
            }
            flushData()
        } catch (_: Exception) {
            flushData()
        }
    }
}
