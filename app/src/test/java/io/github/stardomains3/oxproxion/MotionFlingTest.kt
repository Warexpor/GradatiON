package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The settle spring must pick up the finger's speed and land exactly, without a jump. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class MotionFlingTest {

    /** Speed at the start, in px/s, for a move of [distance] px. */
    private fun startSpeed(f: Motion.Fling, distance: Float): Float {
        val dt = 0.002f
        return f.getInterpolation(dt) * distance / (dt * f.duration / 1000f)
    }

    @Test fun carriesTheReleaseVelocity() {
        val f = Motion.Fling(distance = 800f, velocity = 2000f, response = 0.38f)
        assertEquals(2000f, startSpeed(f, 800f), 120f)
    }

    @Test fun startsFromRestWithoutVelocity() {
        val f = Motion.Fling(distance = 800f, velocity = 0f)
        assertTrue(startSpeed(f, 800f) < 150f)
    }

    @Test fun landsWithoutAVisibleJump() {
        for (v in listOf(-3000f, 0f, 1500f, 6000f)) {
            val f = Motion.Fling(distance = 1080f, velocity = v, response = 0.38f)
            assertEquals(0f, f.getInterpolation(0f), 1e-4f)
            assertEquals(1f, f.getInterpolation(1f), 0f)
            // One frame before the end the page is already within half a pixel,
            // so clamping to the target does not hitch.
            val frame = 16f / f.duration
            val leftPx = (1f - f.getInterpolation(1f - frame)) * 1080f
            assertTrue("v=$v left=${leftPx}px", leftPx < 0.5f)
        }
    }

    @Test fun pagesNeverOvershoot() {
        val f = Motion.Fling(distance = 1080f, velocity = 9000f, response = 0.38f)
        var t = 0f
        while (t <= 1f) { assertTrue("t=$t", f.getInterpolation(t) <= 1.0005f); t += 0.01f }
    }

    @Test fun aHardFlickLandsSooner() {
        val slow = Motion.Fling(distance = 900f, velocity = 0f, response = 0.38f)
        val fast = Motion.Fling(distance = 900f, velocity = 4000f, response = 0.38f)
        assertTrue("${fast.duration} < ${slow.duration}", fast.duration < slow.duration)
    }
}
