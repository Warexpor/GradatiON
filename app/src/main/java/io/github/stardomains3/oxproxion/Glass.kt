package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import kotlin.math.max
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
}

/**
 * iOS-style material: live blur of [GlassBackdropLayout] content behind the view, a light
 * saturation lift, a translucent tint, and a hairline edge. Hardware canvases blur on the
 * RenderThread via [RenderEffect]; software canvases (screenshots, bitmap captures) fall back
 * to a small CPU blur of a downscaled snapshot so the look is the same.
 */
class GlassMaterial(private val host: View, attrs: AttributeSet?) {

    private val density = host.resources.displayMetrics.density

    var source: GlassBackdropLayout? = null
        set(value) {
            field = value
            host.invalidate()
        }
    var cornerRadius: Float = 0f
        set(value) {
            field = value
            pathDirty = true
            host.invalidate()
        }
    var tintColor: Int = ContextCompat.getColor(host.context, R.color.glass_tint)
        set(value) {
            field = value
            tintPaint.color = value
            host.invalidate()
        }
    /** 0..1: how much of the hairline shows (the top bar fades its edge in as content scrolls under). */
    var edgeAlpha: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v == field) return
            field = v
            host.invalidate()
        }
    /** Draw the hairline only along the bottom edge (bars) instead of all around (cards). */
    var bottomEdgeOnly: Boolean = false

    private var blurRadiusPx = 26f * density
    private val blurNode = RenderNode("glass")
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tintColor }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density * 0.75f)
        color = ContextCompat.getColor(host.context, R.color.glass_edge)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density * 0.75f)
        color = ContextCompat.getColor(host.context, R.color.glass_highlight)
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var pathDirty = true
    private var lastW = 0
    private var lastH = 0
    private val hostLoc = IntArray(2)
    private val srcLoc = IntArray(2)
    private var lastDx = Int.MIN_VALUE
    private var lastDy = Int.MIN_VALUE

    private val positionWatcher = ViewTreeObserver.OnPreDrawListener {
        // Glass that moves (sheets, animated panels) must re-sample what is behind it.
        val src = source
        if (src != null && host.isShown) {
            host.getLocationInWindow(hostLoc)
            src.getLocationInWindow(srcLoc)
            val dx = hostLoc[0] - srcLoc[0]
            val dy = hostLoc[1] - srcLoc[1]
            if (dx != lastDx || dy != lastDy) host.invalidate()
        }
        true
    }

    init {
        host.setWillNotDraw(false)
        val a = host.context.obtainStyledAttributes(attrs, R.styleable.GlassMaterial)
        try {
            cornerRadius = a.getDimension(R.styleable.GlassMaterial_glassCornerRadius, 0f)
            if (a.hasValue(R.styleable.GlassMaterial_glassTint)) {
                tintColor = a.getColor(R.styleable.GlassMaterial_glassTint, tintColor)
            }
            blurRadiusPx = a.getDimension(R.styleable.GlassMaterial_glassBlurRadius, blurRadiusPx)
            bottomEdgeOnly = a.getBoolean(R.styleable.GlassMaterial_glassBottomEdgeOnly, false)
            edgeAlpha = a.getFloat(R.styleable.GlassMaterial_glassEdgeAlpha, 1f)
        } finally {
            a.recycle()
        }
        val saturate = ColorMatrix().apply { setSaturation(1.6f) }
        blurNode.setRenderEffect(
            RenderEffect.createChainEffect(
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturate)),
                RenderEffect.createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP)
            )
        )
        host.clipToOutline = false
    }

    fun onAttached() {
        host.viewTreeObserver.addOnPreDrawListener(positionWatcher)
    }

    fun onDetached() {
        host.viewTreeObserver.removeOnPreDrawListener(positionWatcher)
    }

    /** Call from the host's draw() before its children and background-less content. */
    fun draw(canvas: Canvas) {
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        if (pathDirty || w != lastW || h != lastH) {
            path.reset()
            rect.set(0f, 0f, w.toFloat(), h.toFloat())
            path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            lastW = w
            lastH = h
            pathDirty = false
        }

        canvas.save()
        canvas.clipPath(path)
        val src = source
        if (src != null && src.isShown) {
            host.getLocationInWindow(hostLoc)
            src.getLocationInWindow(srcLoc)
            lastDx = hostLoc[0] - srcLoc[0]
            lastDy = hostLoc[1] - srcLoc[1]
            if (canvas.isHardwareAccelerated) {
                if (src.hasRecording) drawHardware(canvas, src, w, h)
            } else {
                drawSoftware(canvas, src, w, h)
            }
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), tintPaint)
        canvas.restore()
        drawEdges(canvas, w, h)
    }

    private fun drawHardware(canvas: Canvas, src: GlassBackdropLayout, w: Int, h: Int) {
        blurNode.setPosition(0, 0, w, h)
        val rc = blurNode.beginRecording(w, h)
        try {
            rc.translate(-lastDx.toFloat(), -lastDy.toFloat())
            rc.drawRenderNode(src.contentNode)
        } finally {
            blurNode.endRecording()
        }
        canvas.drawRenderNode(blurNode)
    }

    private fun drawSoftware(canvas: Canvas, src: View, w: Int, h: Int) {
        val scale = SOFTWARE_SCALE
        val bw = max(1, (w * scale).roundToInt())
        val bh = max(1, (h * scale).roundToInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        c.translate(-lastDx.toFloat(), -lastDy.toFloat())
        src.draw(c)
        val radius = max(1, (blurRadiusPx * scale / 2f).roundToInt())
        boxBlur(bmp, radius)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawBitmap(bmp, null, rect, bitmapPaint)
        bmp.recycle()
    }

    private fun drawEdges(canvas: Canvas, w: Int, h: Int) {
        if (edgeAlpha <= 0f) return
        val baseEdge = edgePaint.alpha
        edgePaint.alpha = (baseEdge * edgeAlpha).roundToInt()
        val inset = edgePaint.strokeWidth / 2f
        if (bottomEdgeOnly) {
            canvas.drawLine(0f, h - inset, w.toFloat(), h - inset, edgePaint)
        } else {
            rect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, edgePaint)
            // Faint top specular line, like light catching the rim of a glass pane.
            val r = cornerRadius
            if (w > 2 * r) {
                val hBase = highlightPaint.alpha
                highlightPaint.alpha = (hBase * edgeAlpha).roundToInt()
                canvas.drawLine(r, inset, w - r, inset, highlightPaint)
                highlightPaint.alpha = hBase
            }
        }
        edgePaint.alpha = baseEdge
    }

    private companion object {
        const val SOFTWARE_SCALE = 0.25f

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
