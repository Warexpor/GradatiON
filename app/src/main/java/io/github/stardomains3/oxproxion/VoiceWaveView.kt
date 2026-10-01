package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The listening strip in the composer: thin gray bars gliding left, each one the mic level at
 * its moment, fading out toward the older edge. The newest bar follows the live level every
 * frame and the strip slides continuously between bars, so it answers the voice at once instead
 * of stepping. While a recording is being transcribed the bars settle into a slow travelling
 * ripple. Up to 60fps while listening, ~15fps for the ripple, nothing while hidden.
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
    private val pitch = barW + gap
    private val minH = 3f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.xai_ink)
    }

    private var levels = FloatArray(0)
    private var target = 0f
    private var smooth = 0f
    private var phase = 0f
    /** Time since the strip last moved one bar left; drives the sub-bar glide. */
    private var sinceShiftMs = 0f
    private var lastFrame = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val reduced = !Motion.areAnimationsEnabled(context)
            val dt = if (lastFrame == 0L) FRAME_LISTEN_MS.toFloat() else (now - lastFrame).toFloat().coerceIn(8f, 160f)
            lastFrame = now
            // With animations off the level still has to show (it is the feedback that the mic
            // hears you), but as one bar per step, without the glide; the ripple just holds.
            if (!(reduced && mode == Mode.WORKING)) {
                step(dt, advancePhase = !reduced)
                invalidate()
            }
            postOnAnimationDelayed(
                this,
                when {
                    reduced -> FRAME_REDUCED_MS
                    mode == Mode.LISTENING -> FRAME_LISTEN_MS
                    else -> FRAME_WORK_MS
                }
            )
        }
    }

    /** Latest mic level, 0..1. Cheap; the view samples it on its own clock. */
    fun setLevel(level: Float) {
        // Speech sits low on the recognizer's scale; lift it so ordinary talk reads.
        target = level.coerceIn(0f, 1f).pow(0.7f)
    }

    fun reset() {
        levels.fill(0f)
        target = 0f
        smooth = 0f
        sinceShiftMs = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // One spare slot: the oldest bar slides out past the left edge.
        val count = max(1, ((w + gap) / pitch).toInt() + 1)
        if (count != levels.size) levels = FloatArray(count)
    }

    private fun step(dtMs: Float, advancePhase: Boolean = true) {
        // Per-frame follow, scaled to the frame time: quick to rise, a touch slower to fall so
        // words read as shapes rather than flicker.
        val k = dtMs / FRAME_LISTEN_MS
        val rate = if (target > smooth) ATTACK else RELEASE
        smooth += (target - smooth) * (1f - (1f - rate).pow(k))
        if (advancePhase) phase = (phase + 0.35f * dtMs / FRAME_WORK_MS) % (2f * PI.toFloat())
        if (levels.isEmpty()) return
        sinceShiftMs += dtMs
        while (sinceShiftMs >= SHIFT_MS) {
            System.arraycopy(levels, 1, levels, 0, levels.size - 1)
            sinceShiftMs -= SHIFT_MS
        }
        levels[levels.size - 1] = smooth
    }

    override fun onDraw(canvas: Canvas) {
        val n = levels.size
        if (n == 0) return
        val cy = height / 2f
        val maxH = height.toFloat()
        val glide = if (mode == Mode.LISTENING && Motion.areAnimationsEnabled(context)) {
            (sinceShiftMs / SHIFT_MS).coerceIn(0f, 1f) * pitch
        } else 0f
        // The newest bar sits at the right edge, then everything glides left until the next shift.
        var x = width - n * pitch + gap - glide
        val r = barW / 2f
        for (i in 0 until n) {
            val age = i / (n - 1).coerceAtLeast(1).toFloat() // 0 oldest .. 1 newest
            val v = when (mode) {
                Mode.LISTENING -> levels[i]
                Mode.WORKING -> 0.18f + 0.14f * (1f + sin(phase - i * 0.45f)) / 2f
            }
            val h = minH + (maxH - minH) * v
            paint.alpha = (255 * (0.22f + 0.78f * age)).toInt()
            if (x + barW > 0f && x < width) canvas.drawRoundRect(x, cy - h / 2f, x + barW, cy + h / 2f, r, r, paint)
            x += pitch
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

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // The animator-scale setting is cached; look again whenever the user comes back.
        if (hasWindowFocus) Motion.refreshAnimations(context)
    }

    override fun onDetachedFromWindow() {
        setRunning(false)
        super.onDetachedFromWindow()
    }

    private fun setRunning(on: Boolean) {
        if (on == running) return
        running = on
        lastFrame = 0L
        removeCallbacks(tick)
        if (on) postOnAnimation(tick)
    }

    companion object {
        /** Up to 60fps while listening: the strip glides, and at 30fps the glide visibly steps. */
        private const val FRAME_LISTEN_MS = 16L
        /** ~15fps for the transcribing ripple, which only needs to look alive. */
        private const val FRAME_WORK_MS = 66L
        /** Animations off: one step per shift, no glide. */
        private const val FRAME_REDUCED_MS = 120L
        /** The strip moves one bar every 120ms (about 8 bars a second), an unhurried drift. */
        private const val SHIFT_MS = 120f
        /** Per-16ms follow rates: rises within ~100ms, eases down over ~250ms. */
        private const val ATTACK = 0.22f
        private const val RELEASE = 0.09f
    }
}
