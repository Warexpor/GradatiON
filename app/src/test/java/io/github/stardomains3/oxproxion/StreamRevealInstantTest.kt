package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** With animations off, a stream shows each update at once: no pacing, no frame loop to wait for. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class StreamRevealInstantTest {

    @Test fun instantStreamShowsEachUpdateAtOnceAndFinishesWithoutFrames() {
        val frames = mutableListOf<Pair<String, Int>>()
        var caughtUp = 0
        val reveal = StreamRevealAnimator({ text, from -> frames += text to from }, { caughtUp++ })
        reveal.instant = true

        reveal.setTarget("Hello")
        reveal.setTarget("Hello there, this is a longer update")

        assertEquals(listOf("Hello" to 0, "Hello there, this is a longer update" to 5), frames)
        assertEquals("Hello there, this is a longer update", reveal.displayed())

        reveal.finishFast()
        assertEquals(1, caughtUp)
    }

    @Test fun pacedStreamHoldsBackUntilAFrameRuns() {
        val frames = mutableListOf<String>()
        val reveal = StreamRevealAnimator({ text, _ -> frames += text }, {})
        reveal.setTarget("Hello there, this is a longer update")
        assertTrue("nothing shows before the first frame", frames.isEmpty())
        assertEquals("", reveal.displayed())
    }
}
