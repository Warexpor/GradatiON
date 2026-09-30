package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassNoticeTest {

    @Test fun aLongerMessageStaysLonger() {
        assertTrue(GlassNotice.durationMs(120, hasAction = false) > GlassNotice.durationMs(20, hasAction = false))
    }

    @Test fun neverFlashesAndNeverLingers() {
        assertEquals(3200L, GlassNotice.durationMs(0, hasAction = false))
        assertEquals(7000L, GlassNotice.durationMs(10_000, hasAction = false))
    }

    @Test fun anActionGetsTimeToBeTapped() {
        assertTrue(GlassNotice.durationMs(10, hasAction = true) >= 5000L)
    }
}
