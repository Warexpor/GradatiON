package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import androidx.core.content.res.ResourcesCompat

/**
 * The GradatiON wordmark: "Gradati" in Instrument Serif italic, "ON" in Plus Jakarta Sans
 * ExtraBold, set a touch smaller and tracked out, like an editorial masthead.
 */
object Wordmark {

    fun build(context: Context): CharSequence {
        val text = context.getString(R.string.grokion_wordmark)
        val split = text.indexOf("ON").takeIf { it > 0 } ?: return text
        val serif = ResourcesCompat.getFont(context, R.font.instrumentserif_italic) ?: return text
        val heavy = ResourcesCompat.getFont(context, R.font.jakarta_extrabold) ?: return text
        return SpannableString(text).apply {
            setSpan(Face(serif, 1f, 0f), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(Face(heavy, 0.62f, 0.06f), split, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
