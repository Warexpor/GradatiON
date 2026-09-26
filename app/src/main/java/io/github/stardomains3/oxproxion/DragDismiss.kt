package io.github.stardomains3.oxproxion

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Pull-down-to-dismiss for floating panels that show a grabber (iOS sheet behaviour): the panel
 * follows the finger with rubber-banding upward, and a release past a third of its height or a
 * downward fling dismisses; anything less springs back. The host view forwards
 * intercept/touch events so buttons inside the panel keep working until a vertical drag starts.
 */
class DragDismiss(
    private val panel: View,
    private val onProgress: (Float) -> Unit = {},
    private val onDismiss: () -> Unit
) {
    private val slop = ViewConfiguration.get(panel.context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(panel.context).scaledMinimumFlingVelocity * 4
    private var downY = 0f
    private var downX = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    fun onIntercept(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; dragging = false
                velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dy = ev.rawY - downY
                val dx = ev.rawX - downX
                if (dy > slop && dy > abs(dx) * 1.3f) {
                    dragging = true
                    downY = ev.rawY
                    panel.animate().cancel()
                    panel.parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        return false
    }

    /** Returns true when the event was consumed as part of a drag. */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Touch landed on the panel background (e.g. the grabber): start tracking right away.
                downX = ev.rawX; downY = ev.rawY; dragging = true
                panel.animate().cancel()
                velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                velocity?.addMovement(ev)
                val dy = ev.rawY - downY
                // Rubber-band upward, free downward.
                panel.translationY = if (dy >= 0) dy else -rubber(-dy)
                onProgress((panel.translationY / panel.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                velocity?.addMovement(ev)
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                val ty = panel.translationY
                dragging = false
                release()
                if (ev.actionMasked == MotionEvent.ACTION_UP && (ty > panel.height / 3f || (vy > minFling && ty > 0))) {
                    val remaining = (panel.height + panel.paddingBottom - ty).coerceAtLeast(0f)
                    val ms = if (vy > 0) (remaining / vy * 1000f).toLong().coerceIn(90L, 240L) else 200L
                    panel.animate().translationY(ty + remaining).alpha(0.4f).setDuration(ms)
                        .setInterpolator(Motion.easeOut).withEndAction {
                            onDismiss()
                        }.start()
                    onProgress(1f)
                } else {
                    panel.animate().translationY(0f).setDuration(420).setInterpolator(Motion.spring).start()
                    onProgress(0f)
                }
                return true
            }
        }
        return false
    }

    private fun release() {
        velocity?.recycle(); velocity = null
    }

    private fun rubber(x: Float): Float {
        val d = panel.resources.displayMetrics.density * 120f
        return d * (1f - 1f / (x / d + 1f))
    }
}
