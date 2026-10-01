package io.github.stardomains3.oxproxion

import android.graphics.Color
import android.os.SystemClock
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance

/**
 * Soft reveal for freshly streamed text: each newly revealed run of words eases from
 * transparent to its full color on its own clock, so the streaming edge reads as a gentle
 * wash of ink rather than characters popping in.
 */
class StreamFadeSpan(
    private val startMs: Long = SystemClock.uptimeMillis(),
    private val durationMs: Long = DURATION_MS
) : CharacterStyle(), UpdateAppearance {

    fun isDone(now: Long = SystemClock.uptimeMillis()): Boolean =
        now - startMs >= durationMs

    override fun updateDrawState(tp: TextPaint) {
        val t = ((SystemClock.uptimeMillis() - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
        val inv = 1f - t
        val eased = 1f - inv * inv * inv // ease-out cubic
        val base = tp.color
        val a = (Color.alpha(base) * eased).toInt().coerceIn(0, 255)
        tp.color = Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
    }

    companion object {
        const val DURATION_MS = 220L
    }
}
