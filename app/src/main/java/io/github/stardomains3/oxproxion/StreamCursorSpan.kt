package io.github.stardomains3.oxproxion

import android.graphics.Color
import android.os.SystemClock
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.UpdateAppearance
import kotlin.math.cos

/** Small breathing dot at the streaming edge: shows the reply is alive between tokens. */
class StreamCursorSpan(
    private val color: Int,
    private val periodMs: Long = 1400L
) : MetricAffectingSpan(), UpdateAppearance {

    override fun updateMeasureState(tp: TextPaint) {
        tp.textSize *= SIZE
    }

    override fun updateDrawState(tp: TextPaint) {
        tp.textSize *= SIZE
        val phase = (SystemClock.uptimeMillis() % periodMs).toFloat() / periodMs
        val wave = 0.5f - 0.5f * cos(phase * 2f * Math.PI.toFloat())
        val alpha = (Color.alpha(color) * (0.25f + 0.75f * wave)).toInt().coerceIn(0, 255)
        tp.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        tp.bgColor = Color.TRANSPARENT
    }

    companion object {
        /** Non-breaking space keeps the dot glued to the last word. */
        const val GLYPH = " ●"
        private const val SIZE = 0.72f
    }
}
