package io.github.stardomains3.oxproxion

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import org.xmlpull.v1.XmlPullParser
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Liquid-glass toggle, after the iOS 26 switch: a round capsule track (a faint sunken wash
 * when off, a dense neutral fill when on) carrying a round glass bead. The bead never changes
 * color, so a toggle reads by the track alone and nothing flashes. Held, it stretches
 * sideways like a drop of gel; it wobbles a touch as it lands. Neutral grays only.
 *
 * Both parts are plain drawables (no blur, no layers), inflatable from XML via
 * `<drawable class="...GlassSwitchTrackDrawable" />`, so they work on SwitchCompat and
 * MaterialSwitch through the theme, and through [applyGrokionSwitchStyle].
 */
private fun Resources.color(id: Int, theme: Resources.Theme?) = getColor(id, theme)

private fun lerpColor(a: Int, b: Int, t: Float): Int = Color.argb(
    (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).roundToInt(),
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt()
)

private fun IntArray.has(attr: Int) = contains(attr)

private fun withAlphaOf(color: Int, f: Float) = Color.argb((Color.alpha(color) * f).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

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
    private var bodyMid = 0
    private var bodyBottom = 0
    private var rimLight = 0
    private var rimDark = 0
    private var shadow = 0

    private var pressed = false
    private var checked = false
    private var enabled = true
    private var initialized = false
    /** 0 = round bead, 1 = stretched while held. */
    private var stretch = 0f
    /** Damped wobble on landing, 0 = none. */
    private var wobble = 0f
    private var pressAnimator: ValueAnimator? = null
    private var wobbleAnimator: ValueAnimator? = null
    private var alphaMul = 255

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shaderKey = Float.NaN

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        density = r.displayMetrics.density
        bodyTop = r.color(R.color.switch_bead_top, theme)
        bodyMid = r.color(R.color.switch_bead_mid, theme)
        bodyBottom = r.color(R.color.switch_bead_bottom, theme)
        rimLight = r.color(R.color.switch_bead_rim_light, theme)
        rimDark = r.color(R.color.switch_bead_rim_dark, theme)
        shadow = r.color(R.color.switch_thumb_shadow, theme)
        rimPaint.strokeWidth = max(1f, density * 0.8f)
        shadowPaint.color = Color.TRANSPARENT
        shadowPaint.setShadowLayer(3f * density, 0f, 1.2f * density, shadow)
    }

    override fun getIntrinsicWidth() = (THUMB_W_DP * density).roundToInt()
    override fun getIntrinsicHeight() = (GlassSwitchTrackDrawable.TRACK_H_DP * density).roundToInt()

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.has(android.R.attr.state_pressed)
        val c = state.has(android.R.attr.state_checked)
        val e = state.has(android.R.attr.state_enabled)
        var changed = false
        if (e != enabled) { enabled = e; changed = true }
        if (p != pressed) {
            pressed = p
            animateStretch(if (p) 1f else 0f)
            changed = true
        }
        if (c != checked) {
            checked = c
            if (initialized) land()
            changed = true
        }
        initialized = true
        if (changed) invalidateSelf()
        return changed
    }

    override fun jumpToCurrentState() {
        pressAnimator?.cancel(); wobbleAnimator?.cancel()
        stretch = if (pressed) 1f else 0f
        wobble = 0f
        invalidateSelf()
    }

    private fun animateStretch(target: Float) {
        pressAnimator?.cancel()
        if (!canAnimateOnScreen()) { stretch = target; return }
        pressAnimator = ValueAnimator.ofFloat(stretch, target).apply {
            duration = if (target > 0f) 320L else 420L
            interpolator = Motion.springBouncy
            addUpdateListener { stretch = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    /** A small springy wobble as the bead lands on the other side (runs alongside the slide). */
    private fun land() {
        wobbleAnimator?.cancel()
        if (!canAnimateOnScreen()) { wobble = 0f; return }
        wobbleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480L
            addUpdateListener {
                val t = it.animatedFraction
                wobble = (kotlin.math.sin(t * Math.PI * 2.2) * kotlin.math.exp(-4.6 * t)).toFloat()
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val mul = if (enabled) alphaMul else alphaMul * 55 / 100
        val d = BEAD_DP * density
        // Held: stretches sideways toward the track's middle, never past the switch's edge.
        val w = (d + 7f * density * stretch) * (1f + 0.08f * wobble)
        val h = d * (1f - 0.05f * wobble)
        val grow = (w - d) / 2f
        val cx = b.exactCenterX() + if (checked) -grow else grow
        val cy = b.exactCenterY()
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val r = h / 2f

        if (shaderKey != rect.top + rect.width()) {
            // Glass, not metal: a bright crown, a barely deeper middle and light gathering again
            // at the base (the caustic), with the track showing faintly through.
            body.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(bodyTop, bodyMid, bodyBottom), floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            // Rim: bright along the top, a soft hairline at the sides, lit again at the bottom.
            rimPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(rimLight, rimDark, rimDark, withAlphaOf(rimLight, 0.55f)),
                floatArrayOf(0f, 0.4f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            // Where the light enters: a soft spot up and to the left, not a glow.
            spec.shader = RadialGradient(
                rect.left + rect.width() * 0.34f, rect.top + rect.height() * 0.28f, rect.height() * 0.42f,
                intArrayOf(withAlphaOf(Color.WHITE, 0.55f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
            )
            shaderKey = rect.top + rect.width()
        }

        shadowPaint.alpha = mul
        canvas.drawRoundRect(rect, r, r, shadowPaint)
        body.alpha = mul
        canvas.drawRoundRect(rect, r, r, body)
        spec.alpha = mul
        canvas.drawRoundRect(rect, r, r, spec)
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
        /** Thumb slot: travel = track width minus this; bead sits 3dp inside the track. */
        const val THUMB_W_DP = 32f
        const val BEAD_DP = 26f
    }
}
