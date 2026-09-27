package io.github.stardomains3.oxproxion

import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup

/**
 * Grows compact controls' hit areas to the 44dp contract without growing their glass. One
 * delegate on [host] routes a touch that lands in a control's padded rect (and on nothing
 * else) to that control. Rects are measured at touch down, so layout, scrolling and moving
 * pills never leave them stale; hidden or disabled controls are skipped.
 *
 * [host] must contain the padded rects, because touches outside it never reach it.
 */
object TouchTargets {

    fun expand(host: ViewGroup, vararg targets: View, minDp: Float = 44f) {
        host.touchDelegate = Delegate(host, targets.toList(), (minDp * host.resources.displayMetrics.density).toInt())
    }

    private class Delegate(
        private val host: ViewGroup,
        private val targets: List<View>,
        private val minPx: Int,
    ) : TouchDelegate(Rect(), host) {

        private val rects = List(targets.size) { Rect() }
        private val delegates = arrayOfNulls<TouchDelegate>(targets.size)
        private var active: TouchDelegate? = null

        private fun update() {
            targets.forEachIndexed { i, v ->
                val r = rects[i]
                r.set(0, 0, v.width, v.height)
                val mapped = v.width > 0 && runCatching { host.offsetDescendantRectToMyCoords(v, r) }.isSuccess
                if (!mapped) {
                    delegates[i] = null
                    return@forEachIndexed
                }
                r.inset(-((minPx - r.width()) / 2).coerceAtLeast(0), -((minPx - r.height()) / 2).coerceAtLeast(0))
                delegates[i] = TouchDelegate(Rect(r), v)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                active = null
                update()
                val x = event.x.toInt()
                val y = event.y.toInt()
                for (i in targets.indices) {
                    val v = targets[i]
                    val d = delegates[i] ?: continue
                    if (!v.isShown || !v.isEnabled || !rects[i].contains(x, y)) continue
                    active = d
                    return d.onTouchEvent(event)
                }
                return false
            }
            val d = active ?: return false
            val handled = d.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) active = null
            return handled
        }
    }
}
