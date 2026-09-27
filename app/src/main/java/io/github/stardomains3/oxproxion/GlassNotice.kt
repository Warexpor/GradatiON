package io.github.stardomains3.oxproxion

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * A short glass pill that drops in under the top bar to say why something didn't happen
 * ("Add your OpenRouter key…"). Toasts are silenced app-wide ([AppToast]), so this is kept for
 * the few moments where silence would read as a broken button. Tap to dismiss; one at a time.
 */
object GlassNotice {

    private const val TAG = "glass_notice"
    private const val SHOW_MS = 3200L

    fun show(context: Context, text: CharSequence) {
        val activity = context.findActivity() ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        content.findViewWithTag<View>(TAG)?.let { content.removeView(it) }

        val d = context.resources.displayMetrics.density
        val pill = TextView(activity).apply {
            tag = TAG
            this.text = text
            setTextColor(ContextCompat.getColor(activity, R.color.xai_ink))
            textSize = 14f
            maxLines = 3
            gravity = Gravity.CENTER
            setPadding((18 * d).toInt(), (11 * d).toInt(), (18 * d).toInt(), (11 * d).toInt())
            background = GlassDrawable(activity, ContextCompat.getColor(activity, R.color.glass_sheet_tint), -1f)
            elevation = 8 * d
            isClickable = true
            setOnClickListener { dismiss(this) }
        }
        val top = ViewCompat.getRootWindowInsets(content)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: (24 * d).toInt()
        content.addView(pill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = top + (64 * d).toInt()
            leftMargin = (24 * d).toInt()
            rightMargin = (24 * d).toInt()
        })
        if (Motion.areAnimationsEnabled(activity)) {
            pill.alpha = 0f
            pill.translationY = -12 * d
            pill.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(Motion.iosOut).start()
        }
        pill.postDelayed({ dismiss(pill) }, SHOW_MS)
    }

    private fun dismiss(pill: View) {
        val parent = pill.parent as? ViewGroup ?: return
        if (!Motion.areAnimationsEnabled(pill.context)) { parent.removeView(pill); return }
        pill.animate().alpha(0f).translationY(-8 * pill.resources.displayMetrics.density)
            .setDuration(200).withEndAction { parent.removeView(pill) }.start()
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
