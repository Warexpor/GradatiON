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
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner

/**
 * The ⋮ menu on a message. A compact context menu in the iOS 26 mould: one label per row with
 * its icon on the trailing side, hairlines between rows, and destructive rows set apart in
 * their own group at the bottom. It is deliberately not [PickerPopover] (the composer's picker,
 * icon-first with subtitles), so a message's menu never reads as a second composer bar.
 *
 * The card opens from the ⋮: its leading edge lines up with the control when there is room,
 * otherwise its trailing edge does, below the control unless the bottom of the screen is closer.
 */
class MessageMenu(
    private val host: FrameLayout,
    private val anchor: View,
    private val backdrop: GlassBackdropLayout?
) {
    class Item(
        val label: CharSequence,
        @DrawableRes val icon: Int,
        /** Set apart at the bottom, in the delete red. */
        val destructive: Boolean = false,
        val onClick: () -> Unit
    )

    private val context = host.context
    private val density = context.resources.displayMetrics.density
    private var scrim: View? = null
    private var card: GlassLinearLayout? = null
    private var backCallback: OnBackPressedCallback? = null
    var onDismiss: (() -> Unit)? = null
    val isShowing get() = card != null

    /** True when this menu is open on [view]: a second tap on the same control folds it. */
    fun isOpenOn(view: View) = isShowing && anchor === view

    fun show(items: List<Item>, lifecycleOwner: LifecycleOwner? = null, animated: Boolean = true) {
        if (isShowing || items.isEmpty()) return
        val scrimView = View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.popover_scrim))
            alpha = 0f
            isClickable = true
            setTag(R.id.tag_popover_layer, true)
            setOnClickListener { dismiss() }
        }
        host.addView(scrimView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val inflater = LayoutInflater.from(context)
        val cardView = inflater.inflate(R.layout.message_menu_card, host, false) as GlassLinearLayout
        cardView.glass.source = backdrop
        cardView.isClickable = true
        cardView.setTag(R.id.tag_popover_layer, true)
        val rowViews = ArrayList<View>()
        val normal = items.filterNot { it.destructive }
        val destructive = items.filter { it.destructive }
        normal.forEachIndexed { i, item ->
            if (i > 0) cardView.addView(hairline(inflater, cardView))
            rowViews += bindRow(inflater, cardView, item)
        }
        destructive.forEachIndexed { i, item ->
            if (i == 0 && normal.isNotEmpty()) cardView.addView(groupGap(inflater, cardView))
            else if (i > 0) cardView.addView(hairline(inflater, cardView))
            rowViews += bindRow(inflater, cardView, item)
        }

        // Natural width, kept between a phone-friendly floor and ceiling.
        val minW = (MIN_WIDTH_DP * density).toInt()
        val maxW = minOf((MAX_WIDTH_DP * density).toInt(), host.width - 2 * gutter())
        cardView.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val width = cardView.measuredWidth.coerceIn(minW.coerceAtMost(maxW), maxW)
        val height = cardView.measuredHeight

        val hostLoc = IntArray(2).also { host.getLocationInWindow(it) }
        val anchorLoc = IntArray(2).also { anchor.getLocationInWindow(it) }
        val ax = anchorLoc[0] - hostLoc[0]
        val ay = anchorLoc[1] - hostLoc[1]
        val gutter = gutter()
        val gap = (6 * density).toInt()
        val leading = ax.coerceAtLeast(gutter)
        val left = if (leading + width <= host.width - gutter) leading
        else (ax + anchor.width - width).coerceIn(gutter, host.width - width - gutter)
        val imeBottom = ViewCompat.getRootWindowInsets(host)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val roomBelow = host.height - imeBottom - (ay + anchor.height) - gap - gutter
        val below = roomBelow >= height || roomBelow >= ay - gap - gutter
        val lp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.START or if (below) Gravity.TOP else Gravity.BOTTOM
        lp.leftMargin = left
        if (below) lp.topMargin = ay + anchor.height + gap else lp.bottomMargin = host.height - ay + gap
        cardView.layoutParams = lp
        host.addView(cardView)
        scrim = scrimView
        card = cardView

        val shouldAnimate = animated && Motion.areAnimationsEnabled(context)
        cardView.pivotX = (ax + anchor.width / 2f - left).coerceIn(0f, width.toFloat())
        cardView.pivotY = if (below) 0f else height.toFloat()
        if (shouldAnimate) {
            cardView.alpha = 0f
            cardView.scaleX = 0.9f
            cardView.scaleY = 0.9f
            android.animation.AnimatorSet().apply {
                playTogether(
                    android.animation.ObjectAnimator.ofFloat(cardView, View.ALPHA, 1f).setDuration(140).apply { interpolator = Motion.easeOut },
                    android.animation.ObjectAnimator.ofPropertyValuesHolder(
                        cardView,
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f)
                    ).setDuration(420).apply { interpolator = Motion.spring }
                )
                start()
            }
            rowViews.forEachIndexed { i, v ->
                v.alpha = 0f
                v.animate().alpha(1f).setStartDelay(30L + 18L * minOf(i, 6)).setDuration(200)
                    .setInterpolator(Motion.iosOut).start()
            }
            scrimView.animate().alpha(1f).setDuration(160).start()
        } else {
            scrimView.alpha = 1f
        }

        (context as? ComponentActivity)?.let { act ->
            backCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = dismiss()
            }.also { cb ->
                val owner = lifecycleOwner ?: host.findViewTreeLifecycleOwner()
                if (owner != null) act.onBackPressedDispatcher.addCallback(owner, cb)
                else act.onBackPressedDispatcher.addCallback(cb)
            }
        }
    }

    private fun gutter() = (12 * density).toInt()

    private fun bindRow(inflater: LayoutInflater, parent: ViewGroup, item: Item): View {
        val row = inflater.inflate(R.layout.item_message_menu_row, parent, false)
        val label = row.findViewById<TextView>(R.id.messageMenuLabel)
        val icon = row.findViewById<ImageView>(R.id.messageMenuIcon)
        label.text = item.label
        icon.setImageResource(item.icon)
        if (item.destructive) {
            val red = ContextCompat.getColor(context, R.color.delete_action)
            label.setTextColor(red)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(red)
        }
        row.setOnClickListener {
            row.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            dismiss()
            item.onClick()
        }
        parent.addView(row)
        return row
    }

    private fun hairline(inflater: LayoutInflater, parent: ViewGroup): View =
        inflater.inflate(R.layout.item_message_menu_divider, parent, false)

    private fun groupGap(inflater: LayoutInflater, parent: ViewGroup): View =
        inflater.inflate(R.layout.item_message_menu_gap, parent, false)

    fun dismiss(animated: Boolean = true) {
        val c = card ?: return
        val s = scrim
        card = null
        scrim = null
        backCallback?.remove()
        backCallback = null
        val remove = Runnable {
            if (c.parent === host) host.removeView(c)
            if (s != null && s.parent === host) host.removeView(s)
        }
        if (animated && Motion.areAnimationsEnabled(context)) {
            c.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setStartDelay(0)
                .setDuration(130).setInterpolator(Motion.iosIn).withEndAction(remove).start()
            s?.animate()?.alpha(0f)?.setDuration(130)?.start()
        } else {
            remove.run()
        }
        onDismiss?.invoke()
    }

    private companion object {
        const val MIN_WIDTH_DP = 190
        const val MAX_WIDTH_DP = 260
    }
}
