package io.github.stardomains3.oxproxion

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The view whose content glass surfaces blur. It records its children into a [RenderNode]
 * that glass views draw (blurred) by reference, so scrolling content shows through the glass
 * with no extra copies or per-frame work on the UI thread.
 */
class GlassBackdropLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    internal val contentNode = RenderNode("glassBackdrop")
    internal var hasRecording = false
        private set

    override fun dispatchDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated && width > 0 && height > 0) {
            contentNode.setPosition(0, 0, width, height)
            val recording = contentNode.beginRecording(width, height)
            try {
                super.dispatchDraw(recording)
            } finally {
                contentNode.endRecording()
            }
            hasRecording = true
            canvas.drawRenderNode(contentNode)
        } else {
            super.dispatchDraw(canvas)
        }
    }

    internal companion object {
        /** The backdrop in [root]'s window that [host] floats over (never one containing it). */
        fun find(root: View, host: View): GlassBackdropLayout? {
            if (root is GlassBackdropLayout) return if (host.isDescendantOf(root)) null else root
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    find(root.getChildAt(i), host)?.let { return it }
                }
            }
            return null
        }

        private fun View.isDescendantOf(ancestor: View): Boolean {
            var p = parent
            while (p is View) {
                if (p === ancestor) return true
                p = p.parent
            }
            return false
        }
    }
}

/**
 * How much glass the device gets. Blur is the expensive part, so it follows the platform's
 * own rules for window blurs: off on low-RAM devices and in battery saver, where surfaces
 * become a solid frosted fill instead.
 *
 * - [Level.LIQUID] (API 33+): blur plus an AGSL lens pass (edge refraction, specular rim).
 * - [Level.BLUR] (API 31-32): blur, with the rim drawn on the canvas.
 * - [Level.SOLID]: no live blur at all.
 */
object GlassQuality {
    enum class Level { LIQUID, BLUR, SOLID }

    @Volatile
    private var powerSave = false
    private var lowRam = false
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        lowRam = app.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        val pm = app.getSystemService(PowerManager::class.java)
        powerSave = pm?.isPowerSaveMode == true
        runCatching {
            ContextCompat.registerReceiver(
                app,
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        powerSave = pm?.isPowerSaveMode == true
                    }
                },
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    val level: Level
        get() = when {
            lowRam || powerSave -> Level.SOLID
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Level.LIQUID
            else -> Level.BLUR
        }
}

/**
 * Liquid-glass material, after Apple's description of it (WWDC25 "Meet Liquid Glass"):
 * a lens more than a frost. Content behind is blurred lightly and, near the rim, bent inward
 * from just beyond the edge, so the shape reads through refraction rather than a border. A
 * specular rim catches a top-left light (and a weaker bounce opposite). Interactive glass
 * springs up under the finger, leans toward it and lights from the touch point.
 *
 * Cost: one small blur layer per surface, updated on the RenderThread only when the content
 * under it changes; the lens is a single shader pass over the same pixels. Nothing runs on the
 * UI thread per scroll frame. Software canvases (screenshots, bitmap captures) use a tiny CPU
 * blur of a downscaled snapshot so the look matches.
 */
class GlassMaterial(
    private val host: View,
    attrs: AttributeSet?,
    capsuleByDefault: Boolean = false
) {

    private val density = host.resources.displayMetrics.density

    var source: GlassBackdropLayout? = null
        set(value) {
            field = value
            host.invalidate()
        }
    /** Negative means a capsule (half the short side), whatever the size. */
    var cornerRadius: Float = if (capsuleByDefault) -1f else 0f
        set(value) {
            field = value
            shapeDirty = true
            host.invalidate()
        }
    var tintColor: Int = ContextCompat.getColor(host.context, R.color.glass_tint)
        set(value) {
            field = value
            tintPaint.color = value
            lensDirty = true
            host.invalidate()
        }
    /** 0..1: how much of the hairline shows. */
    var edgeAlpha: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v == field) return
            field = v
            host.invalidate()
        }
    /** Draw the hairline only along the bottom edge (bars) instead of all around (cards). */
    var bottomEdgeOnly: Boolean = false
    /** Bend content at the rim. Off for very wide surfaces where it would only distract. */
    var lensing: Boolean = true
    /** Springs, leans and glows on touch (buttons, chips). */
    var interactive: Boolean = false

    private var blurRadiusPx = 22f * density
    private val blurNode = RenderNode("glass")
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tintColor }
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdropColor = ContextCompat.getColor(host.context, R.color.xai_canvas)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density * 0.75f)
        color = ContextCompat.getColor(host.context, R.color.glass_edge)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
    }
    private val highlight = ContextCompat.getColor(host.context, R.color.glass_highlight)
    private val glowColor = ContextCompat.getColor(host.context, R.color.glass_glow)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var shapeDirty = true
    private var lensDirty = true
    private var lastW = 0
    private var lastH = 0
    private var radius = 0f
    private val hostLoc = IntArray(2)
    private val srcLoc = IntArray(2)
    private var lastDx = Int.MIN_VALUE
    private var lastDy = Int.MIN_VALUE
    private var searched = false
    private var effectLevel: GlassQuality.Level? = null

    // Touch state
    private var touchX = 0f
    private var touchY = 0f
    private var press = 0f
    private var pressAnimator: ValueAnimator? = null

    private val positionWatcher = ViewTreeObserver.OnPreDrawListener {
        // Glass that moves (sheets, animated panels) must re-sample what is behind it.
        val src = source
        if (src != null && host.isShown) {
            host.getLocationInWindow(hostLoc)
            src.getLocationInWindow(srcLoc)
            val dx = hostLoc[0] - srcLoc[0]
            val dy = hostLoc[1] - srcLoc[1]
            if (dx != lastDx || dy != lastDy) {
                // Record now so a frame that never reaches draw() can't re-trigger forever.
                lastDx = dx
                lastDy = dy
                host.invalidate()
            }
        }
        true
    }

    init {
        GlassQuality.init(host.context)
        host.setWillNotDraw(false)
        val a = host.context.obtainStyledAttributes(attrs, R.styleable.GlassMaterial)
        try {
            cornerRadius = a.getDimension(R.styleable.GlassMaterial_glassCornerRadius, cornerRadius)
            if (a.hasValue(R.styleable.GlassMaterial_glassTint)) {
                tintColor = a.getColor(R.styleable.GlassMaterial_glassTint, tintColor)
            }
            blurRadiusPx = a.getDimension(R.styleable.GlassMaterial_glassBlurRadius, blurRadiusPx)
            bottomEdgeOnly = a.getBoolean(R.styleable.GlassMaterial_glassBottomEdgeOnly, false)
            edgeAlpha = a.getFloat(R.styleable.GlassMaterial_glassEdgeAlpha, 1f)
            lensing = a.getBoolean(R.styleable.GlassMaterial_glassLens, true)
            interactive = a.getBoolean(R.styleable.GlassMaterial_glassInteractive, false)
            val elevation = a.getDimension(R.styleable.GlassMaterial_glassElevation, 0f)
            if (elevation > 0f) {
                host.elevation = elevation
                host.outlineAmbientShadowColor = ContextCompat.getColor(host.context, R.color.glass_shadow_ambient)
                host.outlineSpotShadowColor = ContextCompat.getColor(host.context, R.color.glass_shadow_spot)
            }
        } finally {
            a.recycle()
        }
        host.clipToOutline = false
        host.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = effectiveRadius(view.width, view.height)
                outline.setRoundRect(0, 0, view.width, view.height, r)
                outline.alpha = 1f
            }
        }
    }

    private fun effectiveRadius(w: Int, h: Int): Float {
        val half = min(w, h) / 2f
        return if (cornerRadius < 0f) half else min(cornerRadius, half)
    }

    fun onAttached() {
        host.viewTreeObserver.addOnPreDrawListener(positionWatcher)
        if (source == null) findSource()
    }

    fun onDetached() {
        host.viewTreeObserver.removeOnPreDrawListener(positionWatcher)
        pressAnimator?.cancel()
        press = 0f
        searched = false
    }

    private fun findSource() {
        if (searched) return
        searched = true
        GlassBackdropLayout.find(host.rootView, host)?.let { source = it }
    }

    // ── Touch ──────────────────────────────────────────────────────────────────────────

    /** Feed the host's touch stream (don't consume); only acts when [interactive]. */
    fun onTouch(event: MotionEvent) {
        if (!interactive || !host.isEnabled) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = event.x
                touchY = event.y
                PressRoom.open(host, max(host.width, host.height) * (PRESS_SCALE - 1f) / 2f + 4f * density)
                animatePress(1f)
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                lean(event.x, event.y)
                host.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animatePress(0f)
        }
    }

    // Gel-like: the glass leans a few dp toward the finger while held.
    private fun leanX(x: Float) = ((x - host.width / 2f) * 0.08f).coerceIn(-3f * density, 3f * density)
    private fun leanY(y: Float) = ((y - host.height / 2f) * 0.08f).coerceIn(-3f * density, 3f * density)

    private fun lean(x: Float, y: Float) {
        if (!Motion.areAnimationsEnabled(host.context) || press < 0.99f) return
        host.translationX = leanX(x)
        host.translationY = leanY(y)
    }

    private fun animatePress(target: Float) {
        pressAnimator?.cancel()
        val animate = Motion.areAnimationsEnabled(host.context)
        val down = target > 0f
        if (animate) {
            host.animate()
                .scaleX(if (down) PRESS_SCALE else 1f)
                .scaleY(if (down) PRESS_SCALE else 1f)
                .translationX(if (down) leanX(touchX) else 0f)
                .translationY(if (down) leanY(touchY) else 0f)
                .setInterpolator(if (down) Motion.springBouncy else Motion.spring)
                .setDuration(if (down) 420L else 560L)
                .start()
        } else if (!down) {
            host.translationX = 0f
            host.translationY = 0f
        }
        pressAnimator = ValueAnimator.ofFloat(press, target).apply {
            duration = if (!animate) 0L else if (down) 140L else 460L
            interpolator = Motion.iosOut
            addUpdateListener {
                press = it.animatedValue as Float
                host.invalidate()
            }
            start()
        }
    }

    // ── Drawing ────────────────────────────────────────────────────────────────────────

    /** Call from the host's draw() before its own content. */
    fun draw(canvas: Canvas) {
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        if (shapeDirty || w != lastW || h != lastH) {
            radius = effectiveRadius(w, h)
            path.reset()
            rect.set(0f, 0f, w.toFloat(), h.toFloat())
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            rimPaint.shader = LinearGradient(
                0f, 0f, w * 0.55f, h.toFloat(),
                intArrayOf(highlight, Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(highlight, 0.45f)),
                floatArrayOf(0f, 0.42f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            lastW = w
            lastH = h
            shapeDirty = false
            lensDirty = true
            host.invalidateOutline()
        }
        if (source == null) findSource()

        val level = if (canvas.isHardwareAccelerated) GlassQuality.level else GlassQuality.Level.BLUR
        var tinted = false
        canvas.save()
        canvas.clipPath(path)
        val src = source
        if (level == GlassQuality.Level.SOLID) {
            solidPaint.color = solidOf(tintColor)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), solidPaint)
            tinted = true
        } else if (src != null && src.isShown) {
            host.getLocationInWindow(hostLoc)
            src.getLocationInWindow(srcLoc)
            lastDx = hostLoc[0] - srcLoc[0]
            lastDy = hostLoc[1] - srcLoc[1]
            if (canvas.isHardwareAccelerated) {
                if (src.hasRecording) tinted = drawHardware(canvas, src, w, h, level)
            } else {
                drawSoftware(canvas, src, w, h)
            }
        }
        if (!tinted) canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), tintPaint)
        drawGlow(canvas, w, h)
        canvas.restore()
        drawEdges(canvas, w, h, liquidRim = tinted && level == GlassQuality.Level.LIQUID)
    }

    /** Returns true when the tint was already applied (by the lens shader). */
    private fun drawHardware(
        canvas: Canvas,
        src: GlassBackdropLayout,
        w: Int,
        h: Int,
        level: GlassQuality.Level
    ): Boolean {
        val liquid = level == GlassQuality.Level.LIQUID && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (effectLevel != level || (liquid && lensDirty)) {
            blurNode.setRenderEffect(buildEffect(liquid, w, h))
            effectLevel = level
            lensDirty = false
        }
        // Sample a margin beyond the shape so the lens has real content to bend in and the
        // blur doesn't smear a clamped edge.
        val pad = padFor(w, h)
        blurNode.setPosition(-pad, -pad, w + pad, h + pad)
        val rc = blurNode.beginRecording(w + 2 * pad, h + 2 * pad)
        try {
            rc.drawColor(backdropColor)
            rc.translate(-(lastDx - pad).toFloat(), -(lastDy - pad).toFloat())
            rc.drawRenderNode(src.contentNode)
        } finally {
            blurNode.endRecording()
        }
        canvas.drawRenderNode(blurNode)
        return liquid
    }

    private fun padFor(w: Int, h: Int): Int = (min(w, h) * 0.3f).coerceAtMost(18f * density).roundToInt()

    private fun buildEffect(liquid: Boolean, w: Int, h: Int): RenderEffect {
        val saturate = ColorMatrix().apply { setSaturation(1.5f) }
        val blur = RenderEffect.createChainEffect(
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturate)),
            RenderEffect.createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP)
        )
        if (!liquid) return blur
        // A driver that can't compile the lens keeps plain blur rather than crashing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return blur
        val lens = runCatching { lensEffect(w, h) }.getOrNull() ?: return blur
        return RenderEffect.createChainEffect(lens, blur)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun lensEffect(w: Int, h: Int): RenderEffect {
        val shader = RuntimeShader(LENS_AGSL)
        val short = min(w, h).toFloat()
        // Bigger glass is "thicker": a wider band and a stronger bend, as Apple describes.
        val band = (short * 0.34f).coerceIn(6f * density, 22f * density)
        val bend = if (lensing) band * (if (interactive) 0.9f else 0.6f) else 0f
        shader.setFloatUniform("size", w.toFloat(), h.toFloat())
        shader.setFloatUniform("pad", padFor(w, h).toFloat())
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("band", band)
        shader.setFloatUniform("bend", bend)
        shader.setFloatUniform("px", density)
        shader.setFloatUniform(
            "tint",
            Color.red(tintColor) / 255f,
            Color.green(tintColor) / 255f,
            Color.blue(tintColor) / 255f,
            Color.alpha(tintColor) / 255f
        )
        shader.setFloatUniform("spec", Color.alpha(highlight) / 255f)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

    private fun drawSoftware(canvas: Canvas, src: View, w: Int, h: Int) {
        val scale = SOFTWARE_SCALE
        val bw = max(1, (w * scale).roundToInt())
        val bh = max(1, (h * scale).roundToInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(backdropColor)
        c.scale(scale, scale)
        c.translate(-lastDx.toFloat(), -lastDy.toFloat())
        src.draw(c)
        val radius = max(1, (blurRadiusPx * scale / 2f).roundToInt())
        boxBlur(bmp, radius)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawBitmap(bmp, null, rect, bitmapPaint)
        bmp.recycle()
    }

    private fun drawGlow(canvas: Canvas, w: Int, h: Int) {
        if (press <= 0.001f) return
        // Light from within, spreading from the fingertip.
        val r = hypot(w.toFloat(), h.toFloat()) * 0.75f
        glowPaint.shader = RadialGradient(
            touchX, touchY, r,
            intArrayOf(glowColor, withAlpha(glowColor, 0.35f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.alpha = (255 * press).roundToInt()
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glowPaint)
    }

    private fun drawEdges(canvas: Canvas, w: Int, h: Int, liquidRim: Boolean) {
        if (edgeAlpha <= 0f) return
        val baseEdge = edgePaint.alpha
        edgePaint.alpha = (baseEdge * edgeAlpha).roundToInt()
        val inset = edgePaint.strokeWidth / 2f
        if (bottomEdgeOnly) {
            canvas.drawLine(0f, h - inset, w.toFloat(), h - inset, edgePaint)
        } else {
            rect.set(inset, inset, w - inset, h - inset)
            val r = max(0f, radius - inset)
            canvas.drawRoundRect(rect, r, r, edgePaint)
            if (!liquidRim) {
                // The lens shader lights the rim itself; elsewhere a gradient stroke stands in.
                val ri = rimPaint.strokeWidth / 2f
                rect.set(ri, ri, w - ri, h - ri)
                rimPaint.alpha = (255 * edgeAlpha).roundToInt()
                canvas.drawRoundRect(rect, max(0f, radius - ri), max(0f, radius - ri), rimPaint)
            }
        }
        edgePaint.alpha = baseEdge
    }

    private fun solidOf(tint: Int): Int =
        Color.argb(max(Color.alpha(tint), 0xF2), Color.red(tint), Color.green(tint), Color.blue(tint))

    private fun withAlpha(color: Int, f: Float): Int =
        Color.argb((Color.alpha(color) * f).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

    internal companion object {
        const val SOFTWARE_SCALE = 0.25f
        const val PRESS_SCALE = 1.07f

        /**
         * Lens pass over the blurred backdrop (coordinates include [pad] on every side).
         * Near the rim, samples are pulled from just outside the shape, so content bends in
         * the way it does through the thick edge of a glass lens; a thin specular line and a
         * soft inner sheen light the rim from the top-left, with a weaker bounce opposite.
         */
        const val LENS_AGSL = """
            uniform shader content;
            uniform float2 size;
            uniform float pad;
            uniform float radius;
            uniform float band;
            uniform float bend;
            uniform float px;
            uniform float4 tint;
            uniform float spec;

            float sdRound(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 coord) {
                float2 b = size * 0.5;
                float2 p = coord - pad - b;
                float r = min(radius, min(b.x, b.y));
                float d = sdRound(p, b, r);
                float2 g = float2(
                    sdRound(p + float2(1.0, 0.0), b, r) - sdRound(p - float2(1.0, 0.0), b, r),
                    sdRound(p + float2(0.0, 1.0), b, r) - sdRound(p - float2(0.0, 1.0), b, r));
                float2 n = g / max(length(g), 0.0001);
                float t = clamp(1.0 + d / band, 0.0, 1.0);
                float k = t * t * t;
                half4 c = content.eval(coord + n * k * bend);
                c.rgb = mix(c.rgb, half3(tint.rgb), half(tint.a));

                float2 light = normalize(float2(-0.5, -0.87));
                float lit = pow(max(dot(n, light), 0.0), 1.6);
                float bounce = pow(max(dot(n, -light), 0.0), 2.0) * 0.45;
                float line = 1.0 - smoothstep(0.0, 1.4 * px, -d);
                float sheen = t * t * 0.35;
                float s = clamp((lit + bounce) * (line * 0.85 + sheen) * spec * 2.2, 0.0, 1.0);
                c.rgb = c.rgb + (half3(1.0) - c.rgb) * half(s);
                return half4(c.rgb, 1.0);
            }
        """

        /** Three box passes approximate a gaussian; fine at thumbnail scale. */
        fun boxBlur(bmp: Bitmap, radius: Int) {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val tmp = IntArray(w * h)
            repeat(3) {
                blurPass(px, tmp, w, h, radius, horizontal = true)
                blurPass(tmp, px, w, h, radius, horizontal = false)
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
        }

        fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val outer = if (horizontal) h else w
            val inner = if (horizontal) w else h
            val div = 2 * r + 1
            for (o in 0 until outer) {
                var sa = 0; var sr = 0; var sg = 0; var sb = 0
                fun idx(i: Int): Int {
                    val c = i.coerceIn(0, inner - 1)
                    return if (horizontal) o * w + c else c * w + o
                }
                for (i in -r..r) {
                    val p = src[idx(i)]
                    sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
                }
                for (i in 0 until inner) {
                    dst[idx(i)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                    val pOut = src[idx(i - r)]
                    val pIn = src[idx(i + r + 1)]
                    sa += (pIn ushr 24) - (pOut ushr 24)
                    sr += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                    sg += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                    sb += (pIn and 0xFF) - (pOut and 0xFF)
                }
            }
        }
    }
}

/**
 * Pressed glass swells past its own bounds. Parents clip to their padding by default, which
 * sliced the top and bottom off buttons sitting in padded rows, so a press opens just enough
 * ancestors for the swell to show. Scrolling containers keep their clip: their padding is
 * where content scrolls out of sight.
 */
internal object PressRoom {
    fun open(view: View, grow: Float) {
        var l = view.left - grow
        var t = view.top - grow
        var r = view.right + grow
        var b = view.bottom + grow
        var child = view
        repeat(MAX_DEPTH) {
            val p = child.parent as? ViewGroup ?: return
            if (p is androidx.core.view.ScrollingView || p is android.widget.ScrollView ||
                p is android.widget.HorizontalScrollView || p is android.widget.AbsListView
            ) return
            if (l >= p.paddingLeft && t >= p.paddingTop && r <= p.width - p.paddingRight && b <= p.height - p.paddingBottom) return
            if (p.clipToPadding) p.clipToPadding = false
            if (l >= 0f && t >= 0f && r <= p.width && b <= p.height) return
            if (p.clipChildren) p.clipChildren = false
            l += p.left; t += p.top; r += p.left; b += p.top
            child = p
        }
    }

    private const val MAX_DEPTH = 4
}

class GlassFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    val glass = GlassMaterial(this, attrs)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass.onAttached()
    }

    override fun onDetachedFromWindow() {
        glass.onDetached()
        super.onDetachedFromWindow()
    }

    override fun draw(canvas: Canvas) {
        glass.draw(canvas)
        super.draw(canvas)
    }
}

class GlassLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    val glass = GlassMaterial(this, attrs)
    /** Set on panels with a grabber so a downward drag anywhere on them dismisses. */
    var dragDismiss: DragDismiss? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        dragDismiss?.onIntercept(ev) == true || super.onInterceptTouchEvent(ev)

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        dragDismiss?.onTouch(event) == true || super.onTouchEvent(event)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass.onAttached()
    }

    override fun onDetachedFromWindow() {
        glass.onDetached()
        super.onDetachedFromWindow()
    }

    override fun draw(canvas: Canvas) {
        glass.draw(canvas)
        super.draw(canvas)
    }
}

/**
 * Floating round glass control (iOS 26 bar buttons): a capsule of liquid glass under the
 * icon that springs up, leans and lights from the touch point. Replaces the shrink-on-press
 * animator and press overlay of the plain icon buttons.
 */
class GlassIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {
    val glass = GlassMaterial(this, attrs, capsuleByDefault = true)

    init {
        if (glass.interactive) {
            stateListAnimator = null
            foreground = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass.onAttached()
    }

    override fun onDetachedFromWindow() {
        glass.onDetached()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        glass.onTouch(event)
        return super.onTouchEvent(event)
    }

    override fun draw(canvas: Canvas) {
        glass.draw(canvas)
        super.draw(canvas)
    }
}

/** Glass capsule label (model and mode chips). */
class GlassTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {
    val glass = GlassMaterial(this, attrs, capsuleByDefault = true)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass.onAttached()
    }

    override fun onDetachedFromWindow() {
        glass.onDetached()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        glass.onTouch(event)
        return super.onTouchEvent(event)
    }

    override fun draw(canvas: Canvas) {
        glass.draw(canvas)
        super.draw(canvas)
    }
}
