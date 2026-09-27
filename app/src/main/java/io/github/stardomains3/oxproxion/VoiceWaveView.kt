package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * The listening strip in the composer: thin gray bars that scroll left, each one the mic level
 * at its moment, fading out toward the older edge. While a recording is being transcribed the
 * bars settle into a slow travelling ripple instead. Redraws at ~15fps and only while shown.
 */
class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { LISTENING, WORKING }

    var mode: Mode = Mode.LISTENING
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val barW = 3f * density
    private val gap = 3f * density
    private val minH = 3f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.xai_ink)
    }

    private var levels = FloatArray(0)
    private var target = 0f
    private var smooth = 0f
    private var phase = 0f
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            invalidate()
            postOnAnimationDelayed(this, FRAME_MS)
        }
    }

    /** Latest mic level, 0..1. Cheap; the view samples it on its own clock. */
    fun setLevel(level: Float) {
        target = level.coerceIn(0f, 1f)
    }

    fun reset() {
        levels.fill(0f)
        target = 0f
        smooth = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val count = max(1, ((w + gap) / (barW + gap)).toInt())
        if (count != levels.size) levels = FloatArray(count)
    }

    private fun step() {
        // Fast attack, slower release so words read as shapes rather than flicker.
        smooth += (target - smooth) * if (target > smooth) 0.7f else 0.3f
        if (levels.isEmpty()) return
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = smooth
        phase = (phase + 0.35f) % (2f * PI.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val n = levels.size
        if (n == 0) return
        val cy = height / 2f
        val maxH = height.toFloat()
        val total = n * barW + (n - 1) * gap
        var x = (width - total) / 2f
        val r = barW / 2f
        for (i in 0 until n) {
            val age = i / (n - 1).coerceAtLeast(1).toFloat() // 0 oldest .. 1 newest
            val v = when (mode) {
                Mode.LISTENING -> levels[i]
                Mode.WORKING -> 0.18f + 0.14f * (1f + sin(phase - i * 0.45f)) / 2f
            }
            val h = minH + (maxH - minH) * v
            paint.alpha = (255 * (0.22f + 0.78f * age)).toInt()
            canvas.drawRoundRect(x, cy - h / 2f, x + barW, cy + h / 2f, r, r, paint)
            x += barW + gap
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        setRunning(isVisible && isAttachedToWindow)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setRunning(isShown)
    }

    override fun onDetachedFromWindow() {
        setRunning(false)
        super.onDetachedFromWindow()
    }

    private fun setRunning(on: Boolean) {
        if (on == running) return
        running = on
        removeCallbacks(tick)
        if (on) postOnAnimation(tick)
    }

    companion object {
        /** ~15fps: plenty for a level meter, cheap on the battery. */
        private const val FRAME_MS = 66L
    }
}
