package io.github.stardomains3.oxproxion.code

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * An approval card's answers. Side by side when their labels fit, sharing the spare width so the
 * row runs edge to edge on the content column; stacked full width when they do not, so a long
 * label from the agent never clips or hides behind a sideways scroll.
 */
class CodeChoiceRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val gap = (10 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val room = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val loose = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var natural = 0
        var shown = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(loose, loose)
            natural += child.measuredWidth + if (shown > 0) gap else 0
            shown++
        }
        val stack = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && natural > room
        orientation = if (stack) VERTICAL else HORIZONTAL
        shown = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            (child.layoutParams as LayoutParams).apply {
                width = if (stack) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
                weight = if (stack) 0f else 1f
                marginStart = if (!stack && shown > 0) gap else 0
                topMargin = if (stack && shown > 0) gap else 0
                // A start margin only reaches left/right on resolution, which a laid-out child skips.
                resolveLayoutDirection(child.layoutDirection)
            }
            shown++
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
