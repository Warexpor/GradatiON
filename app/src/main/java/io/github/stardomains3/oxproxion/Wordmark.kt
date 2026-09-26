package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import androidx.core.content.res.ResourcesCompat

/**
 * The GradatiON wordmark: the whole name in Michroma, a wide machined sans, tracked out
 * slightly so it reads like an instrument-panel label.
 */
object Wordmark {

    fun build(context: Context): CharSequence {
        val text = context.getString(R.string.grokion_wordmark)
        val face = ResourcesCompat.getFont(context, R.font.michroma_regular) ?: return text
        return SpannableString(text).apply {
            setSpan(Face(face, 1f, 0.04f), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private class Face(
        private val typeface: Typeface,
        private val scale: Float,
        private val tracking: Float
    ) : MetricAffectingSpan() {
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        private fun apply(tp: TextPaint) {
            tp.typeface = typeface
            tp.textSize *= scale
            tp.letterSpacing = tracking
        }
    }
}
