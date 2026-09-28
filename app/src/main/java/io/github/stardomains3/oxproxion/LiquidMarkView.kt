package io.github.stardomains3.oxproxion

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.widget.ImageViewCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The GradatiON mark as liquid glass: the view's drawable becomes a slab of smoked glass whose
 * surface slowly moves like liquid. The rim catches a light that circles the upper left, smoke
 * drifts inside, and a glint crosses it every few seconds. All of it stays in neutral grays
 * around the image tint, so it keeps the flat mark's tone on either theme.
 *
 * Drop-in for an [android.widget.ImageView] showing `ic_gradation_mark` (src and tint work as usual).
 *
 * Cost (API 33+): one AGSL pass over the view's own pixels. The drawable is rasterized once per
 * size into a crisp mask and a blurred height map, and the shader reads the surface from them.
 * It ticks at [FRAME_MS] only while shown; it holds a still frame with animations off or at
 * [GlassQuality.Level.SOLID], and stops entirely when hidden or detached. It sits inside the
 * chat's glass backdrop, so every frame also refreshes the glass sampling it, hence the low rate.
 * API 31-32, or a driver that can't compile the shader, draws the plain tinted drawable.
 * A software canvas (the pager's fallback snapshot) cannot run the shader, so it blits
 * [lastFrame]: the latest hardware frame, copied off the draw path by [HardwareRaster].
 */
class LiquidMarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    enum class MarkStyle { OFF, PLAIN, LIQUID }

    /**
     * Off hides the mark. Plain is the flat vector. Liquid is the moving glass (the default).
     */
    var markStyle: MarkStyle = MarkStyle.LIQUID
        set(value) {
            if (field == value) return
            field = value
            animated = value == MarkStyle.LIQUID
            visibility = if (value == MarkStyle.OFF) GONE else VISIBLE
            invalidate()
        }

    /** False holds a single still frame. */
    var animated: Boolean = true
        set(value) {
            field = value
            updateTicking()
        }

    /** Seconds of animation; starts where the light already sits on the upper-left rim. */
    var animTime = START_TIME
        internal set

    // API 33+ (kept untyped so the class loads on 31-32).
    private var shader: Any? = null
    private var shaderFailed = false
    private var maskMap: Bitmap? = null
    private var heightMap: Bitmap? = null
    private var surfaceKey = ""
    private val paint = Paint()
    private var ticking = false
    private var windowVisible = true
    private var lastTick = 0L
    /** Latest liquid frame for software canvases (pager snapshot). Never built inside onDraw. */
    private var lastFrame: Bitmap? = null
    private var lastFrameTime = Float.NaN
    private var cachePosted = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            val now = SystemClock.uptimeMillis()
            if (canAnimate()) {
                animTime += min(0.25f, (now - lastTick) / 1000f)
                invalidate()
                Choreographer.getInstance().postFrameCallbackDelayed(this, FRAME_MS)
            } else {
                // Battery saver or animations off: hold still, look again in a while.
                Choreographer.getInstance().postFrameCallbackDelayed(this, IDLE_POLL_MS)
            }
            lastTick = now
        }
    }

    private val cacheFrame = Runnable {
        cachePosted = false
        refreshLastFrame()
    }

    init {
        GlassQuality.init(context)
    }

    /** True when the liquid shader draws this view (API 33+ and it compiled). Visible for tests. */
    val isLiquid: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shaderFailed && drawable != null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTicking()
    }

    override fun onDetachedFromWindow() {
        stopTicking()
        removeCallbacks(cacheFrame)
        cachePosted = false
        lastFrame?.recycle()
        lastFrame = null
        lastFrameTime = Float.NaN
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateTicking()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        updateTicking()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dropSurface()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        invalidate()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        dropSurface()
        updateTicking()
    }

    private fun dropSurface() {
        surfaceKey = ""
        maskMap = null
        heightMap = null
        lastFrame?.recycle()
        lastFrame = null
        lastFrameTime = Float.NaN
    }

    private fun canAnimate(): Boolean =
        Motion.areAnimationsEnabled(context) && GlassQuality.level != GlassQuality.Level.SOLID

    private fun updateTicking() {
        val run = animated && isLiquid && isAttachedToWindow && windowVisible && isShown
        if (run == ticking) return
        if (run) {
            ticking = true
            lastTick = SystemClock.uptimeMillis()
            Choreographer.getInstance().postFrameCallbackDelayed(frame, FRAME_MS)
        } else {
            stopTicking()
        }
    }

    private fun stopTicking() {
        ticking = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onDraw(canvas: Canvas) {
        if (markStyle != MarkStyle.LIQUID) {
            super.onDraw(canvas)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            super.onDraw(canvas)
            return
        }
        // RuntimeShader only runs on a hardware canvas. Never spin up a HardwareRenderer here:
        // the pager's drawToBitmap is software, and nested GPU work mid-draw crashes the process.
        if (canvas.isHardwareAccelerated && drawLiquid(canvas)) {
            scheduleCacheRefresh()
            return
        }
        val cached = lastFrame
        if (cached != null && !cached.isRecycled) {
            canvas.drawBitmap(cached, 0f, 0f, null)
            return
        }
        super.onDraw(canvas)
    }

    private fun scheduleCacheRefresh() {
        if (cachePosted || width == 0 || height == 0) return
        // A swipe only needs a recent frame, not a readback on every tick.
        if (lastFrame != null && abs(animTime - lastFrameTime) < 0.4f) return
        cachePosted = true
        post(cacheFrame)
    }

    /** Rasterize the current liquid frame off the draw path for software snapshots. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun refreshLastFrame() {
        if (!isLiquid || markStyle != MarkStyle.LIQUID || width == 0 || height == 0) return
        if (lastFrame != null && lastFrameTime == animTime) return
        val frame = runCatching { rasterizeLiquid() }.getOrNull() ?: return
        lastFrame?.recycle()
        lastFrame = frame
        lastFrameTime = animTime
    }

    /** The current liquid frame as a software bitmap, for canvases that cannot run the shader. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun rasterizeLiquid(): Bitmap? {
        var drew = false
        val frame = HardwareRaster.render(width, height, software = true) { drew = drawLiquid(it) }
        if (!drew) { frame?.recycle(); return null }
        return frame
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawLiquid(canvas: Canvas): Boolean {
        val d = drawable ?: return false
        if (width == 0 || height == 0) return false
        val s = shader() ?: return false
        val (m, h) = surface(d) ?: return false
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val tint = ImageViewCompat.getImageTintList(this)?.getColorForState(drawableState, Color.GRAY) ?: Color.GRAY
        val density = resources.displayMetrics.density
        val step = max(1f, 1.5f * density)
        s.setInputShader("mask", BitmapShader(m, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        // Linear filtering matters: nearest sampling turns the 8-bit height map's slope into a checkerboard.
        s.setInputShader("height", BitmapShader(h, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        })
        s.setFloatUniform("res", width.toFloat(), height.toFloat())
        s.setFloatUniform("time", animTime)
        s.setFloatUniform("base", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f)
        s.setFloatUniform("dark", if (night) 1f else 0f)
        s.setFloatUniform("stepPx", step)
        s.setFloatUniform("slope", SLOPE * blurRadius() / step)
        paint.shader = s
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        return true
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun shader(): RuntimeShader? {
        (shader as? RuntimeShader)?.let { return it }
        if (shaderFailed) return null
        val s = runCatching { RuntimeShader(LIQUID_MARK_AGSL) }.getOrNull()
        if (s == null) {
            shaderFailed = true
            stopTicking()
            return null
        }
        shader = s
        return s
    }

    private fun blurRadius(): Float = max(2f, min(width, height) * BLUR_FRACTION)

    /** Crisp mask and height map (the mask blurred), both at view size. Rebuilt only on size or drawable change. */
    private fun surface(d: Drawable): Pair<Bitmap, Bitmap>? {
        val key = "$width:$height:${System.identityHashCode(d)}"
        val m0 = maskMap
        val h0 = heightMap
        if (key == surfaceKey && m0 != null && h0 != null) return m0 to h0
        val m = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(m).apply {
            translate(paddingLeft.toFloat(), paddingTop.toFloat())
            imageMatrix?.let { concat(it) }
            d.draw(this)
        }
        val h = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val alpha = m.extractAlpha()
        Canvas(h).drawBitmap(alpha, 0f, 0f, Paint().apply {
            color = Color.WHITE
            maskFilter = BlurMaskFilter(blurRadius(), BlurMaskFilter.Blur.NORMAL)
        })
        alpha.recycle()
        maskMap = m
        heightMap = h
        surfaceKey = key
        return m to h
    }

    companion object {
        private const val FRAME_MS = 50L
        private const val IDLE_POLL_MS = 2000L
        private const val START_TIME = 2.4f
        /** Height-map blur as a fraction of the view: how far in from the edge the glass curves. */
        private const val BLUR_FRACTION = 0.03f
        /** Edge tilt: about 1.4 means the rim leans roughly 55 degrees toward the viewer. */
        private const val SLOPE = 1.4f

        const val LIQUID_MARK_AGSL = """
            uniform shader mask;
            uniform shader height;
            uniform float2 res;
            uniform float time;
            uniform float3 base;
            uniform float dark;
            uniform float stepPx;
            uniform float slope;

            float hash(float2 p) {
                float3 p3 = fract(float3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }
            float vnoise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                float2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
                           mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
            }
            float fbm(float2 p) {
                float s = 0.0;
                float a = 0.5;
                for (int k = 0; k < 3; k++) {
                    s += a * vnoise(p);
                    p = p * 2.03 + float2(17.0, 9.0);
                    a *= 0.5;
                }
                return s;
            }

            half4 main(float2 c) {
                float a = mask.eval(c).a;
                if (a <= 0.0) return half4(0.0);
                float2 uv = c / res.y;
                float t = time;

                // Surface: slope of the blurred mask, plus a slow wobble so it moves like liquid.
                float hx = height.eval(c + float2(stepPx, 0.0)).a - height.eval(c - float2(stepPx, 0.0)).a;
                float hy = height.eval(c + float2(0.0, stepPx)).a - height.eval(c - float2(0.0, stepPx)).a;
                float2 wob = float2(vnoise(uv * 4.0 + float2(t * 0.32, 0.0)),
                                    vnoise(uv * 4.0 + float2(3.7, 7.1 - t * 0.27))) - 0.5;
                float3 n = normalize(float3(-hx * slope + wob.x * 0.22, -hy * slope + wob.y * 0.22, 1.0));

                // Smoke inside, seen through the surface (refraction shifts it along the normal).
                float2 p = uv * 2.4 + n.xy * 0.35;
                float2 q = float2(fbm(p + float2(0.0, t * 0.07)), fbm(p + float2(5.2, 1.3) - t * 0.06));
                float smoke = fbm(p + 2.4 * q + float2(1.7, 9.2) + t * 0.035);

                // Light circles the upper left; a glint crosses the mark every 9 s.
                float la = t * 0.22;
                float3 L = normalize(float3(-0.55 + 0.35 * cos(la), -0.62 + 0.3 * sin(la), 0.75));
                float3 H = normalize(L + float3(0.0, 0.0, 1.0));
                float spec = pow(max(dot(n, H), 0.0), 42.0);
                float rim = clamp(pow(1.0 - n.z, 1.1) * 2.8, 0.0, 1.0);
                float lit = clamp(dot(n.xy, -L.xy) * 1.4 + 0.25, 0.0, 1.0);
                float sweep = fract(t / 9.0) * 2.0 - 0.5;
                float bd = ((c.x / res.x + c.y / res.y) * 0.5 - sweep) / 0.045;
                float band = exp(-bd * bd);

                float3 hi = float3(mix(0.97, 0.80, dark));
                float3 lo = float3(mix(0.34, 0.03, dark));
                float3 col = base;
                col = mix(col, lo, clamp((0.60 - smoke) * 1.4, 0.0, 0.65));
                col = mix(col, hi, clamp((smoke - 0.56) * 1.1, 0.0, 0.22));
                col = mix(col, hi, rim * mix(0.18, 0.8, lit));
                col = mix(col, lo, rim * (1.0 - lit) * 0.55);
                col += spec * 0.5 + band * (0.05 + 0.18 * rim);
                col = clamp(col, 0.0, 1.0);
                return half4(half3(col) * half(a), half(a));
            }
        """
    }
}
