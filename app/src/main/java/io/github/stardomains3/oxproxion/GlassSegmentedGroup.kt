package io.github.stardomains3.oxproxion

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Liquid-glass segmented control, after iOS: one clear glass capsule track with a translucent
 * glass thumb that slides to the checked segment. The thumb's leading edge runs ahead of its
 * trailing edge, so it stretches while it travels and springs back to shape as it lands.
 *
 * Drop-in for [MaterialButtonToggleGroup] (single selection): the child MaterialButtons are
 * stripped of their fill, stroke and ripple, so whatever style the layout gives them the
 * group looks the same everywhere. Everything is drawn with plain paints (no blur, no layers,
 * no per-frame allocation); it only invalidates while the thumb moves.
 */
class GlassSegmentedGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MaterialButtonToggleGroup(context, attrs) {

    private val density = resources.displayMetrics.density
    private val pad = (3f * density).toInt()

    private val trackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = res(R.color.switch_track_off) }
    private val trackSheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density * 0.75f)
        color = res(R.color.switch_track_rim)
    }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = res(R.color.segment_thumb) }
    private val thumbRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density * 0.9f)
        color = res(R.color.segment_thumb_rim)
    }
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = res(R.color.segment_thumb_shadow) }
    private val sheen = res(R.color.glass_sheen)
    private val shadowA = Color.alpha(thumbShadow.color)
    private val fillA = Color.alpha(thumbFill.color)
    private val rimA = Color.alpha(thumbRim.color)

    private val track = RectF()
    private val thumb = RectF()
    private val tmp = RectF()
    private val from = RectF()
    private val to = RectF()
    private var trackShaderH = -1f

    private var targetId = View.NO_ID
    private var progress = 1f
    private var thumbAlpha = 1f
    private var fadeIn = false
    private var animator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        setPadding(pad, pad, pad, pad)
        addOnButtonCheckedListener { _, _, _ -> moveThumb() }
    }

    private fun res(id: Int) = ContextCompat.getColor(context, id)

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        (child as? MaterialButton)?.let(::strip)
        super.addView(child, index, params)
    }

    /** Segments are bare labels; the track and thumb are the only surfaces. */
    private fun strip(b: MaterialButton) {
        b.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        b.strokeWidth = 0
        b.rippleColor = ColorStateList.valueOf(Color.TRANSPARENT)
        b.insetTop = 0
        b.insetBottom = 0
        b.elevation = 0f
        b.stateListAnimator = null
        b.isCheckable = true
        b.setTextColor(ContextCompat.getColorStateList(context, R.color.segment_text))
        b.iconTint = ContextCompat.getColorStateList(context, R.color.segment_text)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        targetId = checkedButtonId
        progress = 1f
        thumbAlpha = if (targetId == View.NO_ID) 0f else 1f
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun childRect(id: Int, out: RectF): Boolean {
        if (id == View.NO_ID) return false
        val c = findViewById<View>(id) ?: return false
        if (c.parent !== this || c.visibility != View.VISIBLE || c.width == 0) return false
        out.set(c.left.toFloat(), c.top.toFloat(), c.right.toFloat(), c.bottom.toFloat())
        return true
    }

    private fun moveThumb() {
        val next = checkedButtonId
        if (next == targetId) return
        // Start from wherever the thumb is drawn now (mid-flight included).
        val hadThumb = targetId != View.NO_ID && thumbAlpha > 0f && !thumb.isEmpty
        if (hadThumb) from.set(thumb)
        targetId = next
        animator?.cancel()
        val animate = isLaidOut && isAttachedToWindow && Motion.areAnimationsEnabled(context)
        if (!animate || next == View.NO_ID) {
            progress = 1f
            thumbAlpha = if (next == View.NO_ID) 0f else 1f
            invalidate()
            return
        }
        fadeIn = !hadThumb
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (fadeIn) 220L else 520L
            addUpdateListener {
                progress = it.animatedFraction
                if (fadeIn) thumbAlpha = Motion.easeOut.getInterpolation(progress)
                invalidate()
            }
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        drawTrack(canvas)
        drawThumb(canvas)
        super.dispatchDraw(canvas)
    }

    private fun drawTrack(canvas: Canvas) {
        if (childCount == 0 || width == 0) return
        track.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = track.height() / 2f
        canvas.drawRoundRect(track, r, r, trackFill)
        if (trackShaderH != track.height()) {
            trackShaderH = track.height()
            trackSheen.shader = LinearGradient(0f, 0f, 0f, track.height() * 0.55f, sheen, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(track, r, r, trackSheen)
        val i = trackRim.strokeWidth / 2f
        track.inset(i, i)
        canvas.drawRoundRect(track, r - i, r - i, trackRim)
    }

    private fun drawThumb(canvas: Canvas) {
        if (thumbAlpha <= 0f || !childRect(targetId, to)) return
        if (progress >= 1f || fadeIn) {
            thumb.set(to)
        } else {
            // Liquid travel: the leading edge springs ahead, the trailing edge follows late.
            val lead = Motion.spring.getInterpolation(min(1f, progress * 1.35f))
            val trail = Motion.spring.getInterpolation(max(0f, (progress - 0.12f) / 0.88f))
            val right = to.centerX() >= from.centerX()
            val l0 = from.left; val r0 = from.right
            val l1 = to.left; val r1 = to.right
            val left = l0 + (l1 - l0) * (if (right) trail else lead)
            val rightEdge = r0 + (r1 - r0) * (if (right) lead else trail)
            val top = from.top + (to.top - from.top) * lead
            val bottom = from.bottom + (to.bottom - from.bottom) * lead
            thumb.set(min(left, rightEdge - density), top, rightEdge, bottom)
            // Thin out a touch while stretched, like a drop in motion.
            thumb.inset(0f, sin(progress * Math.PI).toFloat() * 1.6f * density)
        }
        val r = thumb.height() / 2f
        val a = (thumbAlpha * 255).toInt()

        thumbShadow.alpha = shadowA * a / 255
        canvas.save()
        canvas.translate(0f, 1.2f * density)
        canvas.drawRoundRect(thumb, r, r, thumbShadow)
        canvas.restore()

        thumbFill.alpha = fillA * a / 255
        canvas.drawRoundRect(thumb, r, r, thumbFill)

        thumbRim.alpha = rimA * a / 255
        val ri = thumbRim.strokeWidth / 2f
        tmp.set(thumb)
        tmp.inset(ri, ri)
        canvas.drawRoundRect(tmp, r - ri, r - ri, thumbRim)
    }
}
