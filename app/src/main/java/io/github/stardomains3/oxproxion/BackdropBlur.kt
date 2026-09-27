package io.github.stardomains3.oxproxion

import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.PopupWindow
import android.view.WindowManager

/**
 * Frosts the screen behind a floating menu (iOS context menus), so the menu reads as lifted off
 * the page instead of sitting on a see-through dim. In-tree menus blur the views they cover with
 * a [RenderEffect]; menus in their own window ([PopupWindow]) blur behind the window. One blur
 * layer while a menu is open, none otherwise. Where [GlassQuality] says no live blur (battery
 * saver, low-RAM phones, Android 11 and older) the menus keep their plain dim.
 */
object BackdropBlur {

    private const val RADIUS_DP = 16f

    private fun available(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && GlassQuality.level != GlassQuality.Level.SOLID

    /** Blur (or un-blur) [views] in place; safe to call again mid-animation. */
    fun set(views: Collection<View>, on: Boolean, animate: Boolean = true) {
        if (!available()) return
        views.forEach { v ->
            (v.getTag(R.id.tag_backdrop_blur_anim) as? ValueAnimator)?.cancel()
            val from = v.getTag(R.id.tag_backdrop_blur_radius) as? Float ?: 0f
            val to = if (on) RADIUS_DP * v.resources.displayMetrics.density else 0f
            if (!animate || !Motion.areAnimationsEnabled(v.context) || !v.isAttachedToWindow) {
                apply(v, to)
                return@forEach
            }
            val anim = ValueAnimator.ofFloat(from, to).apply {
                duration = if (on) 220L else 160L
                interpolator = if (on) Motion.iosOut else Motion.iosIn
                addUpdateListener { apply(v, it.animatedValue as Float) }
                start()
            }
            v.setTag(R.id.tag_backdrop_blur_anim, anim)
        }
    }

    private fun apply(v: View, radius: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        v.setTag(R.id.tag_backdrop_blur_radius, radius)
        v.setRenderEffect(
            if (radius < 0.5f) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        )
    }

    /**
     * Call right after [PopupWindow.showAsDropDown]/showAtLocation: blurs and dims everything
     * behind the popup's window, replacing a hand-made dim view.
     */
    fun behind(popup: PopupWindow) {
        val decor = popup.contentView?.rootView ?: return
        val lp = decor.layoutParams as? WindowManager.LayoutParams ?: return
        val wm = decor.context.getSystemService(WindowManager::class.java) ?: return
        val blur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled &&
            GlassQuality.level != GlassQuality.Level.SOLID
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        lp.dimAmount = if (blur) 0.18f else 0.4f
        if (blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = (RADIUS_DP * decor.resources.displayMetrics.density).toInt()
        }
        runCatching { wm.updateViewLayout(decor, lp) }
    }
}
