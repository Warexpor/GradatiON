package io.github.stardomains3.oxproxion

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.annotation.RequiresApi
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos

/**
 * Ambient background behind content (Settings > Appearance > Background). Strictly neutral:
 * every generated style is a signed gray field drawn as white or black at a few percent alpha,
 * so it reads on either theme's canvas and never adds a hue.
 *
 * Styles ([Style]):
 * - DRIFT: a slow mesh of soft light and dark blobs drifting (~12 fps).
 * - FLOW: domain-warped noise, like slow smoke (~14 fps).
 * - ADAPTIVE: Drift in Chat, Flow in Roleplay, over a soft vertical light whose strength and
 *   side follow the phone wallpaper's brightness; calmer (dimmer, slower) in the evening.
 * - PHOTO: the user's own picture ([BackgroundPhoto]), grayscale unless "Keep color", with
 *   optional blur, dim toward the canvas, a legibility tint at the top and bottom, and an
 *   optional animated liquid (Flow) layer. Static unless liquid is on.
 *
 * Usage: place it full-size behind the transcript (inside the glass backdrop is fine). It
 * reads the preference when attached and follows changes live. Tell it the mode with
 * [mode] and call [setScrolling] from the list's scroll state to freeze it while scrolling.
 *
 * Cost (API 33+): each animated frame is one AGSL pass at 1/4 resolution into a tiny layer
 * that is upscaled (the fields are smooth). It only invalidates itself; nothing else in the
 * tree redraws. When it sits inside a [GlassBackdropLayout], each frame also refreshes the
 * glass sampling it (RenderThread only), which is why frame rates are 12-14 fps. It holds a
 * static frame (no callbacks at all) when hidden, backgrounded, scrolling, with animations
 * off, or in battery saver / low-RAM ([GlassQuality] SOLID). API 31-32 get a static
 * pre-rendered field. The photo is decoded, cropped, grayed and blurred once, off the main
 * thread, at half the view's size.
 */
class AmbientBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Style(val key: String, val frameMs: Long) {
        OFF("off", 0L),
        DRIFT("drift", 83L),
        FLOW("flow", 71L),
        ADAPTIVE("adaptive", 0L),
        PHOTO("photo", 71L);

        companion object {
            /** Grain was retired; anyone who had it gets Drift. */
            fun fromKey(key: String?): Style =
                if (key == "grain") DRIFT else entries.find { it.key == key } ?: OFF
        }
    }

    /** Chat vs Roleplay; ADAPTIVE picks Drift for Chat and Flow for Roleplay. */
    var mode: ChatMode = ChatMode.ASK
        set(value) {
            if (field == value) return
            field = value
            retune()
        }

    /** Force a style (previews); null follows the preference. */
    /**
     * A roleplay character's own wallpaper ([BackgroundPhoto.slotForCharacter]). While that slot
     * holds a picture it replaces the app background, with the same photo options.
     */
    var photoSlot: String? = null
        set(value) {
            if (field == value) return
            field = value
            retune()
        }

    private fun activeSlot(): String? = photoSlot?.takeIf { BackgroundPhoto.hasPhoto(context, it) }

    var styleOverride: Style? = null
        set(value) {
            field = value
            retune()
        }

    /** False draws a single static frame. */
    var animated: Boolean = true
        set(value) {
            field = value
            updateTicking()
        }

    /** Strength multiplier on the tuned defaults. */
    var intensity: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 3f)
            retune()
        }

    private var scrolling = false

    /** Freeze (hold the current frame) while the content above is scrolling. */
    fun setScrolling(scrolling: Boolean) {
        if (this.scrolling == scrolling) return
        this.scrolling = scrolling
        updateTicking()
    }

    /** The style actually drawn right now (ADAPTIVE resolved). Visible for tests. */
    val resolvedStyle: Style get() = tuning.style

    private class Tuning(
        val style: Style,
        /** Field drawn on top (DRIFT/FLOW), or null for none. */
        val field: Style?,
        val fieldAmp: Float,
        val speed: Float,
        val frameMs: Long,
        /** ADAPTIVE: signed strength of the vertical light (+ top light, - bottom shade). */
        val horizon: Float = 0f
    )

    private var prefStyle = Style.OFF
    private var tuning = Tuning(Style.OFF, null, 0f, 0f, 0L)
    private var tunedAtHour = -1
    private var photoOptions = BackgroundPhoto.Options()
    private var wallpaperLuma: Float? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint()
    private val fieldPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val washPaint = Paint()
    private var ticking = false
    private var windowVisible = true
    private var animTime = 7.5f // seconds of animation; starts mid-flow so the first frame isn't bland
    private var lastTick = 0L

    // API 33+ (kept untyped so the class loads on 31-32).
    private var fieldShader: Any? = null
    private var fieldShaderStyle: Style? = null
    private var fieldNode: RenderNode? = null

    // API 31-32 / software canvases.
    private var staticField: Bitmap? = null
    private var staticFieldKey = ""

    // PHOTO: processed bitmap, keyed by size + options + file version.
    private var photo: Bitmap? = null
    private var photoKey = ""
    private var photoLoading = ""

    private val prefs: SharedPreferences? =
        if (isInEditMode) null else context.getSharedPreferences(SharedPreferencesHelper.MAIN_PREFS, Context.MODE_PRIVATE)
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SharedPreferencesHelper.KEY_BACKGROUND_STYLE || key in BackgroundPhoto.PREF_KEYS ||
            key?.startsWith(BackgroundPhoto.KEY_VERSION) == true
        ) readPref()
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            val now = SystemClock.uptimeMillis()
            if (canAnimate()) {
                animTime += min(0.25f, (now - lastTick) / 1000f) * tuning.speed
                if (tunedAtHour != Calendar.getInstance().get(Calendar.HOUR_OF_DAY) && prefOrOverride() == Style.ADAPTIVE) retune()
                invalidate()
                lastTick = now
                Choreographer.getInstance().postFrameCallbackDelayed(this, tuning.frameMs)
            } else {
                // Battery saver or animations off: hold still, look again in a while.
                lastTick = now
                Choreographer.getInstance().postFrameCallbackDelayed(this, IDLE_POLL_MS)
            }
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        GlassQuality.init(context)
        val a = context.obtainStyledAttributes(attrs, R.styleable.AmbientBackgroundView)
        try {
            if (a.hasValue(R.styleable.AmbientBackgroundView_ambientStyle)) {
                styleOverride = Style.entries[a.getInt(R.styleable.AmbientBackgroundView_ambientStyle, 0)]
            }
            animated = a.getBoolean(R.styleable.AmbientBackgroundView_ambientAnimated, true)
            intensity = a.getFloat(R.styleable.AmbientBackgroundView_ambientIntensity, 1f)
        } finally {
            a.recycle()
        }
    }

    /** No content size of its own: fills what it is given, never stretches a wrap_content parent. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(0, widthMeasureSpec), resolveSize(0, heightMeasureSpec))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs?.registerOnSharedPreferenceChangeListener(prefListener)
        wallpaperLuma = if (isInEditMode) null else BackgroundPhoto.systemWallpaperLuma(context)
        readPref()
    }

    override fun onDetachedFromWindow() {
        prefs?.unregisterOnSharedPreferenceChangeListener(prefListener)
        stopTicking()
        fieldNode?.discardDisplayList()
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

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        retune()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestPhoto()
        invalidate()
    }

    private fun readPref() {
        prefStyle = Style.fromKey(prefs?.getString(SharedPreferencesHelper.KEY_BACKGROUND_STYLE, Style.OFF.key))
        photoOptions = prefs?.let { BackgroundPhoto.readOptions(it) } ?: BackgroundPhoto.Options()
        retune()
    }

    private fun prefOrOverride(): Style = styleOverride ?: if (activeSlot() != null) Style.PHOTO else prefStyle

    private fun retune() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        tunedAtHour = hour
        tuning = tune(prefOrOverride(), mode, isNight(), hour, intensity, photoOptions, wallpaperLuma)
        if (tuning.style == Style.OFF) {
            fieldNode?.discardDisplayList()
            staticField = null
        }
        if (tuning.style != Style.PHOTO) {
            photo = null
            photoKey = ""
        } else {
            requestPhoto()
        }
        updateTicking()
        invalidate()
    }

    private fun isNight(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun canAnimate(): Boolean =
        Motion.areAnimationsEnabled(context) && GlassQuality.level != GlassQuality.Level.SOLID

    private fun updateTicking() {
        val run = tuning.field != null && tuning.frameMs > 0 && animated && !scrolling &&
            isAttachedToWindow && windowVisible && isShown
        if (run == ticking) return
        if (run) {
            ticking = true
            lastTick = SystemClock.uptimeMillis()
            Choreographer.getInstance().postFrameCallbackDelayed(frame, tuning.frameMs)
        } else {
            stopTicking()
        }
    }

    private fun stopTicking() {
        ticking = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    // ── Drawing ─────────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val t = tuning
        if (t.style == Style.OFF || width == 0 || height == 0) return
        if (t.style == Style.PHOTO) drawPhoto(canvas)
        if (t.horizon != 0f) drawHorizon(canvas, t.horizon)
        val field = t.field
        if (field != null && t.fieldAmp > 0f) {
            val hw = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canvas.isHardwareAccelerated
            if (!(hw && drawFieldAgsl(canvas, field, t.fieldAmp))) drawFieldStatic(canvas, field, t.fieldAmp)
        }
    }

    /** ADAPTIVE: a soft neutral light from the top (bright wallpaper) or shade at the bottom. */
    private fun drawHorizon(canvas: Canvas, strength: Float) {
        val a = (abs(strength) * 255).roundToInt().coerceIn(0, 255)
        val tone = if (isNight()) Color.WHITE else Color.BLACK
        val c = Color.argb(a, Color.red(tone), Color.green(tone), Color.blue(tone))
        val h = height.toFloat()
        washPaint.shader = if (strength > 0f) {
            LinearGradient(0f, 0f, 0f, h * 0.7f, c, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, h * 0.3f, 0f, h, Color.TRANSPARENT, c, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), h, washPaint)
        washPaint.shader = null
    }

    private fun photoKeyNow(): String =
        "${activeSlot()}:${BackgroundPhoto.version(context, activeSlot())}:${max(1, width / 2)}:${max(1, height / 2)}:" +
            "${photoOptions.blur}:${photoOptions.color}"

    /** Start decoding as soon as size and options are known, not on the first draw. */
    private fun requestPhoto() {
        if (tuning.style != Style.PHOTO || width == 0 || height == 0 || isInEditMode) return
        val key = photoKeyNow()
        if (photoKey == key || photoLoading == key) return
        photoLoading = key
        BackgroundPhoto.loadAsync(context, max(1, width / 2), max(1, height / 2), photoOptions, activeSlot()) { loaded ->
            if (photoLoading != key) return@loadAsync
            photoLoading = ""
            photo = loaded
            photoKey = key
            invalidate()
        }
    }

    private fun drawPhoto(canvas: Canvas) {
        val opts = photoOptions
        val canvasColor = context.getColor(R.color.xai_canvas)
        requestPhoto()
        val bmp = photo
        if (bmp != null) {
            canvas.save()
            canvas.scale(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            canvas.drawBitmap(bmp, 0f, 0f, photoPaint)
            canvas.restore()
        }
        // Dim: wash toward the canvas so text keeps its contrast.
        if (opts.dim) {
            washPaint.color = canvasColor
            washPaint.alpha = if (isNight()) 130 else 120
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), washPaint)
        }
        // Tint: canvas-toned fades behind the top bar and the composer.
        if (opts.tint) {
            val h = height.toFloat()
            val solid = Color.argb(170, Color.red(canvasColor), Color.green(canvasColor), Color.blue(canvasColor))
            washPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(solid, Color.TRANSPARENT, Color.TRANSPARENT, solid),
                floatArrayOf(0f, 0.22f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
            washPaint.alpha = 255
            canvas.drawRect(0f, 0f, width.toFloat(), h, washPaint)
            washPaint.shader = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawFieldAgsl(canvas: Canvas, style: Style, amp: Float): Boolean {
        val shader = fieldShaderFor(style) ?: return false
        val fw = max(1, ceil(width / FIELD_DOWNSCALE).toInt())
        val fh = max(1, ceil(height / FIELD_DOWNSCALE).toInt())
        shader.setFloatUniform("res", fw.toFloat(), fh.toFloat())
        shader.setFloatUniform("time", animTime)
        shader.setFloatUniform("amp", amp)
        shader.setFloatUniform("polarity", if (isNight()) 1f else -1f)
        fieldPaint.shader = shader
        // Smooth fields: shade a quarter-resolution layer, upscale it with filtering.
        val node = fieldNode ?: RenderNode("ambientField").also {
            it.setUseCompositingLayer(true, null)
            fieldNode = it
        }
        node.setPosition(0, 0, fw, fh)
        val rc = node.beginRecording(fw, fh)
        try {
            rc.drawRect(0f, 0f, fw.toFloat(), fh.toFloat(), fieldPaint)
        } finally {
            node.endRecording()
        }
        canvas.save()
        canvas.scale(width / fw.toFloat(), height / fh.toFloat())
        canvas.drawRenderNode(node)
        canvas.restore()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun fieldShaderFor(style: Style): RuntimeShader? {
        if (fieldShaderStyle == style) (fieldShader as? RuntimeShader)?.let { return it }
        val src = when (style) {
            Style.DRIFT -> DRIFT_AGSL
            Style.FLOW -> FLOW_AGSL
            else -> return null
        }
        // A driver that can't compile AGSL gets the static field instead.
        val s = runCatching { RuntimeShader(src) }.getOrNull() ?: return null
        fieldShader = s
        fieldShaderStyle = style
        return s
    }

    /** API 31-32 and software canvases: the same field evaluated once on the CPU, small, upscaled. */
    private fun drawFieldStatic(canvas: Canvas, style: Style, amp: Float) {
        val fw = max(8, (width / STATIC_DOWNSCALE).roundToInt())
        val fh = max(8, (height / STATIC_DOWNSCALE).roundToInt())
        val key = "$style:$fw:$fh:$amp:${isNight()}"
        val bmp = staticField?.takeIf { staticFieldKey == key } ?: renderFieldCpu(style, fw, fh, amp, if (isNight()) 1f else -1f).also {
            staticField = it
            staticFieldKey = key
        }
        canvas.save()
        canvas.scale(width / fw.toFloat(), height / fh.toFloat())
        canvas.drawBitmap(bmp, 0f, 0f, fieldPaint)
        canvas.restore()
    }

    companion object {
        private const val IDLE_POLL_MS = 2000L
        private const val FIELD_DOWNSCALE = 4f
        private const val STATIC_DOWNSCALE = 8f
        /** Fixed moment the static fallback shows. */
        private const val STATIC_TIME = 7.5f

        private fun tune(
            style: Style,
            mode: ChatMode,
            night: Boolean,
            hour: Int,
            k: Float,
            photo: BackgroundPhoto.Options,
            wallpaperLuma: Float?
        ): Tuning {
            // Light canvases show the same alpha more strongly, so they get a little less.
            val th = if (night) 1f else 0.8f
            return when (style) {
                Style.OFF -> Tuning(Style.OFF, null, 0f, 0f, 0L)
                Style.DRIFT -> Tuning(Style.DRIFT, Style.DRIFT, 0.075f * th * k, 1f, Style.DRIFT.frameMs)
                Style.FLOW -> Tuning(Style.FLOW, Style.FLOW, 0.09f * th * k, 1f, Style.FLOW.frameMs)
                Style.PHOTO -> if (photo.liquid) {
                    Tuning(Style.PHOTO, Style.FLOW, 0.07f * th * k, 0.8f, Style.PHOTO.frameMs)
                } else {
                    Tuning(Style.PHOTO, null, 0f, 0f, 0L)
                }
                Style.ADAPTIVE -> {
                    val base = if (mode == ChatMode.RP) Style.FLOW else Style.DRIFT
                    // Calmer after dark: dimmer and slower late in the day and overnight.
                    val calm = when (hour) {
                        in 22..23, in 0..5 -> 0.6f
                        in 19..21 -> 0.8f
                        else -> 1f
                    }
                    // Wallpaper brightness: a bright one lights the top, a dark one shades the
                    // bottom; a busy mid-tone gets a slightly livelier field. Unknown = neutral.
                    val l = wallpaperLuma ?: 0.5f
                    val horizon = ((l - 0.5f) * 0.16f * th * k * calm).coerceIn(-0.08f, 0.08f)
                    val lively = 1f + 0.35f * (1f - abs(l - 0.5f) * 2f)
                    val fieldAmp = (if (base == Style.FLOW) 0.08f else 0.07f) * th * k * calm * lively
                    val speed = if (calm < 1f) calm * 0.8f else 1f
                    Tuning(base, base, fieldAmp, speed, base.frameMs + if (calm < 1f) 40L else 0L, horizon)
                }
            }
        }

        // ── Shaders: signed gray field → white or black at a few percent (premultiplied). ──

        private const val HASH = """
            float hash(float2 p) {
                float3 p3 = fract(float3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }
        """

        private const val SIGNED_OUT = """
            half4 signedGray(float v, float amp, float2 c) {
                // Tiny dither so low-alpha gradients don't band.
                float a = clamp(abs(v) * amp + (hash(c + time) - 0.5) / 255.0, 0.0, 1.0);
                half l = v > 0.0 ? 1.0 : 0.0;
                return half4(half3(l * a), half(a));
            }
        """

        /** Soft light and dark blobs on slow Lissajous paths. */
        const val DRIFT_AGSL = """
            uniform float2 res;
            uniform float time;
            uniform float amp;
            uniform float polarity;
            $HASH
            $SIGNED_OUT
            float blob(float2 uv, float2 p, float r) {
                float2 d = uv - p;
                return exp(-dot(d, d) / (r * r));
            }
            half4 main(float2 c) {
                float ax = res.x / res.y;
                float2 uv = c / res.y;
                float t = time;
                float v = 0.0;
                v += 1.00 * blob(uv, float2(ax * (0.28 + 0.20 * sin(t * 0.052)), 0.24 + 0.14 * cos(t * 0.043)), 0.34);
                v += 0.75 * blob(uv, float2(ax * (0.76 + 0.18 * cos(t * 0.037)), 0.46 + 0.20 * sin(t * 0.031 + 1.3)), 0.38);
                v += 0.90 * blob(uv, float2(ax * (0.50 + 0.26 * sin(t * 0.029 + 2.1)), 0.80 + 0.12 * cos(t * 0.047)), 0.30);
                v += 0.60 * blob(uv, float2(ax * (0.18 + 0.14 * cos(t * 0.041 + 0.7)), 0.66 + 0.16 * sin(t * 0.035)), 0.26);
                v += 0.55 * blob(uv, float2(ax * (0.86 + 0.10 * sin(t * 0.058 + 4.0)), 0.10 + 0.08 * cos(t * 0.05)), 0.22);
                // Mostly light on dark canvases, mostly shade on light ones (polarity).
                return signedGray(polarity * clamp(v - 0.18, -1.0, 1.0), amp, c);
            }
        """

        /** Domain-warped value noise: slow smoke. */
        const val FLOW_AGSL = """
            uniform float2 res;
            uniform float time;
            uniform float amp;
            uniform float polarity;
            $HASH
            $SIGNED_OUT
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
                for (int k = 0; k < 4; k++) {
                    s += a * vnoise(p);
                    p = p * 2.03 + float2(17.0, 9.0);
                    a *= 0.5;
                }
                return s;
            }
            half4 main(float2 c) {
                float2 p = c / res.y * 2.4;
                float t = time * 0.045;
                float2 q = float2(fbm(p + float2(0.0, t)), fbm(p + float2(5.2, 1.3) - t));
                float2 r = float2(fbm(p + 3.0 * q + float2(1.7, 9.2) + 0.6 * t),
                                  fbm(p + 3.0 * q + float2(8.3, 2.8) - 0.4 * t));
                float v = (fbm(p + 2.5 * r) - 0.44) * 2.8;
                return signedGray(polarity * clamp(v, -1.0, 1.0), amp, c);
            }
        """

        // ── CPU twins of the shaders for the static fallback ──

        private fun hash(x: Float, y: Float): Float {
            var p0 = frac(x * 0.1031f); var p1 = frac(y * 0.1031f); var p2 = frac(x * 0.1031f)
            val d = p0 * (p1 + 33.33f) + p1 * (p2 + 33.33f) + p2 * (p0 + 33.33f)
            p0 += d; p1 += d; p2 += d
            return frac((p0 + p1) * p2)
        }

        private fun frac(v: Float) = v - floor(v)

        private fun vnoise(x: Float, y: Float): Float {
            val ix = floor(x); val iy = floor(y)
            val fx = x - ix; val fy = y - iy
            val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy)
            val a = hash(ix, iy); val b = hash(ix + 1, iy)
            val c = hash(ix, iy + 1); val d = hash(ix + 1, iy + 1)
            return (a + (b - a) * ux) + ((c + (d - c) * ux) - (a + (b - a) * ux)) * uy
        }

        private fun fbm(x0: Float, y0: Float): Float {
            var x = x0; var y = y0; var s = 0f; var a = 0.5f
            repeat(4) {
                s += a * vnoise(x, y)
                x = x * 2.03f + 17f; y = y * 2.03f + 9f
                a *= 0.5f
            }
            return s
        }

        private fun blob(u: Float, v: Float, px: Float, py: Float, r: Float): Float {
            val dx = u - px; val dy = v - py
            return exp(-(dx * dx + dy * dy) / (r * r))
        }

        private fun renderFieldCpu(style: Style, w: Int, h: Int, amp: Float, polarity: Float): Bitmap {
            val px = IntArray(w * h)
            val ax = w / h.toFloat()
            val t = STATIC_TIME
            for (j in 0 until h) for (i in 0 until w) {
                val u = i / h.toFloat(); val v = j / h.toFloat()
                val f = if (style == Style.FLOW) {
                    val p0 = u * 2.4f; val p1 = v * 2.4f; val tt = t * 0.045f
                    val q0 = fbm(p0, p1 + tt); val q1 = fbm(p0 + 5.2f - tt, p1 + 1.3f - tt)
                    val r0 = fbm(p0 + 3 * q0 + 1.7f + 0.6f * tt, p1 + 3 * q1 + 9.2f + 0.6f * tt)
                    val r1 = fbm(p0 + 3 * q0 + 8.3f - 0.4f * tt, p1 + 3 * q1 + 2.8f - 0.4f * tt)
                    (fbm(p0 + 2.5f * r0, p1 + 2.5f * r1) - 0.44f) * 2.8f
                } else {
                    blob(u, v, ax * (0.28f + 0.20f * sin(t * 0.052f)), 0.24f + 0.14f * cos(t * 0.043f), 0.34f) +
                        0.75f * blob(u, v, ax * (0.76f + 0.18f * cos(t * 0.037f)), 0.46f + 0.20f * sin(t * 0.031f + 1.3f), 0.38f) +
                        0.90f * blob(u, v, ax * (0.50f + 0.26f * sin(t * 0.029f + 2.1f)), 0.80f + 0.12f * cos(t * 0.047f), 0.30f) +
                        0.60f * blob(u, v, ax * (0.18f + 0.14f * cos(t * 0.041f + 0.7f)), 0.66f + 0.16f * sin(t * 0.035f), 0.26f) +
                        0.55f * blob(u, v, ax * (0.86f + 0.10f * sin(t * 0.058f + 4.0f)), 0.10f + 0.08f * cos(t * 0.05f), 0.22f) - 0.18f
                }.coerceIn(-1f, 1f) * polarity
                val a = (abs(f) * amp * 255f).roundToInt().coerceIn(0, 255)
                val l = if (f > 0f) 255 else 0
                px[j * w + i] = (a shl 24) or (l shl 16) or (l shl 8) or l
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
