package io.github.stardomains3.oxproxion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import kotlin.math.hypot
import kotlin.math.max

/**
 * Animated appearance change. Snapshots the window as it is, switches the night mode (which
 * recreates the activity), then lays the old frame over the new one and opens a circular
 * window in it from the control that was tapped, so the new theme spreads out like ink
 * (~450 ms, iOS curve). A soft bright rim rides the edge of the circle.
 *
 * With animations off (duration scale 0) it switches instantly. Nothing survives the
 * transition: the snapshot is dropped when the reveal ends or if the new activity never shows.
 *
 * Usage: `ThemeTransition.apply(activity, anchorView, AppCompatDelegate.MODE_NIGHT_YES)`.
 */
object ThemeTransition {

    private const val DURATION_MS = 460L
    private const val STALE_MS = 3000L

    private class Pending(val bitmap: Bitmap, val cx: Float, val cy: Float, val from: Activity, val at: Long)

    private var pending: Pending? = null
    private var callbacksRegistered = false
    private val main = Handler(Looper.getMainLooper())
    /** A capture is in flight; ignore repeat taps until the mode is applied. */
    private var capturing = false

    /** Switch to [nightMode] (an AppCompatDelegate MODE_NIGHT_* value), revealing from [anchor]. */
    fun apply(activity: Activity, anchor: View?, nightMode: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == nightMode) return
        val decor = activity.window?.decorView
        if (decor == null || decor.width <= 0 || decor.height <= 0 || !Motion.areAnimationsEnabled(activity)) {
            switch(activity, nightMode)
            return
        }
        if (capturing) return
        capturing = true
        val (cx, cy) = centerOf(anchor, decor)
        capture(activity, decor) { bmp ->
            capturing = false
            if (bmp != null && !activity.isFinishing && !activity.isDestroyed) {
                pending?.bitmap?.recycle()
                pending = Pending(bmp, cx, cy, activity, android.os.SystemClock.uptimeMillis())
                ensureCallbacks(activity.application)
            }
            if (!activity.isDestroyed) switch(activity, nightMode)
        }
    }

    private fun switch(activity: Activity, nightMode: Int) {
        // Same sequence the app always used: AppCompat usually recreates on its own, the
        // explicit recreate covers activities it skipped (a second request is a no-op).
        AppCompatDelegate.setDefaultNightMode(nightMode)
        if (!activity.isFinishing && !activity.isDestroyed) activity.recreate()
    }

    private fun centerOf(anchor: View?, decor: View): Pair<Float, Float> {
        if (anchor == null || !anchor.isAttachedToWindow) return decor.width / 2f to decor.height / 2f
        val a = IntArray(2)
        val d = IntArray(2)
        anchor.getLocationInWindow(a)
        decor.getLocationInWindow(d)
        return (a[0] - d[0] + anchor.width / 2f) to (a[1] - d[1] + anchor.height / 2f)
    }

    /** Exact pixels via PixelCopy (hardware content, glass included); software draw as fallback. */
    private fun capture(activity: Activity, decor: View, done: (Bitmap?) -> Unit) {
        val bmp = runCatching { Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888) }.getOrNull()
            ?: return done(null)
        val window = activity.window
        val fallback = {
            val ok = runCatching { decor.draw(Canvas(bmp)) }.isSuccess
            done(if (ok) bmp else { bmp.recycle(); null })
        }
        try {
            PixelCopy.request(window, bmp, { result ->
                if (result == PixelCopy.SUCCESS) done(bmp) else fallback()
            }, main)
        } catch (_: Throwable) {
            fallback()
        }
    }

    private fun ensureCallbacks(app: Application) {
        if (callbacksRegistered) return
        callbacksRegistered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val p = pending ?: return
                if (activity === p.from) return
                pending = null
                if (android.os.SystemClock.uptimeMillis() - p.at > STALE_MS) { p.bitmap.recycle(); return }
                val decor = activity.window?.decorView as? ViewGroup
                if (decor == null) { p.bitmap.recycle(); return }
                val overlay = RevealOverlay(activity, p.bitmap, p.cx, p.cy)
                decor.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                overlay.start()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * The old frame with a growing circular hole. Clip-out on a hardware canvas: one bitmap
     * draw per frame, no offscreen layers.
     */
    private class RevealOverlay(
        context: Context,
        private val old: Bitmap,
        private val cx: Float,
        private val cy: Float
    ) : View(context) {

        private val path = Path()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var radius = 0f
        private var progress = 0f
        private var animator: ValueAnimator? = null
        private val density = resources.displayMetrics.density

        init {
            isClickable = true // swallow taps while the reveal runs
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun start() {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DURATION_MS
                interpolator = Motion.iosPush
                startDelay = 40L // let the new frame settle under the snapshot first
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = finish()
                    override fun onAnimationCancel(animation: Animator) = finish()
                })
                start()
            }
        }

        private fun finish() {
            (parent as? ViewGroup)?.removeView(this)
            if (!old.isRecycled) old.recycle()
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            super.onDetachedFromWindow()
            if (!old.isRecycled) old.recycle()
        }

        override fun onDraw(canvas: Canvas) {
            if (old.isRecycled) return
            val far = maxOf(
                hypot(cx, cy), hypot(width - cx, cy),
                hypot(cx, height - cy), hypot(width - cx, height - cy)
            )
            radius = far * progress
            canvas.save()
            if (radius > 0.5f) {
                path.rewind()
                path.addCircle(cx, cy, radius, Path.Direction.CW)
                canvas.clipOutPath(path)
            }
            canvas.drawBitmap(old, 0f, 0f, paint)
            canvas.restore()
            // A soft light rim riding the edge, fading as it spreads.
            if (radius > 1f && progress < 1f) {
                val band = 26f * density
                val inner = max(0f, radius - band)
                val a = ((1f - progress) * 0.55f * 255).toInt()
                rimPaint.shader = RadialGradient(
                    cx, cy, radius,
                    intArrayOf(Color.TRANSPARENT, Color.argb(a, 255, 255, 255), Color.TRANSPARENT),
                    floatArrayOf(inner / radius, 0.985f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, radius, rimPaint)
            }
        }
    }
}
