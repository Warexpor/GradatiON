package io.github.stardomains3.oxproxion

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.TooLongLineException
import io.ktor.utils.io.readLineStrictTo
import kotlinx.io.EOFException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Shared SSE / NDJSON payload reader for chat streaming.
 * Extracted from ChatViewModel — behavior preserved.
 */
object SseJsonReader {
    /** One SSE line, and one assembled event, stop here. A bigger line is refused instead of allocated. */
    const val MAX_LINE_BYTES = 4L * 1024 * 1024
    const val MAX_EVENT_CHARS = 4 * 1024 * 1024

    /** Why a read returned. [CLOSED] is the server hanging up without saying it was finished. */
    enum class End { DONE, STOPPED, CLOSED }

    /**
     * Reads SSE (`data:` lines, blank-line delimited) with NDJSON fallback.
     * Stops after OpenAI-style `[DONE]`, or when [shouldStop] returns true, so keep-alive
     * connections do not leave the UI stuck on Stop. A network failure flushes what was
     * buffered and is then rethrown, so a half-received reply never passes for a whole one.
     */
    suspend fun forEachJsonPayload(
        channel: ByteReadChannel,
        onPayload: suspend (String) -> Unit,
        shouldStop: (() -> Boolean)? = null,
        maxLineBytes: Long = MAX_LINE_BYTES,
        maxEventChars: Int = MAX_EVENT_CHARS,
    ): End {
        val dataLines = mutableListOf<String>()
        var eventChars = 0
        var sawDone = false
        fun addData(value: String) {
            eventChars += value.length
            if (eventChars > maxEventChars) throw IOException("Stream event is too large")
            dataLines.add(value)
        }
        suspend fun flushData(): Boolean {
            if (dataLines.isEmpty()) return false
            val payload = dataLines.joinToString("\n").trim()
            dataLines.clear()
            eventChars = 0
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
                val line = channel.readBoundedLine(maxLineBytes) ?: break
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
                        addData(value)
                    }
                    line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:") -> Unit
                    line.trimStart().startsWith("{") -> {
                        if (line.length > maxEventChars) throw IOException("Stream event is too large")
                        if (flushData()) break
                        onPayload(line.trim())
                        if (shouldStop?.invoke() == true) break
                    }
                    dataLines.isNotEmpty() -> addData(line)
                }
            }
            // A channel closed by a network failure reads as "closed" above; report the cause.
            channel.closedCause?.let { throw it }
            flushData()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            try {
                flushData()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // The original failure is the one worth reporting.
            }
            throw e
        }
        return when {
            sawDone -> End.DONE
            shouldStop?.invoke() == true -> End.STOPPED
            else -> End.CLOSED
        }
    }

    /**
     * [readLineStrictTo] refuses a line past [maxBytes] instead of growing it until the process dies.
     * A stream that simply ends mid-line still returns that line: the strict reader throws [EOFException]
     * after writing what it had, and a cut reply is not a failure by itself.
     */
    private suspend fun ByteReadChannel.readBoundedLine(maxBytes: Long): String? {
        val out = StringBuilder()
        return try {
            val read = readLineStrictTo(out, maxBytes)
            if (read < 0) null else out.toString()
        } catch (_: EOFException) {
            out.toString().takeIf { it.isNotEmpty() }
        } catch (e: TooLongLineException) {
            throw IOException("Stream event is too large", e)
        }
    }
}
