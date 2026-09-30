package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Picks which part of a photo becomes the avatar: the picture moves and zooms under a fixed
 * round window, never letting the window see past the photo's edge. [crop] returns exactly what
 * the window shows.
 */
class AvatarCropView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val matrix = Matrix()
    private val window = RectF()
    private val bounds = RectF()
    private val scrim = Path()
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = Color.argb(200, 255, 255, 255)
    }

    private var bitmap: Bitmap? = null
    private var minScale = 1f

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val now = currentScale()
            val next = (now * detector.scaleFactor).coerceIn(minScale, minScale * MAX_ZOOM)
            val factor = next / now
            matrix.postScale(factor, factor, detector.focusX, detector.focusY)
            settle()
            invalidate()
            return true
        }
    })

    private val panner = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            matrix.postTranslate(-dx, -dy)
            settle()
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            reset()
            return true
        }
    })

    init {
        // Nothing in a picture window is worth reading aloud; the buttons around it carry the meaning.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setImage(bmp: Bitmap) {
        bitmap = bmp
        reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val side = min(w, h) - 2 * MARGIN_DP * d
        window.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        scrim.reset()
        scrim.fillType = Path.FillType.EVEN_ODD
        scrim.addRect(bounds, Path.Direction.CW)
        scrim.addCircle(window.centerX(), window.centerY(), side / 2f, Path.Direction.CW)
        reset()
    }

    /** Fills the window with the photo's middle. */
    private fun reset() {
        val bmp = bitmap ?: return
        if (window.isEmpty) return
        minScale = max(window.width() / bmp.width, window.height() / bmp.height)
        matrix.reset()
        matrix.postScale(minScale, minScale)
        matrix.postTranslate(
            window.centerX() - bmp.width * minScale / 2f,
            window.centerY() - bmp.height * minScale / 2f
        )
        invalidate()
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    /** Keeps the photo covering the window on every side. */
    private fun settle() {
        val bmp = bitmap ?: return
        val r = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        matrix.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.left > window.left) dx = window.left - r.left else if (r.right < window.right) dx = window.right - r.right
        if (r.top > window.top) dy = window.top - r.top else if (r.bottom < window.bottom) dy = window.bottom - r.bottom
        matrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(event)
        // A pinch already moves the picture through its focus point; a second finger must not also pan it.
        if (!scaler.isInProgress) panner.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, matrix, photoPaint)
        canvas.drawPath(scrim, scrimPaint)
        canvas.drawCircle(window.centerX(), window.centerY(), window.width() / 2f, ringPaint)
    }

    /** What the window shows, as a square of [size] pixels. Null before an image is set. */
    fun crop(size: Int): Bitmap? {
        val bmp = bitmap ?: return null
        if (window.isEmpty) return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val m = Matrix(matrix)
        m.postTranslate(-window.left, -window.top)
        val k = size / window.width()
        m.postScale(k, k)
        Canvas(out).drawBitmap(bmp, m, photoPaint)
        return out
    }

    private companion object {
        const val MARGIN_DP = 24f
        const val MAX_ZOOM = 6f
    }
}
