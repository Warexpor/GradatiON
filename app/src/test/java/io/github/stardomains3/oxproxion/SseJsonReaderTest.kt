package io.github.stardomains3.oxproxion

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The stream reader has to say how a stream ended, or a cut reply passes for a finished one. */
class SseJsonReaderTest {

    private fun read(
        text: String,
        shouldStop: (() -> Boolean)? = null,
        maxLineBytes: Long = SseJsonReader.MAX_LINE_BYTES,
        maxEventChars: Int = SseJsonReader.MAX_EVENT_CHARS,
    ): Pair<SseJsonReader.End, List<String>> = runBlocking {
        val payloads = mutableListOf<String>()
        val end = SseJsonReader.forEachJsonPayload(
            ByteReadChannel(text.toByteArray()),
            { payloads.add(it) },
            shouldStop,
            maxLineBytes,
            maxEventChars,
        )
        end to payloads
    }

    @Test
    fun doneMarkerEndsTheStream() {
        val (end, payloads) = read("data: {\"a\":1}\n\ndata: {\"a\":2}\n\ndata: [DONE]\n\n")
        assertEquals(SseJsonReader.End.DONE, end)
        assertEquals(listOf("{\"a\":1}", "{\"a\":2}"), payloads)
    }

    @Test
    fun streamThatEndsWithoutDoneIsClosed() {
        val (end, payloads) = read("data: {\"a\":1}\n\ndata: {\"a\":2}\n\n")
        assertEquals(SseJsonReader.End.CLOSED, end)
        assertEquals(2, payloads.size)
    }

    @Test
    fun truncatedLastEventIsStillFlushedButNotDone() {
        val (end, payloads) = read("data: {\"a\":1}\n\ndata: {\"a\":2")
        assertEquals(SseJsonReader.End.CLOSED, end)
        assertEquals(listOf("{\"a\":1}", "{\"a\":2"), payloads)
    }

    @Test
    fun multiLineDataJoinsIntoOnePayload() {
        val (end, payloads) = read("data: {\"a\":\ndata: 1}\n\ndata: [DONE]\n\n")
        assertEquals(SseJsonReader.End.DONE, end)
        assertEquals(listOf("{\"a\":\n1}"), payloads)
    }

    @Test
    fun ndjsonLinesAreEachAPayload() {
        val (end, payloads) = read("{\"a\":1}\n{\"a\":2}\n")
        assertEquals(SseJsonReader.End.CLOSED, end)
        assertEquals(listOf("{\"a\":1}", "{\"a\":2}"), payloads)
    }

    @Test
    fun commentsAndEventLinesAreSkipped() {
        val (_, payloads) = read(": keepalive\nevent: message\nid: 7\ndata: {\"a\":1}\n\ndata: [DONE]\n\n")
        assertEquals(listOf("{\"a\":1}"), payloads)
    }

    @Test
    fun shouldStopEndsEarly() {
        var stop = false
        val (end, payloads) = runBlocking {
            val seen = mutableListOf<String>()
            val result = SseJsonReader.forEachJsonPayload(
                ByteReadChannel("data: {\"a\":1}\n\ndata: {\"a\":2}\n\n".toByteArray()),
                { seen.add(it); stop = true },
                { stop },
            )
            result to seen
        }
        assertEquals(SseJsonReader.End.STOPPED, end)
        assertEquals(1, payloads.size)
    }

    @Test
    fun cancellationFromTheHandlerPropagates() {
        try {
            runBlocking {
                SseJsonReader.forEachJsonPayload(
                    ByteReadChannel("data: {\"a\":1}\n\n".toByteArray()),
                    { throw CancellationException("stop") },
                )
            }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // The reader must not swallow it.
        }
    }

    @Test
    fun cancellingTheReaderIsNotSwallowed() = runBlocking {
        val channel = ByteChannel()
        val reached = CompletableDeferred<Unit>()
        var rethrown = false
        val job = launch {
            try {
                reached.complete(Unit)
                SseJsonReader.forEachJsonPayload(channel, { })
            } catch (e: CancellationException) {
                rethrown = true
                throw e
            }
        }
        reached.await()
        delay(50)
        job.cancel()
        job.join()
        assertTrue("reader swallowed the cancellation", rethrown)
    }

    @Test
    fun aLinePastTheCapFailsInsteadOfGrowing() {
        try {
            read("data: " + "x".repeat(80) + "\n\n", maxLineBytes = 40)
            fail("expected the oversized line to fail")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("too large"))
        }
    }

    @Test
    fun severalDataLinesPastTheEventCapFail() {
        val body = buildString {
            repeat(4) { append("data: ").append("y".repeat(20)).append('\n') }
            append('\n')
        }
        try {
            read(body, maxEventChars = 30)
            fail("expected the oversized event to fail")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("too large"))
        }
    }

    @Test
    fun networkFailureSurfaces() {
        val channel = ByteChannel()
        channel.cancel(IOException("connection reset"))
        try {
            runBlocking { SseJsonReader.forEachJsonPayload(channel, { }) }
            fail("expected the read failure")
        } catch (e: IOException) {
            assertEquals("connection reset", e.message)
        }
    }
}
