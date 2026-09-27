package io.github.stardomains3.oxproxion

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import org.xmlpull.v1.XmlPullParser
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Liquid-glass toggle, after the iOS 26 switch: a 60x28 recessed glass groove (a faint wash
 * when off, a dense neutral fill when on) carrying a frosted 36x24 glass pill. The pill never
 * changes color, so a toggle reads by the groove alone and nothing flashes.
 *
 * Whenever the pill is held, dragged or travelling it swells past the groove into a clear lens:
 * the frost drains out, the groove shows through, and a two-sided rim catches the light. It
 * settles back into frosted glass on a spring once it stops. Neutral grays only.
 *
 * Every switch in the app is a SwitchCompat on these two drawables, so they are all the same
 * size. SwitchCompat makes the thumb travel equal the thumb slot, so the geometry lives in the
 * track's side padding: a [TRAVEL_DP] slot inside [SIDE_PAD_DP] padding on each side, and the
 * thumb paints its pill wider than that slot. The thumb is taller than the groove so the lens
 * has room to swell without being clipped.
 *
 * Both parts are plain drawables (no blur, no layers), inflatable from XML via
 * `<drawable class="...GlassSwitchTrackDrawable" />`, set through the theme's switch style
 * and through [applyGrokionSwitchStyle].
 */
private fun Resources.color(id: Int, theme: Resources.Theme?) = getColor(id, theme)

private fun lerpColor(a: Int, b: Int, t: Float): Int = Color.argb(
    (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).roundToInt(),
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt()
)

private fun IntArray.has(attr: Int) = contains(attr)

/** Animate only what is on screen; state set while binding (not laid out yet) just jumps. */
internal fun Drawable.canAnimateOnScreen(): Boolean {
    val v = callback as? android.view.View ?: return false
    return isVisible && v.isLaidOut && v.isAttachedToWindow
}

class GlassSwitchTrackDrawable : Drawable() {

    private var density = 1f
    private var offTint = 0
    private var onTint = 0
    private var shade = 0
    private var rim = 0
    private var rimTop = 0
    private var lip = 0
    private var shaderTop = Float.NaN

    private var checked = false
    private var enabled = true
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var alphaMul = 255

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        density = r.displayMetrics.density
        offTint = r.color(R.color.switch_track_off, theme)
        onTint = r.color(R.color.switch_track_on, theme)
        shade = r.color(R.color.switch_track_shade, theme)
        rim = r.color(R.color.switch_track_rim, theme)
        rimTop = r.color(R.color.switch_track_rim_top, theme)
        lip = r.color(R.color.switch_track_lip, theme)
        rimPaint.strokeWidth = max(1f, density * 0.75f)
    }

    override fun getIntrinsicWidth() = (TRACK_W_DP * density).roundToInt()
    override fun getIntrinsicHeight() = (TRACK_H_DP * density).roundToInt()

    override fun getPadding(padding: Rect): Boolean {
        val side = (SIDE_PAD_DP * density).roundToInt()
        padding.set(side, 0, side, 0)
        return true
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val c = state.has(android.R.attr.state_checked)
        val e = state.has(android.R.attr.state_enabled)
        var changed = false
        if (e != enabled) { enabled = e; changed = true }
        if (c != checked) {
            checked = c
            animateTo(if (c) 1f else 0f)
            changed = true
        }
        if (changed) invalidateSelf()
        return changed
    }

    override fun jumpToCurrentState() {
        animator?.cancel()
        progress = if (checked) 1f else 0f
        invalidateSelf()
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        if (!canAnimateOnScreen()) { progress = target; return }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 280L
            interpolator = Motion.iosOut
            addUpdateListener { progress = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val h = TRACK_H_DP * density
        // Center a groove of the track height inside the taller switch bounds.
        val top = b.exactCenterY() - h / 2f
        rect.set(b.left.toFloat(), top, b.right.toFloat(), top + h)
        val r = h / 2f
        val mul = if (enabled) alphaMul else alphaMul * 40 / 100

        fill.color = lerpColor(offTint, onTint, progress)
        fill.alpha = fill.alpha * mul / 255
        canvas.drawRoundRect(rect, r, r, fill)

        if (shaderTop != rect.top) {
            // Recessed: the upper wall shades the floor of the groove...
            shadePaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.top + h * 0.55f,
                shade, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            // ...and the rim is dark where the wall faces away, lit along the lower lip.
            rimPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(rimTop, rim, lip),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            shaderTop = rect.top
        }
        shadePaint.alpha = mul
        canvas.drawRoundRect(rect, r, r, shadePaint)

        val i = rimPaint.strokeWidth / 2f
        rect.inset(i, i)
        rimPaint.alpha = (255 * (1f - 0.35f * progress) * mul / 255).roundToInt()
        canvas.drawRoundRect(rect, r - i, r - i, rimPaint)
    }

    override fun setAlpha(alpha: Int) { alphaMul = alpha; invalidateSelf() }
    override fun getAlpha() = alphaMul
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val TRACK_W_DP = 60f
        const val TRACK_H_DP = 28f
        /** How far the pill travels; also the thumb slot SwitchCompat moves. */
        const val TRAVEL_DP = 20f
        /** Side padding that makes TRACK_W = 2 * TRAVEL + 2 * SIDE_PAD. */
        const val SIDE_PAD_DP = (TRACK_W_DP - 2 * TRAVEL_DP) / 2f
    }
}

class GlassSwitchThumbDrawable : Drawable() {

    private var density = 1f
    private var bodyTop = 0
    private var bodyBottom = 0
    private var sheen = 0
    private var rimLight = 0
    private var rimDark = 0
    private var lensTint = 0
    private var lensEdge = 0
    private var shadow = 0

    private var pressed = false
    private var enabled = true
    private var checked = false
    /** 0 = frosted pill at rest, 1 = held: a wide, clear lens. */
    private var hold = 0f
    private var holdAnimator: ValueAnimator? = null
    /** Same as [hold], driven by the pill moving (a tap's slide or a drag). */
    private var motion = 0f
    private var motionTarget = 0f
    private var motionAnimator: ValueAnimator? = null
    private var lastLeft = Int.MIN_VALUE
    private var lastWidth = 0
    private val settle = Runnable { animateMotion(0f) }
    private var alphaMul = 255

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val sheenRect = RectF()
    private val shape = Path()
    private var shaderKey = Float.NaN
    private var shaderH = Float.NaN

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        density = r.displayMetrics.density
        bodyTop = r.color(R.color.switch_bead_top, theme)
        bodyBottom = r.color(R.color.switch_bead_bottom, theme)
        sheen = r.color(R.color.switch_bead_sheen, theme)
        rimLight = r.color(R.color.switch_bead_rim_light, theme)
        rimDark = r.color(R.color.switch_bead_rim_dark, theme)
        lensTint = r.color(R.color.switch_lens_tint, theme)
        lensEdge = r.color(R.color.switch_lens_edge, theme)
        shadow = r.color(R.color.switch_thumb_shadow, theme)
        rimPaint.strokeWidth = max(1f, density * 0.75f)
        edgePaint.strokeWidth = 1.75f * density
        tintPaint.color = lensTint
        shadowPaint.color = Color.TRANSPARENT
        shadowPaint.setShadowLayer(3f * density, 0f, 1.25f * density, shadow)
    }

    override fun getIntrinsicWidth() = (GlassSwitchTrackDrawable.TRAVEL_DP * density).roundToInt()
    override fun getIntrinsicHeight() = (THUMB_H_DP * density).roundToInt()

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.has(android.R.attr.state_pressed)
        val e = state.has(android.R.attr.state_enabled)
        val c = state.has(android.R.attr.state_checked)
        var changed = false
        if (e != enabled) { enabled = e; changed = true }
        if (c != checked) { checked = c; changed = true }
        if (p != pressed) {
            pressed = p
            animateHold(if (p) 1f else 0f)
            changed = true
        }
        if (changed) invalidateSelf()
        return changed
    }

    override fun jumpToCurrentState() {
        holdAnimator?.cancel()
        motionAnimator?.cancel()
        unscheduleSelf(settle)
        hold = if (pressed) 1f else 0f
        motion = 0f
        motionTarget = 0f
        invalidateSelf()
    }

    /**
     * SwitchCompat moves the thumb by re-setting its bounds every frame, and it cancels the
     * pressed state once a drag starts. Watching the bounds move covers taps, drags and
     * programmatic toggles alike; the lens holds while they keep moving.
     */
    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val moved = lastLeft != Int.MIN_VALUE && bounds.width() == lastWidth && bounds.left != lastLeft
        lastLeft = bounds.left
        lastWidth = bounds.width()
        if (!moved || !canAnimateOnScreen()) return
        animateMotion(1f)
        unscheduleSelf(settle)
        scheduleSelf(settle, SystemClock.uptimeMillis() + SETTLE_MS)
    }

    private fun animateHold(target: Float) {
        holdAnimator?.cancel()
        if (!canAnimateOnScreen()) { hold = target; return }
        holdAnimator = ValueAnimator.ofFloat(hold, target).apply {
            duration = if (target > 0f) 200L else 380L
            interpolator = if (target > 0f) Motion.iosOut else Motion.spring
            addUpdateListener { hold = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    private fun animateMotion(target: Float) {
        if (motionTarget == target && motionAnimator?.isRunning == true) return
        motionTarget = target
        motionAnimator?.cancel()
        if (!canAnimateOnScreen()) { motion = target; invalidateSelf(); return }
        motionAnimator = ValueAnimator.ofFloat(motion, target).apply {
            duration = if (target > 0f) 160L else 420L
            interpolator = if (target > 0f) Motion.iosOut else Motion.spring
            addUpdateListener { motion = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val lens = max(hold, motion * MOTION_LENS).coerceIn(0f, 1.1f)
        val mul = if (enabled) alphaMul else alphaMul * 55 / 100

        val w = (PILL_W_DP + LENS_GROW_W_DP * lens) * density
        val h = (PILL_H_DP + LENS_GROW_H_DP * lens) * density
        var cx = b.exactCenterX()
        // The lens may spill past the groove but never past the view, which would clip it.
        (callback as? android.view.View)?.let { v ->
            val edge = 0.75f * density
            cx = cx.coerceIn(edge + w / 2f, max(edge + w / 2f, v.width - edge - w / 2f))
        }
        val cy = b.exactCenterY()
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val r = h / 2f

        if (shaderKey != rect.top || shaderH != h) {
            body.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                bodyTop, bodyBottom, Shader.TileMode.CLAMP
            )
            sheenPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.top + h * 0.5f,
                sheen, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            // A lens is lit on both sides: the light enters along the top and focuses along
            // the base, with the flanks nearly clear.
            edgePaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(lensEdge, Color.TRANSPARENT, Color.TRANSPARENT, lensEdge),
                floatArrayOf(0f, 0.38f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
            rimPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(rimLight, rimDark, rimDark),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            shaderKey = rect.top
            shaderH = h
        }

        val clear = lens.coerceAtMost(1f)
        // As a lens the frost drains out and the shadow thins, so the groove reads through.
        // The shadow is clipped to outside the pill, or it would darken the clear lens.
        shadowPaint.alpha = (mul * (1f - 0.55f * clear)).roundToInt()
        shape.rewind()
        shape.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipOutPath(shape)
        canvas.drawRoundRect(rect, r, r, shadowPaint)
        canvas.restore()
        body.alpha = (mul * (1f - 0.88f * clear)).roundToInt()
        canvas.drawRoundRect(rect, r, r, body)
        if (clear > 0f) {
            tintPaint.alpha = (Color.alpha(lensTint) * clear * mul / 255).roundToInt()
            canvas.drawRoundRect(rect, r, r, tintPaint)
        }

        sheenRect.set(rect.left + r * 0.45f, rect.top + 1.25f * density, rect.right - r * 0.45f, rect.top + h * 0.52f)
        val sr = sheenRect.height() / 2f
        sheenPaint.alpha = (mul * (1f - 0.5f * clear)).roundToInt()
        canvas.drawRoundRect(sheenRect, sr, sr, sheenPaint)

        if (clear > 0f) {
            val ei = edgePaint.strokeWidth / 2f + rimPaint.strokeWidth
            rect.inset(ei, ei)
            edgePaint.alpha = (mul * clear).roundToInt()
            canvas.drawRoundRect(rect, r - ei, r - ei, edgePaint)
            rect.inset(-ei, -ei)
        }

        rimPaint.alpha = mul
        val ri = rimPaint.strokeWidth / 2f
        rect.inset(ri, ri)
        canvas.drawRoundRect(rect, r - ri, r - ri, rimPaint)
    }

    override fun setAlpha(alpha: Int) { alphaMul = alpha; invalidateSelf() }
    override fun getAlpha() = alphaMul
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val PILL_W_DP = 36f
        const val PILL_H_DP = 24f
        /** How much the pill grows as a full lens: it spills past the 28dp groove. */
        const val LENS_GROW_W_DP = 12f
        const val LENS_GROW_H_DP = 10f
        /** Height of the thumb bounds: room for the lens and its shadow. */
        const val THUMB_H_DP = 40f
        /** A tap's slide swells the lens a little less than a finger holding it. */
        const val MOTION_LENS = 0.85f
        /** How long the pill must sit still before the lens settles back. */
        const val SETTLE_MS = 90L
    }
}
