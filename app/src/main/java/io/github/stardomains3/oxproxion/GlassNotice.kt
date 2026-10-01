package io.github.stardomains3.oxproxion

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * A short glass pill that drops in under the top bar to say why something didn't happen
 * ("Add your OpenRouter key…"). Toasts are not used anywhere, so this is kept for
 * the few moments where silence would read as a broken button. Tap to dismiss; one at a time.
 * With an action ("Open folder") the whole pill runs it on tap, so the target stays generous.
 * It is a polite live region and is announced, so TalkBack users hear why nothing happened.
 */
object GlassNotice {

    private const val TAG = "glass_notice"
    private const val BASE_MS = 2200L
    private const val PER_CHAR_MS = 55L
    private const val MIN_MS = 3200L
    private const val MAX_MS = 7000L
    private const val MIN_ACTION_MS = 5000L

    /** Long enough to read the words, longer still when there is something to tap. */
    internal fun durationMs(length: Int, hasAction: Boolean): Long {
        val ms = (BASE_MS + PER_CHAR_MS * length).coerceIn(MIN_MS, MAX_MS)
        return if (hasAction) maxOf(ms, MIN_ACTION_MS) else ms
    }

    fun show(context: Context, text: CharSequence, actionLabel: CharSequence? = null, onAction: (() -> Unit)? = null) {
        val activity = context.findActivity() ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        content.findViewWithTag<View>(TAG)?.let { content.removeView(it) }

        val d = context.resources.displayMetrics.density
        val pill = TextView(activity).apply {
            tag = TAG
            this.text = if (actionLabel != null && onAction != null) {
                SpannableStringBuilder(text).append("  ").append(actionLabel).apply {
                    setSpan(StyleSpan(Typeface.BOLD), length - actionLabel.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else text
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(ContextCompat.getColor(activity, R.color.xai_ink))
            textSize = 14f
            maxLines = 3
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            background = GlassDrawable(activity, ContextCompat.getColor(activity, R.color.glass_sheet_tint), -1f)
            elevation = 8 * d
            isClickable = true
            setOnClickListener {
                dismiss(this)
                if (onAction != null) onAction()
            }
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
        @Suppress("DEPRECATION")
        pill.announceForAccessibility(text)
        pill.postDelayed({ dismiss(pill) }, durationMs(text.length, onAction != null))
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
