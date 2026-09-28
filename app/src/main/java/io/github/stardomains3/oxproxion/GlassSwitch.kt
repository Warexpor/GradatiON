package io.github.stardomains3.oxproxion

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.animation.AnimationUtils
import androidx.appcompat.widget.SwitchCompat
import org.xmlpull.v1.XmlPullParser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Liquid-glass toggle, after the iOS 26 switch: a 60x28 recessed glass groove (a faint wash
 * when off, a dense neutral fill when on) carrying a 36x24 pill of clear glass. The pill is a
 * lens: it redraws the groove under it magnified, so the groove's edges bend inside it, and a
 * two-sided rim and a sheen catch the light. It never changes color, so a toggle reads by the
 * groove alone and nothing flashes.
 *
 * While a finger holds the pill it swells past the groove and magnifies harder, then settles
 * back on a spring on release. Sliding never resizes it. Neutral grays only.
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
        if (bounds.isEmpty) return
        // The lens redraws the groove magnified inside itself; leave that area to it so the
        // translucent fills are not stacked twice. SwitchCompat sets the thumb's bounds for
        // the frame before it draws the track, so the lens is already where it will be drawn.
        val thumb = (callback as? SwitchCompat)?.thumbDrawable as? GlassSwitchThumbDrawable
        if (thumb == null) { drawGroove(canvas); return }
        canvas.save()
        canvas.clipOutPath(thumb.lensShape())
        drawGroove(canvas)
        canvas.restore()
    }

    /** The groove alone, also drawn scaled up inside the thumb's lens. */
    internal fun drawGroove(canvas: Canvas) {
        val b = bounds
        val h = TRACK_H_DP * density
        // Center a groove of the track height inside the taller switch bounds.
        val top = b.exactCenterY() - h / 2f
        rect.set(b.left.toFloat(), top, b.right.toFloat(), top + h)
        val r = h / 2f
        val mul = if (enabled) alphaMul else alphaMul * 40 / 100
        // The fill follows the pill, so it fills as the pill is dragged or springs across.
        val progress = ((callback as? SwitchCompat)?.thumbDrawable as? GlassSwitchThumbDrawable)
            ?.travelFraction(b) ?: progress

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
    private var rimMid = 0
    private var rimBottom = 0
    private var lensTint = 0
    private var lensEdge = 0
    private var shadow = 0

    private var pressed = false
    private var enabled = true
    private var lastLeft = Int.MIN_VALUE
    private var lastWidth = 0
    /**
     * Where the pill is drawn. SwitchCompat slides the thumb on a short fixed curve; the pill
     * follows that on a soft spring of its own, so a tap lands with a little settle and a drag
     * trails the finger slightly, like something with weight.
     */
    private var drawnX = Float.NaN
    private var drawnV = 0f
    /**
     * The lens swell, 0 = pill at rest, 1 = held (wider, magnifying harder). Only a finger
     * holding the pill swells it; sliding keeps it at rest size. It is a spring stepped with
     * the position, so a quick press and release never restarts from rest.
     */
    private var swell = 0f
    private var swellV = 0f
    private var pressedAtMs = 0L
    private var lastStepMs = 0L
    private var resting = true
    private val unitMatrix = Matrix()
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

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        density = r.displayMetrics.density
        bodyTop = r.color(R.color.switch_bead_top, theme)
        bodyBottom = r.color(R.color.switch_bead_bottom, theme)
        sheen = r.color(R.color.switch_bead_sheen, theme)
        rimLight = r.color(R.color.switch_bead_rim_light, theme)
        rimMid = r.color(R.color.switch_bead_rim_mid, theme)
        rimBottom = r.color(R.color.switch_bead_rim_bottom, theme)
        lensTint = r.color(R.color.switch_lens_tint, theme)
        lensEdge = r.color(R.color.switch_lens_edge, theme)
        shadow = r.color(R.color.switch_thumb_shadow, theme)
        rimPaint.strokeWidth = max(1f, density * 0.4f)
        edgePaint.strokeWidth = 0.6f * density
        tintPaint.color = lensTint
        shadowPaint.color = Color.TRANSPARENT
        shadowPaint.setShadowLayer(1.6f * density, 0f, 0.6f * density, shadow)
    }

    override fun getIntrinsicWidth() = (GlassSwitchTrackDrawable.TRAVEL_DP * density).roundToInt()
    override fun getIntrinsicHeight() = (THUMB_H_DP * density).roundToInt()

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.has(android.R.attr.state_pressed)
        val e = state.has(android.R.attr.state_enabled)
        var changed = false
        if (e != enabled) { enabled = e; changed = true }
        if (p != pressed) {
            pressed = p
            if (p) pressedAtMs = AnimationUtils.currentAnimationTimeMillis()
            wake()
            changed = true
        }
        if (changed) invalidateSelf()
        return changed
    }

    override fun jumpToCurrentState() {
        drawnX = Float.NaN
        drawnV = 0f
        swell = if (pressed) 1f else 0f
        swellV = 0f
        resting = true
        invalidateSelf()
    }

    /**
     * Something just started the springs. Start their clock at the event, not at the last frame
     * drawn (which may be long ago), so the first frame steps by the real time since the touch.
     */
    private fun wake() {
        if (resting) lastStepMs = AnimationUtils.currentAnimationTimeMillis()
        resting = false
    }

    /**
     * A tap is pressed for a moment too, and a swell started then would peak mid-slide, so
     * only a press that outlasts a tap counts as holding the pill.
     */
    private fun holding(now: Long) = pressed && now - pressedAtMs >= HOLD_DELAY_MS
    private fun waitingForHold(now: Long) = pressed && now - pressedAtMs < HOLD_DELAY_MS

    private fun lensTarget(now: Long) = if (holding(now)) 1f else 0f

    /**
     * Step the pill's position and swell springs; once per frame. Uses the frame's vsync time,
     * not the moment this draw happens to run, so the steps are even and the motion does not
     * shimmer.
     */
    private fun follow() {
        val target = bounds.exactCenterX()
        val now = AnimationUtils.currentAnimationTimeMillis()
        val dt = (now - lastStepMs) / 1000f
        if (drawnX.isNaN() || dt > 0.5f || !canAnimateOnScreen()) {
            drawnX = target; drawnV = 0f
            swell = lensTarget(now); swellV = 0f
            lastStepMs = now
            resting = !waitingForHold(now)
            if (!resting && canAnimateOnScreen()) invalidateSelf()
            return
        }
        if (dt <= 0f) return
        lastStepMs = now
        val lensGoal = lensTarget(now)
        val omega = (2 * Math.PI / FOLLOW_RESPONSE_S).toFloat()
        // Swelling pops slightly past full; relaxing is critically damped so it never dips
        // below the resting size.
        val swelling = lensGoal > swell
        val lensOmega = (2 * Math.PI / if (swelling) SWELL_RESPONSE_S else RELAX_RESPONSE_S).toFloat()
        val lensDamping = if (swelling) SWELL_DAMPING else 1f
        var left = dt
        while (left > 0f) {
            val h = minOf(left, 0.004f)
            drawnV += (-omega * omega * (drawnX - target) - 2f * FOLLOW_DAMPING * omega * drawnV) * h
            drawnX += drawnV * h
            swellV += (-lensOmega * lensOmega * (swell - lensGoal) - 2f * lensDamping * lensOmega * swellV) * h
            swell += swellV * h
            left -= h
        }
        val placed = abs(drawnX - target) < 0.3f && abs(drawnV) < 4f * density
        if (placed) { drawnX = target; drawnV = 0f }
        val swollen = abs(swell - lensGoal) < 0.002f && abs(swellV) < 0.02f
        if (swollen) { swell = lensGoal; swellV = 0f }
        resting = placed && swollen && !waitingForHold(now)
        if (!resting) invalidateSelf()
    }

    /** How far along its travel the drawn pill is, 0 (off) to 1 (on), for [track]'s bounds. */
    internal fun travelFraction(track: Rect): Float? {
        if (drawnX.isNaN()) return null
        val half = GlassSwitchTrackDrawable.SIDE_PAD_DP * density + GlassSwitchTrackDrawable.TRAVEL_DP * density / 2f
        val min = track.left + half
        val max = track.right - half
        if (max <= min) return null
        return ((drawnX - min) / (max - min)).coerceIn(0f, 1f)
    }

    /**
     * SwitchCompat moves the thumb by re-setting its bounds every frame. Watching the bounds
     * move covers taps, drags and programmatic toggles alike, and starts the follow spring.
     */
    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val moved = lastLeft != Int.MIN_VALUE && bounds.width() == lastWidth && bounds.left != lastLeft
        lastLeft = bounds.left
        lastWidth = bounds.width()
        if (!moved || !canAnimateOnScreen()) return
        wake()
        invalidateSelf()
    }

    /** Up to a touch past 1: the swell's spring pops slightly beyond full size. */
    private fun lens() = swell.coerceIn(0f, 1.1f)

    /** Where the pill is this frame, spilling past the groove but never past the view. */
    private fun lensRect(out: RectF): RectF {
        val b = bounds
        val lens = lens()
        val w = (PILL_W_DP + LENS_GROW_W_DP * lens) * density
        val h = (PILL_H_DP + LENS_GROW_H_DP * lens) * density
        var cx = if (drawnX.isNaN()) b.exactCenterX() else drawnX
        (callback as? android.view.View)?.let { v ->
            val edge = 0.75f * density
            cx = cx.coerceIn(edge + w / 2f, max(edge + w / 2f, v.width - edge - w / 2f))
        }
        val cy = b.exactCenterY()
        return out.apply { set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f) }
    }

    /** The pill's outline; the track leaves this area for the lens to draw. */
    internal fun lensShape(): Path {
        follow()
        lensRect(rect)
        val r = rect.height() / 2f
        return shape.apply { rewind(); addRoundRect(rect, r, r, Path.Direction.CW) }
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val mul = if (enabled) alphaMul else alphaMul * 55 / 100
        val path = lensShape()
        val lens = lens()
        val h = rect.height()
        val r = h / 2f

        // The pill changes size every frame while it swells, so its gradients are built once
        // over a unit height and stretched onto it, instead of being reallocated per frame.
        if (body.shader == null) {
            body.shader = LinearGradient(0f, 0f, 0f, 1f, bodyTop, bodyBottom, Shader.TileMode.CLAMP)
            sheenPaint.shader = LinearGradient(0f, 0f, 0f, 0.45f, sheen, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            // A lens is lit on both sides: the light enters along the top and focuses along
            // the base, with the flanks nearly clear.
            edgePaint.shader = LinearGradient(
                0f, 0f, 0f, 1f,
                intArrayOf(lensEdge, Color.TRANSPARENT, Color.TRANSPARENT, lensEdge),
                floatArrayOf(0f, 0.38f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
            rimPaint.shader = LinearGradient(
                0f, 0f, 0f, 1f,
                intArrayOf(rimLight, rimMid, rimBottom),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        unitMatrix.setScale(1f, h)
        unitMatrix.postTranslate(0f, rect.top)
        body.shader.setLocalMatrix(unitMatrix)
        sheenPaint.shader.setLocalMatrix(unitMatrix)
        edgePaint.shader.setLocalMatrix(unitMatrix)
        rimPaint.shader.setLocalMatrix(unitMatrix)

        // Shadow outside the pill only; inside, it would cloud the lens.
        shadowPaint.alpha = mul
        canvas.save()
        canvas.clipOutPath(path)
        canvas.drawRoundRect(rect, r, r, shadowPaint)
        canvas.restore()

        // The lens: the groove under the pill, magnified about the pill's center. Held, it
        // magnifies harder.
        val track = (callback as? SwitchCompat)?.trackDrawable as? GlassSwitchTrackDrawable
        if (track != null) {
            val k = 1f + MAGNIFY * (REST_MAGNIFY + (1f - REST_MAGNIFY) * lens)
            canvas.save()
            canvas.clipPath(path)
            canvas.scale(k, k, rect.centerX(), rect.centerY())
            track.drawGroove(canvas)
            canvas.restore()
        }

        body.alpha = mul
        canvas.drawRoundRect(rect, r, r, body)
        tintPaint.alpha = Color.alpha(lensTint) * mul / 255
        canvas.drawRoundRect(rect, r, r, tintPaint)

        val ei = edgePaint.strokeWidth / 2f + edgePaint.strokeWidth
        rect.inset(ei, ei)
        edgePaint.alpha = mul
        canvas.drawRoundRect(rect, r - ei, r - ei, edgePaint)
        rect.inset(-ei, -ei)

        sheenRect.set(rect.left + h * 0.25f, rect.top + 0.5f * density, rect.right - h * 0.25f, rect.top + h * 0.45f)
        val sr = sheenRect.height() / 2f
        sheenPaint.alpha = mul
        canvas.drawRoundRect(sheenRect, sr, sr, sheenPaint)

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
        /** Full magnification of a held lens, and the share of it the pill keeps at rest. */
        const val MAGNIFY = 0.22f
        const val REST_MAGNIFY = 0.6f
        /** How long a press must last before the pill swells; shorter presses are taps. */
        const val HOLD_DELAY_MS = 150L
        /** The swell spring: a quick pop when it grows, a slower unforced relax when it shrinks. */
        const val SWELL_RESPONSE_S = 0.3f
        const val SWELL_DAMPING = 0.62f
        const val RELAX_RESPONSE_S = 0.4f
        /** The pill's follow spring: period in seconds, and a damping that leaves a soft settle. */
        const val FOLLOW_RESPONSE_S = 0.26f
        const val FOLLOW_DAMPING = 0.72f
    }
}
