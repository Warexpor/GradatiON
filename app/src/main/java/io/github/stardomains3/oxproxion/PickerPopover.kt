package io.github.stardomains3.oxproxion

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import coil.load
import java.io.File

/**
 * Anchored picker overlay, shaped like Grok's mode popover: a liquid-glass card that grows out
 * of the control that opened it (above it when the control sits low, below it when high), with
 * rows of icon, title, subtitle and a check on the selected one. Lives inside the fragment's own
 * view tree so the glass can sample the transcript live; tap outside, Back or a pick closes it.
 */
class PickerPopover(
    private val host: FrameLayout,
    private val anchor: View,
    private val backdrop: GlassBackdropLayout?,
    /** Surface the card should clear and align with (e.g. the whole composer), else the anchor. */
    private val edge: View = anchor
) {
    data class Row(
        val title: CharSequence,
        val subtitle: CharSequence? = null,
        val iconRes: Int = 0,
        val avatar: File? = null,
        val monogram: String? = null,
        val selected: Boolean = false,
        val onClick: () -> Unit
    )

    private val context = host.context
    private val density = context.resources.displayMetrics.density
    private var scrim: View? = null
    private var card: GlassLinearLayout? = null
    private var backCallback: OnBackPressedCallback? = null
    var onDismiss: (() -> Unit)? = null
    val isShowing get() = card != null

    fun show(title: CharSequence?, rows: List<Row>, footer: List<Row> = emptyList()) {
        if (isShowing) return
        val scrimView = View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.popover_scrim))
            alpha = 0f
            isClickable = true
            setOnClickListener { dismiss() }
        }
        host.addView(scrimView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val inflater = LayoutInflater.from(context)
        val cardView = inflater.inflate(R.layout.popover_card, host, false) as GlassLinearLayout
        cardView.glass.source = backdrop
        cardView.isClickable = true
        val titleView = cardView.findViewById<TextView>(R.id.popoverTitle)
        titleView.text = title
        titleView.isVisible = !title.isNullOrEmpty()
        val rowsBox = cardView.findViewById<ViewGroup>(R.id.popoverRows)
        val footerBox = cardView.findViewById<ViewGroup>(R.id.popoverFooter)
        cardView.findViewById<View>(R.id.popoverDivider).isVisible = footer.isNotEmpty() && rows.isNotEmpty()
        val rowViews = ArrayList<View>()
        var selectedView: View? = null
        rows.forEach { r -> bindRow(inflater, rowsBox, r).also { rowViews += it; if (r.selected) selectedView = it } }
        footer.forEach { r -> rowViews += bindRow(inflater, footerBox, r) }

        // Geometry: card hugs the anchor's leading edge and opens toward the roomier side.
        val hostLoc = IntArray(2).also { host.getLocationInWindow(it) }
        val anchorLoc = IntArray(2).also { anchor.getLocationInWindow(it) }
        val edgeLoc = IntArray(2).also { edge.getLocationInWindow(it) }
        val ax = anchorLoc[0] - hostLoc[0]
        val ey = edgeLoc[1] - hostLoc[1]
        val ex = edgeLoc[0] - hostLoc[0]
        val gutter = (12 * density).toInt()
        val width = minOf(host.width - 2 * gutter, (340 * density).toInt())
        val above = ey + edge.height / 2 > host.height / 2
        val gap = (8 * density).toInt()
        val topInset = ViewCompat.getRootWindowInsets(host)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        val room = if (above) ey - gap - topInset - gutter - (56 * density).toInt() else host.height - (ey + edge.height + gap) - gutter
        val maxHeight = room.coerceAtMost((560 * density).toInt()).coerceAtLeast((160 * density).toInt())
        val left = (if (edge !== anchor) ex else ax - (8 * density).toInt()).coerceIn(gutter, host.width - width - gutter)
        val ay = ey

        val lp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.START or if (above) Gravity.BOTTOM else Gravity.TOP
        lp.leftMargin = left
        if (above) lp.bottomMargin = host.height - ay + gap else lp.topMargin = ay + edge.height + gap
        cardView.layoutParams = lp
        // Cap the list height; the footer stays pinned.
        cardView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        if (cardView.measuredHeight > maxHeight) {
            val scroll = cardView.findViewById<NestedScrollView>(R.id.popoverScroll)
            val over = cardView.measuredHeight - maxHeight
            scroll.layoutParams = scroll.layoutParams.apply { height = (scroll.measuredHeight - over).coerceAtLeast((120 * density).toInt()) }
            selectedView?.let { sel -> scroll.post { scroll.scrollTo(0, (sel.top - (scroll.height - sel.height) / 2).coerceAtLeast(0)) } }
        }
        host.addView(cardView)
        scrim = scrimView
        card = cardView

        // Grow out of the anchor: pivot at the anchor, spring scale + lift, rows ripple in.
        val animate = Motion.areAnimationsEnabled(context)
        cardView.pivotX = (ax - left + anchor.width / 2f).coerceIn(0f, width.toFloat())
        cardView.pivotY = if (above) cardView.measuredHeight.coerceAtMost(maxHeight).toFloat() else 0f
        if (animate) {
            cardView.alpha = 0f
            cardView.scaleX = 0.86f
            cardView.scaleY = 0.86f
            cardView.translationY = (if (above) 14f else -14f) * density
            android.animation.AnimatorSet().apply {
                playTogether(
                    android.animation.ObjectAnimator.ofFloat(cardView, View.ALPHA, 1f).setDuration(160).apply { interpolator = Motion.easeOut },
                    android.animation.ObjectAnimator.ofPropertyValuesHolder(
                        cardView,
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f),
                        android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f)
                    ).setDuration(460).apply { interpolator = Motion.spring }
                )
                start()
            }
            rowViews.forEachIndexed { i, v ->
                v.alpha = 0f
                v.translationY = (if (above) 8f else -8f) * density
                v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(40L + 16L * minOf(i, 10)).setDuration(260).setInterpolator(Motion.iosOut).start()
            }
            scrimView.animate().alpha(1f).setDuration(180).start()
        } else {
            scrimView.alpha = 1f
        }

        (context as? ComponentActivity)?.let { act ->
            backCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = dismiss()
            }.also { act.onBackPressedDispatcher.addCallback(it) }
        }
    }

    private fun bindRow(inflater: LayoutInflater, parent: ViewGroup, r: Row): View {
        val v = inflater.inflate(R.layout.item_popover_row, parent, false)
        v.findViewById<TextView>(R.id.popoverRowTitle).text = r.title
        v.findViewById<TextView>(R.id.popoverRowSubtitle).apply { text = r.subtitle; isVisible = !r.subtitle.isNullOrEmpty() }
        val icon = v.findViewById<ImageView>(R.id.popoverRowIcon)
        val mono = v.findViewById<TextView>(R.id.popoverRowMonogram)
        val avatar = v.findViewById<ImageView>(R.id.popoverRowAvatar)
        when {
            r.avatar != null && r.avatar.exists() -> {
                icon.isVisible = false
                avatar.isVisible = true
                avatar.load(r.avatar) { crossfade(false) }
            }
            r.monogram != null -> {
                icon.isVisible = false
                mono.isVisible = true
                mono.text = r.monogram
            }
            r.iconRes != 0 -> icon.setImageResource(r.iconRes)
            else -> icon.isVisible = false
        }
        v.isSelected = r.selected
        v.findViewById<View>(R.id.popoverRowCheck).isVisible = r.selected
        v.setOnClickListener {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            dismiss()
            r.onClick()
        }
        parent.addView(v)
        return v
    }

    fun dismiss(animated: Boolean = true) {
        val c = card ?: return
        val s = scrim
        card = null
        scrim = null
        backCallback?.remove()
        backCallback = null
        val remove = Runnable {
            host.removeView(c)
            if (s != null) host.removeView(s)
        }
        if (animated && Motion.areAnimationsEnabled(context)) {
            c.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setStartDelay(0)
                .setDuration(150).setInterpolator(Motion.iosIn).withEndAction(remove).start()
            s?.animate()?.alpha(0f)?.setDuration(150)?.start()
        } else {
            remove.run()
        }
        onDismiss?.invoke()
    }
}
