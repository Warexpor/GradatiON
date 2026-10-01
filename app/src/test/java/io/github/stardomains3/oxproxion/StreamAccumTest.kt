package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAccumTest {

    @Test fun firstTokenPublishesImmediately() {
        val a = StreamAccum()
        a.appendContent("Hi")
        val msg = a.partial(0L)
        assertEquals("Hi", msg?.content?.jsonPrimitive?.contentOrNull)
        assertNull(msg?.reasoning)
    }

    @Test fun tokensInsideTheIntervalStayOffTheUi() {
        val a = StreamAccum()
        a.appendContent("a")
        assertEquals("a", a.partial(0L)?.content?.jsonPrimitive?.contentOrNull)
        a.appendContent("b")
        assertNull(a.partial(StreamAccum.INTERVAL_NS - 1))
        assertEquals("ab", a.partial(StreamAccum.INTERVAL_NS)?.content?.jsonPrimitive?.contentOrNull)
    }

    @Test fun forceFlushesTheTailTheIntervalWasHolding() {
        val a = StreamAccum()
        a.appendContent("a")
        a.partial(0L)
        a.appendContent("bc")
        assertEquals("abc", a.partial(1L, force = true)?.content?.jsonPrimitive?.contentOrNull)
        assertNull(a.partial(2L, force = true))
    }

    @Test fun reasoningBlankBecomesNullAndAResetDropsIt() {
        val a = StreamAccum()
        a.appendReasoning("  ")
        assertNull(a.partial(0L)?.reasoning)
        a.clearReasoning()
        a.appendReasoning("think")
        assertEquals("think", a.partial(StreamAccum.INTERVAL_NS)?.reasoning)
        a.clearReasoning()
        a.appendContent("x")
        val msg = a.partial(StreamAccum.INTERVAL_NS * 2, force = true)
        assertEquals("x", msg?.content?.jsonPrimitive?.contentOrNull)
        assertNull(msg?.reasoning)
    }

    @Test fun fullTextSurvivesAfterSnapshots() {
        val a = StreamAccum()
        a.appendContent("one")
        a.appendContent(" two")
        a.partial(0L)
        assertEquals("one two", a.content())
        assertEquals("", a.reasoning())
    }
}
