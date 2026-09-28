package io.github.stardomains3.oxproxion

import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Swipes must work where no child takes the touch (the empty history list is hidden, so the
 * drawer itself is the only target). This used to depend on whether Room's empty result had
 * landed before the swipe, which made the drawer-close screenshot test order-dependent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class SwipeNavLayoutTest {

    private companion object { var gestures = 0 }

    private fun build(clickable: Boolean, withChild: Boolean): Pair<SwipeNavLayout, MutableList<Int>> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val commits = mutableListOf<Int>()
        val l = SwipeNavLayout(ctx).apply {
            isClickable = clickable
            if (withChild) addView(View(ctx).apply { isClickable = true })
            listener = object : SwipeNavLayout.Listener {
                override fun onCommit(direction: Int) { commits += direction }
            }
            measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, 2000)
        }
        return l to commits
    }

    /**
     * Plays [points] (ms since down, x) as one gesture. The clock is advanced with each event
     * so Robolectric's velocity tracker sees the same timeline as the events.
     */
    private fun gesture(v: View, points: List<Pair<Long, Float>>) {
        // Robolectric's velocity tracker keeps state across tests that reuse the same event
        // times (every test's clock starts at the same value); start each gesture later.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(10L * ++gestures))
        val t0 = android.os.SystemClock.uptimeMillis()
        var last = 0L
        points.forEachIndexed { i, (dt, x) ->
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(dt - last))
            last = dt
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            v.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + dt, action, x, 900f, 0))
        }
    }

    private fun swipe(v: View, fromX: Float, toX: Float) = gesture(v,
        listOf(0L to fromX) + (1..8).map { k -> 20L * k to fromX + (toX - fromX) * k / 8f } + listOf(200L to toX))

    @Test fun leftSwipeCommitsOnEmptyClickableLayout() {
        val (l, commits) = build(clickable = true, withChild = false)
        swipe(l, 850f, 150f)
        assertEquals(listOf(-1), commits)
    }

    @Test fun rightSwipeCommitsOnEmptyPlainLayout() {
        val (l, commits) = build(clickable = false, withChild = false)
        swipe(l, 150f, 850f)
        assertEquals(listOf(1), commits)
    }

    @Test fun swipeCommitsOverClickableChild() {
        val (l, commits) = build(clickable = true, withChild = true)
        swipe(l, 850f, 150f)
        assertEquals(listOf(-1), commits)
    }

    @Test fun shortNudgeDoesNotCommit() {
        val (l, commits) = build(clickable = true, withChild = false)
        swipe(l, 500f, 620f)
        assertEquals(emptyList<Int>(), commits)
    }

    /** Far across, then flicked back toward the start: the user changed their mind. */
    @Test fun flickBackCancelsEvenWhenFar() {
        val (l, commits) = build(clickable = true, withChild = false)
        // Out to 950, then back to 550: still past the commit line when let go.
        gesture(l, listOf(0L to 50f) + (1..8).map { k -> 20L * k to 50f + 900f * k / 8f } +
            (1..20).map { k -> 160L + 10L * k to 950f - 20f * k } + listOf(365L to 550f))
        assertEquals("v=${l.releaseVelocity}", emptyList<Int>(), commits)
    }

    /** A short but quick flick is projected ahead past the line, so it commits. */
    @Test fun shortFastFlickCommits() {
        val (l, commits) = build(clickable = true, withChild = false)
        gesture(l, listOf(0L to 700f) + (1..6).map { k -> 8L * k to 700f - 250f * k / 6f } + listOf(50L to 450f))
        assertEquals("v=${l.releaseVelocity}", listOf(-1), commits)
    }
}
