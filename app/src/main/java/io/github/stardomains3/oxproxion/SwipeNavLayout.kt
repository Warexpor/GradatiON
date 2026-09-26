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
 *  - the finger must travel mostly sideways (|dx| > 2 x |dy|) past a large slop before the
 *    gesture is claimed, and must not have started as a vertical scroll;
 *  - a press held still for a long-press (selection handles, context menus) is never claimed;
 *  - the swipe commits only past [commitFraction] of the width, or a fast fling past half that.
 * While claimed, [Listener.onDrag] reports the offset so the UI can follow the finger.
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

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = ViewConfiguration.getLongPressTimeout().toLong()
    private val flingMin = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 12f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var state = IDLE
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val l = listener ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downTime = SystemClock.uptimeMillis()
                state = if (l.canStart(ev.x, ev.y)) UNDECIDED else REJECTED
                velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                if (state != UNDECIDED) return state == DRAGGING
                val dx = ev.x - downX
                val dy = ev.y - downY
                when {
                    abs(dy) > slop * 1.5f && abs(dy) >= abs(dx) -> state = REJECTED
                    SystemClock.uptimeMillis() - downTime > longPress && abs(dx) < slop * 3 -> state = REJECTED
                    abs(dx) > slop * 3 && abs(dx) > abs(dy) * 2f -> {
                        state = DRAGGING
                        downX = ev.x - (if (dx > 0) slop * 3f else -slop * 3f)
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
        if (state != DRAGGING) return super.onTouchEvent(event)
        val l = listener ?: return false
        velocity?.addMovement(event)
        val dx = event.x - downX
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> l.onDrag(dx)
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val far = abs(dx) > width * commitFraction
                val flung = abs(vx) > flingMin && abs(dx) > width * commitFraction / 2f && (vx > 0) == (dx > 0)
                if (far || flung) l.onCommit(if (dx > 0) 1 else -1) else l.onCancel()
                reset()
            }
            MotionEvent.ACTION_CANCEL -> { l.onCancel(); reset() }
        }
        return true
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
    }
}
