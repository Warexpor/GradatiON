package io.github.stardomains3.oxproxion

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.core.widget.doOnTextChanged
import com.google.android.material.textfield.TextInputLayout

/**
 * A dialog window covers the activity's notice pill, so a refused Save speaks on the field
 * itself. Call once from onViewCreated; the error clears as soon as the user edits.
 */
fun TextInputLayout.clearErrorOnEdit() {
    editText?.doOnTextChanged { _, _, _, _ -> if (error != null) error = null }
}

/**
 * Dialog windows frost what's behind them (cross-window blur, Android 12+) with a light dim,
 * and enter/exit like iOS alerts. Phones that can't blur across windows (many mid-range
 * Samsungs) get the frost inside the card instead: one blurred snapshot of the screen,
 * painted under the card's glass.
 */
object GlassDialogs {
    fun frost(window: Window, animate: Boolean = true) {
        // A dialog reaches here from its builder, its own onViewCreated and GlassChrome's started
        // hook; each extra pass would snapshot and blur the screen again for nothing.
        val decor = window.decorView
        if (decor.getTag(R.id.tag_glass_frosted) == true) return
        decor.setTag(R.id.tag_glass_frosted, true)
        val wm = window.context.getSystemService(WindowManager::class.java)
        val blur = wm?.isCrossWindowBlurEnabled == true
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (blur) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also {
                it.blurBehindRadius = (22 * window.context.resources.displayMetrics.density).toInt()
            }
            window.setDimAmount(DIM_WITH_BLUR)
        } else {
            val frosted = frostCards(window)
            window.setDimAmount(if (frosted) DIM_WITH_CARD_FROST else DIM_NO_BLUR)
        }
        if (animate) window.setWindowAnimations(R.style.Animation_Gradation_Dialog)
    }

    /** False when there's nothing to snapshot or the device can't blur in-app either. */
    private fun frostCards(window: Window): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (GlassQuality.level == GlassQuality.Level.SOLID) return false
        val activity = window.context.activity() ?: return false
        val source = activity.window?.decorView ?: return false
        if (source.width == 0 || source.height == 0 || !source.isHardwareAccelerated) return false
        val shot = snapshot(source) ?: return false
        val origin = IntArray(2).also { source.getLocationOnScreen(it) }
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(this)
                applyTo(decor, GlassDrawable.Frost(shot, FROST_SCALE, decor, origin), origin)
                return true
            }
        })
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                decor.removeOnAttachStateChangeListener(this)
                shot.recycle()
            }
        })
        return true
    }

    /** Every glass card in the dialog (the alert card, a sheet) paints the frost under its tint. */
    private fun applyTo(view: View, template: GlassDrawable.Frost, origin: IntArray) {
        view.background?.let(::glassOf)?.frost = GlassDrawable.Frost(template.bitmap, template.scale, view, origin)
        if (view is ViewGroup) for (i in 0 until view.childCount) applyTo(view.getChildAt(i), template, origin)
    }

    private fun glassOf(d: Drawable): GlassDrawable? = when (d) {
        is GlassDrawable -> d
        is DrawableWrapper -> d.drawable?.let(::glassOf)
        else -> null
    }

    /** The whole activity window, small and blurred, drawn through the GPU so glass comes out as seen. */
    private fun snapshot(source: View): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val w = (source.width * FROST_SCALE).toInt().coerceAtLeast(1)
        val h = (source.height * FROST_SCALE).toInt().coerceAtLeast(1)
        val radius = FROST_RADIUS_DP * source.resources.displayMetrics.density * FROST_SCALE
        return runCatching {
            HardwareRaster.render(w, h, software = false) { canvas ->
                val node = RenderNode("dialogFrost").apply {
                    setPosition(0, 0, w, h)
                    setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                }
                val c = node.beginRecording()
                c.scale(FROST_SCALE, FROST_SCALE)
                source.draw(c)
                node.endRecording()
                canvas.drawRenderNode(node)
            }
        }.getOrNull()
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

    private const val DIM_WITH_BLUR = 0.28f
    private const val DIM_WITH_CARD_FROST = 0.34f
    private const val DIM_NO_BLUR = 0.5f
    /** A quarter-size snapshot is plenty under a blur, and cheap to take on open. */
    private const val FROST_SCALE = 0.25f
    private const val FROST_RADIUS_DP = 22f
}
