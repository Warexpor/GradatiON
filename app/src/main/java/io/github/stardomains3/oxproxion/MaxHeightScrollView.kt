package io.github.stardomains3.oxproxion

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** A ScrollView that grows with its content up to [maxHeightPx] (default 260dp), then scrolls. */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    var maxHeightPx: Int = (260 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }
}
