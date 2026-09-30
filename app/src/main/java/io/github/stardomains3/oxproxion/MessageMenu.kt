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
 * its icon on the trailing side, hairlines between rows, and destructive rows last, in the delete red. It is deliberately not [PickerPopover] (the composer's picker,
 * icon-first with subtitles), so a message's menu never reads as a second composer bar.
 *
 * The card opens from the ⋮: its leading edge lines up with the control when there is room,
 * otherwise its trailing edge does, below the control unless the bottom of the screen is closer.
 */
class MessageMenu(
    private val host: FrameLayout,
    private val anchor: View,
    private val backdrop: GlassBackdropLayout?,
    /** The card never crosses these: it stays below [topBound]'s bottom edge and above [bottomBound]'s top edge. */
    private val topBound: View? = null,
    private val bottomBound: View? = null
) {
    class Item(
        val label: CharSequence,
        @DrawableRes val icon: Int,
        /** Goes last, in the delete red. */
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
            if (i > 0 || normal.isNotEmpty()) cardView.addView(hairline(inflater, cardView))
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
        // Nearer the middle of the screen wins: an anchor on the left hangs the card from its leading
        // edge, one on the right from its trailing edge.
        val anchorMid = ax + anchor.width / 2
        val left = (if (anchorMid <= host.width / 2) ax else ax + anchor.width - width)
            .coerceIn(gutter, (host.width - width - gutter).coerceAtLeast(gutter))
        val imeBottom = ViewCompat.getRootWindowInsets(host)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val topLimit = topBound?.let { it.locationIn(host)[1] + it.height + gap } ?: gutter
        val bottomLimit = bottomBound?.let { it.locationIn(host)[1] - gap } ?: (host.height - imeBottom - gutter)
        val roomBelow = bottomLimit - (ay + anchor.height + gap)
        val roomAbove = ay - gap - topLimit
        val below = when {
            roomBelow >= height && roomAbove >= height -> ay + anchor.height / 2 <= (topLimit + bottomLimit) / 2
            roomBelow >= height -> true
            roomAbove >= height -> false
            else -> roomBelow >= roomAbove
        }
        val wanted = if (below) ay + anchor.height + gap else ay - gap - height
        val top = wanted.coerceIn(topLimit, (bottomLimit - height).coerceAtLeast(topLimit))
        val lp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.START or Gravity.TOP
        lp.leftMargin = left
        lp.topMargin = top
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

    /** The screen gutter of the design contract. */
    private fun gutter() = (16 * density).toInt()

    private fun View.locationIn(parent: View): IntArray {
        val p = IntArray(2).also { parent.getLocationInWindow(it) }
        val me = IntArray(2).also { getLocationInWindow(it) }
        return intArrayOf(me[0] - p[0], me[1] - p[1])
    }

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
            Haptics.tap(row)
            dismiss()
            item.onClick()
        }
        parent.addView(row)
        return row
    }

    private fun hairline(inflater: LayoutInflater, parent: ViewGroup): View =
        inflater.inflate(R.layout.item_message_menu_divider, parent, false)

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
