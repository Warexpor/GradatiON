package io.github.stardomains3.oxproxion

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import org.xmlpull.v1.XmlPullParser
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Liquid-glass toggle, after the iOS 26 switch: a 52x32 capsule cage (a faint sunken wash
 * when off, a dense neutral fill when on) carrying a round 28dp bead. The bead never changes
 * color, so a toggle reads by the track alone and nothing flashes. Held, the bead widens into
 * a short pill and turns clear, a lens over the track, then settles back into a circle.
 * Neutral grays only. Every switch in the app is a SwitchCompat on these two drawables, so
 * they are all the same size.
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
    private var rim = 0
    private var rimTop = 0
    private var rimShaderTop = Float.NaN

    private var checked = false
    private var enabled = true
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var alphaMul = 255

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        density = r.displayMetrics.density
        offTint = r.color(R.color.switch_track_off, theme)
        onTint = r.color(R.color.switch_track_on, theme)
        rim = r.color(R.color.switch_track_rim, theme)
        rimTop = r.color(R.color.switch_track_rim_top, theme)
        rimPaint.strokeWidth = max(1f, density * 0.75f)
    }

    override fun getIntrinsicWidth() = (TRACK_W_DP * density).roundToInt()
    override fun getIntrinsicHeight() = (TRACK_H_DP * density).roundToInt()

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
            duration = 260L
            interpolator = Motion.iosOut
            addUpdateListener { progress = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val h = TRACK_H_DP * density
        // Center a capsule of the track height (SwitchCompat may give taller bounds).
        val top = b.exactCenterY() - h / 2f
        rect.set(b.left.toFloat(), top, b.right.toFloat(), top + h)
        val r = h / 2f
        val mul = if (enabled) alphaMul else alphaMul * 40 / 100

        fill.color = lerpColor(offTint, onTint, progress)
        fill.alpha = fill.alpha * mul / 255
        canvas.drawRoundRect(rect, r, r, fill)

        // Glass edge: a hairline that is darker along the top (the capsule reads sunken) and
        // fades as the fill takes over.
        val i = rimPaint.strokeWidth / 2f
        rect.inset(i, i)
        if (rimShaderTop != rect.top) {
            rimPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(rimTop, rim), null, Shader.TileMode.CLAMP
            )
            rimShaderTop = rect.top
        }
        rimPaint.alpha = (255 * (1f - 0.6f * progress) * mul / 255).roundToInt()
        canvas.drawRoundRect(rect, r - i, r - i, rimPaint)
    }

    override fun setAlpha(alpha: Int) { alphaMul = alpha; invalidateSelf() }
    override fun getAlpha() = alphaMul
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val TRACK_W_DP = 52f
        const val TRACK_H_DP = 32f
    }
}

class GlassSwitchThumbDrawable : Drawable() {

    private var density = 1f
    private var bodyTop = 0
    private var bodyBottom = 0
    private var rimLight = 0
    private var rimDark = 0
    private var shadow = 0

    private var pressed = false
    private var enabled = true
    private var checked = false
    /** 0 = round bead at rest, 1 = held: a wider, clearer lens. */
    private var hold = 0f
    private var holdAnimator: ValueAnimator? = null
    private var alphaMul = 255

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var shaderKey = Float.NaN

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        density = r.displayMetrics.density
        bodyTop = r.color(R.color.switch_bead_top, theme)
        bodyBottom = r.color(R.color.switch_bead_bottom, theme)
        rimLight = r.color(R.color.switch_bead_rim_light, theme)
        rimDark = r.color(R.color.switch_bead_rim_dark, theme)
        shadow = r.color(R.color.switch_thumb_shadow, theme)
        rimPaint.strokeWidth = max(1f, density * 0.75f)
        shadowPaint.color = Color.TRANSPARENT
        shadowPaint.setShadowLayer(2.5f * density, 0f, 1f * density, shadow)
    }

    override fun getIntrinsicWidth() = (THUMB_W_DP * density).roundToInt()
    override fun getIntrinsicHeight() = (GlassSwitchTrackDrawable.TRACK_H_DP * density).roundToInt()

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
        hold = if (pressed) 1f else 0f
        invalidateSelf()
    }

    private fun animateHold(target: Float) {
        holdAnimator?.cancel()
        if (!canAnimateOnScreen()) { hold = target; return }
        holdAnimator = ValueAnimator.ofFloat(hold, target).apply {
            duration = if (target > 0f) 200L else 320L
            interpolator = if (target > 0f) Motion.iosOut else Motion.spring
            addUpdateListener { hold = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val mul = if (enabled) alphaMul else alphaMul * 55 / 100
        val d = BEAD_DP * density
        // Held, the bead widens toward the middle of the track, never past its own edge.
        val w = d + HOLD_GROW_DP * density * hold
        val grow = (w - d) / 2f
        val cx = b.exactCenterX() + if (checked) -grow else grow
        val cy = b.exactCenterY()
        rect.set(cx - w / 2f, cy - d / 2f, cx + w / 2f, cy + d / 2f)
        val r = d / 2f

        if (shaderKey != rect.top) {
            // A plain, bright bead: the faintest fall-off toward the base, no hotspot.
            body.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                bodyTop, bodyBottom, Shader.TileMode.CLAMP
            )
            // Rim: lit along the top, a soft hairline everywhere else.
            rimPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(rimLight, rimDark, rimDark),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            shaderKey = rect.top
        }

        // Held, it turns into clear glass: the body thins out and the shadow lifts, so the
        // track reads through the lens.
        val bodyAlpha = (mul * (1f - 0.4f * hold)).roundToInt()
        shadowPaint.alpha = (mul * (1f - 0.5f * hold)).roundToInt()
        canvas.drawRoundRect(rect, r, r, shadowPaint)
        body.alpha = bodyAlpha
        canvas.drawRoundRect(rect, r, r, body)
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
        /** Thumb slot: travel = track width minus this; the bead sits 2dp inside the track. */
        const val THUMB_W_DP = 32f
        const val BEAD_DP = 28f
        /** How much wider the bead gets while held. */
        const val HOLD_GROW_DP = 8f
    }
}
