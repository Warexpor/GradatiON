package io.github.stardomains3.oxproxion

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * FrameLayout that recognises deliberate, wide horizontal swipes anywhere on it (not just at an
 * edge) and hands them to [listener]. It is strict on purpose so it never fights scrolling,
 * text selection or taps:
 *  - the finger must travel mostly sideways (|dx| > 1.5 x |dy|) past 1.5x the touch slop
 *    before the gesture is claimed, and must not have started as a vertical scroll;
 *  - a press held still for a long-press (selection handles, context menus) is never claimed;
 *  - the swipe commits when the release, projected ahead by its velocity, lands past
 *    [commitFraction] of the width, having travelled at least a third of that; a flick back
 *    the other way always cancels.
 * While claimed, [Listener.onDrag] reports the offset so the UI can follow the finger.
 * Distances are measured on screen, not in this view's coordinates: the history drawer moves
 * itself under the finger, and local coordinates would shift with it every step.
 */
class SwipeNavLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Listener {
        /** Whether a swipe may start at this point (in this view's coordinates). */
        fun canStart(x: Float, y: Float): Boolean = true
        fun onDrag(dx: Float) {}
        /** [direction] is +1 when the finger moved right, -1 when it moved left. */
        fun onCommit(direction: Int)
        fun onCancel() {}
    }

    var listener: Listener? = null
    var commitFraction = 0.34f
    /**
     * Horizontal finger velocity (px/s, + = rightward) at the release that led to the current
     * [Listener.onCommit] or [Listener.onCancel], so the settle can carry it on.
     */
    var releaseVelocity = 0f
        private set

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = ViewConfiguration.getLongPressTimeout().toLong()
    private val flingBack = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 6f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var state = IDLE
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val l = listener ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; downTime = SystemClock.uptimeMillis()
                state = if (l.canStart(ev.x, ev.y)) UNDECIDED else REJECTED
                velocity?.recycle(); velocity = VelocityTracker.obtain().also { track(it, ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.let { track(it, ev) }
                if (state != UNDECIDED) return state == DRAGGING
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                when {
                    abs(dy) > slop * 1.5f && abs(dy) >= abs(dx) -> state = REJECTED
                    SystemClock.uptimeMillis() - downTime > longPress && abs(dx) < slop * 1.5f -> state = REJECTED
                    abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.5f -> {
                        state = DRAGGING
                        downX = ev.rawX - (if (dx > 0) slop * 1.5f else -slop * 1.5f)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        return false
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // No child took the DOWN (an empty history list, blank space), so the intercept path
        // never sees the MOVEs. Make the call here instead.
        if (state == UNDECIDED && event.actionMasked == MotionEvent.ACTION_MOVE && onInterceptTouchEvent(event)) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.onTouchEvent(cancel)
            cancel.recycle()
            listener?.onDrag(event.rawX - downX)
            return true
        }
        if (state != DRAGGING) {
            val handled = super.onTouchEvent(event)
            val undecided = state == UNDECIDED
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) reset()
            // Keep the gesture while it may still become a swipe.
            return handled || undecided
        }
        val l = listener ?: return false
        velocity?.let { track(it, event) }
        val dx = event.rawX - downX
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> l.onDrag(dx)
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                releaseVelocity = vx
                // Judge where the page is headed, not only where it is: a flick carries it on,
                // and a flick back the other way means "never mind", however far it got.
                val flungBack = abs(vx) > flingBack && (vx > 0) != (dx > 0)
                // A flick still has to travel a bit, so a quick nudge never navigates.
                val projected = dx + vx * PROJECTION_S
                val commit = !flungBack && (projected > 0) == (dx > 0) &&
                    abs(projected) > width * commitFraction && abs(dx) > width * commitFraction / 3f
                if (commit) l.onCommit(if (dx > 0) 1 else -1) else l.onCancel()
                reset()
            }
            MotionEvent.ACTION_CANCEL -> { releaseVelocity = 0f; l.onCancel(); reset() }
        }
        return true
    }

    /** Feeds [e] to [tracker] in screen coordinates, for the same reason as the distances. */
    private fun track(tracker: VelocityTracker, e: MotionEvent) {
        val screen = MotionEvent.obtain(e)
        screen.setLocation(e.rawX, e.rawY)
        tracker.addMovement(screen)
        screen.recycle()
    }

    private fun reset() {
        state = IDLE
        velocity?.recycle(); velocity = null
    }

    private companion object {
        const val IDLE = 0
        const val UNDECIDED = 1
        const val REJECTED = 2
        const val DRAGGING = 3
        /** How far ahead a release is projected: roughly where a flick would coast to. */
        const val PROJECTION_S = 0.16f
    }
}
