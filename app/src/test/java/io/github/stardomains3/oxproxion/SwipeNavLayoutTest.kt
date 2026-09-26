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

    private fun swipe(v: View, fromX: Float, toX: Float) {
        val t0 = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, dt: Long) = MotionEvent.obtain(t0, t0 + dt, action, x, 900f, 0)
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, fromX, 0))
        for (k in 1..8) v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * k / 8f, 20L * k))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, toX, 200))
    }

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
}
