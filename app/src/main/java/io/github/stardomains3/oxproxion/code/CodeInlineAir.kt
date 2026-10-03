package io.github.stardomains3.oxproxion.code

import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import io.github.stardomains3.oxproxion.ChatMarkdown

/**
 * Air between an inline code pill and the words beside it in agent prose. The pill is painted
 * wider than its run, so a plain space leaves it touching the next word; the space on each side
 * is widened by that overhang. Word spacing keeps it an ordinary space, so lines still break
 * there and a space that ends a line takes no room. Idempotent, so a streaming run can be redone.
 */
internal object CodeInlineAir {

    fun apply(text: Spannable, extraPx: Float) {
        for (marker in text.getSpans(0, text.length, ChatMarkdown.InlineCodeMarker::class.java)) {
            val start = text.getSpanStart(marker)
            val end = text.getSpanEnd(marker)
            if (start > 0) widen(text, start - 1, extraPx)
            if (end in 0 until text.length) widen(text, end, extraPx)
        }
    }

    private fun widen(text: Spannable, at: Int, extraPx: Float) {
        if (text[at] != ' ') return
        if (text.getSpans(at, at + 1, WideSpace::class.java).isNotEmpty()) return
        text.setSpan(WideSpace(extraPx), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    class WideSpace(private val extraPx: Float) : MetricAffectingSpan() {
        override fun updateMeasureState(paint: TextPaint) { paint.wordSpacing += extraPx }
        override fun updateDrawState(paint: TextPaint) { paint.wordSpacing += extraPx }
    }
}
