package io.github.stardomains3.oxproxion

import android.view.Window
import android.view.WindowManager

/**
 * Dialog windows frost what's behind them (cross-window blur, Android 12+) with a light dim,
 * and enter/exit like iOS alerts. When the device or battery saver disables blur, a
 * stronger dim keeps the dialog legible.
 */
object GlassDialogs {
    fun frost(window: Window, animate: Boolean = true) {
        val wm = window.context.getSystemService(WindowManager::class.java)
        val blur = wm?.isCrossWindowBlurEnabled == true
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (blur) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also {
                it.blurBehindRadius = (22 * window.context.resources.displayMetrics.density).toInt()
            }
            window.setDimAmount(DIM_WITH_BLUR)
        } else {
            window.setDimAmount(DIM_NO_BLUR)
        }
        if (animate) window.setWindowAnimations(R.style.Animation_Gradation_Dialog)
    }

    private const val DIM_WITH_BLUR = 0.28f
    private const val DIM_NO_BLUR = 0.5f
}
