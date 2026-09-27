package io.github.stardomains3.oxproxion

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView

/**
 * A soft highlight that sweeps across a label ("Thinking"), like the iOS "slide to unlock"
 * sheen. The base text keeps its muted color; a brighter band of [highlight] travels over it.
 */
object ShimmerText {

    fun start(view: TextView, highlight: Int, periodMs: Long = 1600L) {
        stop(view)
        if (!Motion.areAnimationsEnabled(view.context)) return
        val base = view.currentTextColor
        val bandWidth = view.resources.displayMetrics.density * 72f
        val shader = LinearGradient(
            0f, 0f, bandWidth, 0f,
            intArrayOf(base, highlight, base),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        val matrix = Matrix()
        view.paint.shader = shader
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val width = view.width.toFloat().coerceAtLeast(1f)
                val x = -bandWidth + (width + bandWidth * 2f) * (it.animatedValue as Float)
                matrix.setTranslate(x, 0f)
                shader.setLocalMatrix(matrix)
                view.invalidate()
            }
        }
        // Pause while the label is off the window (scrolled away, pooled, screen gone) so an
        // infinite sweep never ticks for a view nobody can see.
        val pauser = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = animator.resume()
            override fun onViewDetachedFromWindow(v: View) = animator.pause()
        }
        view.addOnAttachStateChangeListener(pauser)
        view.setTag(R.id.tag_shimmer_animator, animator)
        view.setTag(R.id.tag_shimmer_pauser, pauser)
        animator.start()
        if (!view.isAttachedToWindow) animator.pause()
    }

    /**
     * [start] on the next frame, once layout has given the label a width. A [stop] before then
     * wins, so a reply that lands in the same frame never leaves a sweep on a hidden row.
     */
    fun post(view: TextView, highlight: Int) {
        view.setTag(R.id.tag_shimmer_pending, true)
        view.post {
            if (view.getTag(R.id.tag_shimmer_pending) == true) start(view, highlight)
        }
    }

    fun stop(view: TextView) {
        view.setTag(R.id.tag_shimmer_pending, null)
        (view.getTag(R.id.tag_shimmer_pauser) as? View.OnAttachStateChangeListener)?.let {
            view.removeOnAttachStateChangeListener(it)
        }
        view.setTag(R.id.tag_shimmer_pauser, null)
        (view.getTag(R.id.tag_shimmer_animator) as? ValueAnimator)?.cancel()
        view.setTag(R.id.tag_shimmer_animator, null)
        if (view.paint.shader != null) {
            view.paint.shader = null
            view.invalidate()
        }
    }

    /** Highlight that reads on both themes: full ink over the muted label. */
    fun highlightFor(view: TextView): Int {
        val ink = view.context.getColor(R.color.xai_ink)
        return Color.argb(255, Color.red(ink), Color.green(ink), Color.blue(ink))
    }
}
