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
 * Liquid-glass toggle, after the iOS 26 switch: a capsule track that is clear glass when off
 * and a dense neutral fill when on (never a white slab, never a hue), with a glass-bead thumb
 * (sheen, lit rim, soft shadow) that swells into a clear lens while held and squashes with a
 * spring when it lands.
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
    private var sheen = 0
    private var highlight = 0

    private var checked = false
    private var enabled = true
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var alphaMul = 255

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var shaderH = -1

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        density = r.displayMetrics.density
        offTint = r.color(R.color.switch_track_off, theme)
        onTint = r.color(R.color.switch_track_on, theme)
        rim = r.color(R.color.switch_track_rim, theme)
        sheen = r.color(R.color.glass_sheen, theme)
        highlight = r.color(R.color.glass_highlight, theme)
        rimPaint.strokeWidth = max(1f, density * 0.75f)
        litPaint.strokeWidth = max(1f, density)
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
            duration = 240L
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

        if (shaderH != b.height()) {
            shaderH = b.height()
            sheenPaint.shader = LinearGradient(0f, rect.top, 0f, rect.top + h * 0.55f, sheen, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            litPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(highlight, Color.TRANSPARENT, Color.TRANSPARENT, lerpColor(Color.TRANSPARENT, highlight, 0.4f)),
                floatArrayOf(0f, 0.45f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        sheenPaint.alpha = mul
        canvas.drawRoundRect(rect, r, r, sheenPaint)
        val i = litPaint.strokeWidth / 2f
        rect.inset(i, i)
        litPaint.alpha = mul
        canvas.drawRoundRect(rect, r - i, r - i, litPaint)
        rimPaint.color = rim
        rimPaint.alpha = rimPaint.alpha * mul / 255
        canvas.drawRoundRect(rect, r - i, r - i, rimPaint)
    }

    override fun setAlpha(alpha: Int) { alphaMul = alpha; invalidateSelf() }
    override fun getAlpha() = alphaMul
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val TRACK_W_DP = 60f
        const val TRACK_H_DP = 32f
    }
}

class GlassSwitchThumbDrawable : Drawable() {

    private var density = 1f
    private var top = 0
    private var bottom = 0
    private var rim = 0
    private var shadow = 0
    private var lens = 0
    private var highlight = 0

    private var pressed = false
    private var checked = false
    private var enabled = true
    private var initialized = false
    /** 0 = bead, 1 = swollen clear lens (held). */
    private var lensAmt = 0f
    /** Horizontal squash on landing, 0 = none. */
    private var squash = 0f
    private var pressAnimator: ValueAnimator? = null
    private var squashAnimator: ValueAnimator? = null
    private var alphaMul = 255

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var shaderKey = 0f

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        density = r.displayMetrics.density
        top = r.color(R.color.switch_thumb_top, theme)
        bottom = r.color(R.color.switch_thumb_bottom, theme)
        rim = r.color(R.color.switch_thumb_rim, theme)
        shadow = r.color(R.color.switch_thumb_shadow, theme)
        lens = r.color(R.color.switch_thumb_lens, theme)
        highlight = r.color(R.color.glass_highlight, theme)
        rimPaint.strokeWidth = max(1f, density * 0.9f)
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
            animatePress(if (p) 1f else 0f)
            changed = true
        }
        if (c != checked) {
            checked = c
            if (initialized) landSquash()
            changed = true
        }
        initialized = true
        if (changed) invalidateSelf()
        return changed
    }

    override fun jumpToCurrentState() {
        pressAnimator?.cancel(); squashAnimator?.cancel()
        lensAmt = if (pressed) 1f else 0f
        squash = 0f
        invalidateSelf()
    }

    private fun canAnimate() = canAnimateOnScreen()

    private fun animatePress(target: Float) {
        pressAnimator?.cancel()
        if (!canAnimate()) { lensAmt = target; return }
        pressAnimator = ValueAnimator.ofFloat(lensAmt, target).apply {
            duration = if (target > 0f) 380L else 460L
            interpolator = Motion.springBouncy
            addUpdateListener { lensAmt = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    /** A springy stretch as the bead lands on the other side (runs alongside the slide). */
    private fun landSquash() {
        squashAnimator?.cancel()
        if (!canAnimate()) { squash = 0f; return }
        squashAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520L
            addUpdateListener {
                val t = it.animatedFraction
                // Stretch while travelling, then a damped wobble back to round.
                squash = (kotlin.math.sin(t * Math.PI * 2.2) * kotlin.math.exp(-4.2 * t)).toFloat()
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val mul = if (enabled) alphaMul else alphaMul * 55 / 100
        val baseW = (THUMB_W_DP - 3f) * density
        val baseH = (GlassSwitchTrackDrawable.TRACK_H_DP - 7f) * density
        // Held: swells past the track into a clear lens.
        val w = (baseW + 8f * density * lensAmt) * (1f + 0.16f * squash)
        val h = (baseH + 5f * density * lensAmt) * (1f - 0.08f * squash)
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val r = h / 2f

        // Soft contact shadow, fading as the bead lifts into a lens.
        shadowPaint.color = shadow
        shadowPaint.alpha = (Color.alpha(shadow) * (1f - 0.6f * lensAmt) * mul / 255).roundToInt()
        canvas.save()
        canvas.translate(0f, 1.2f * density)
        val s = 0.6f * density
        canvas.drawRoundRect(rect.left - s, rect.top - s, rect.right + s, rect.bottom + s, r + s, r + s, shadowPaint)
        canvas.restore()

        if (shaderKey != rect.top + rect.height() * 1000f) {
            shaderKey = rect.top + rect.height() * 1000f
            fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
            specPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.top + rect.height() * 0.55f,
                highlight, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        // Bead body; turns into a clear lens (mostly see-through) while held.
        fill.alpha = ((255 - (255 - Color.alpha(lens)) * lensAmt) * mul / 255f).roundToInt()
        canvas.drawRoundRect(rect, r, r, fill)
        specPaint.alpha = mul
        val si = 1.5f * density
        canvas.drawRoundRect(rect.left + si, rect.top + si * 0.6f, rect.right - si, rect.centerY(), r, r, specPaint)
        rimPaint.color = rim
        rimPaint.alpha = (Color.alpha(rim) * (1f + lensAmt) .coerceAtMost(1.8f) * mul / 255).roundToInt().coerceAtMost(255)
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
        const val THUMB_W_DP = 30f
    }
}
