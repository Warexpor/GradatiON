package io.github.stardomains3.oxproxion

import android.text.Layout
import android.text.Spanned
import android.text.style.ClickableSpan
import android.widget.TextView

/**
 * A tap on a message you sent opens the action row. A tap on a link has to open the
 * link and leave that row alone: the text view is clickable, so both used to happen.
 * The hit test matches [android.text.method.LinkMovementMethod], which is what actually
 * opens the link, including a tap in the empty tail of a short line.
 */
object MessageTap {

    fun hitsLink(view: TextView, x: Float, y: Float): Boolean {
        val layout = view.layout ?: return false
        val text = view.text ?: return false
        val offset = offsetAt(
            layout, x, y,
            view.totalPaddingLeft, view.totalPaddingTop,
            view.scrollX, view.scrollY,
        )
        return !togglesActions(text, offset)
    }

    /** False when [offset] sits on a link or a code header. Anything else may toggle the row. */
    fun togglesActions(text: CharSequence, offset: Int): Boolean {
        val spanned = text as? Spanned ?: return true
        if (offset < 0 || offset > spanned.length) return true
        return spanned.getSpans(offset, offset, ClickableSpan::class.java).isEmpty()
    }

    fun offsetAt(
        layout: Layout,
        x: Float,
        y: Float,
        paddingLeft: Int,
        paddingTop: Int,
        scrollX: Int,
        scrollY: Int,
    ): Int {
        val localX = x.toInt() - paddingLeft + scrollX
        val localY = y.toInt() - paddingTop + scrollY
        val line = layout.getLineForVertical(localY)
        return layout.getOffsetForHorizontal(line, localX.toFloat())
    }
}
