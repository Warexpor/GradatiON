package io.github.stardomains3.oxproxion

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.core.content.ContextCompat
import java.util.WeakHashMap

object CopyFeedbackAnimator {
    private val pendingResets = WeakHashMap<ImageView, Runnable>()
    /** The icon to come back to; kept across a second tap while the check still shows. */
    private val originals = WeakHashMap<ImageView, Drawable?>()
    /** Same for the tint: a second tap must not take the check's tint for the button's own. */
    private val originalTints = WeakHashMap<ImageView, ColorStateList?>()

    fun play(button: ImageView) {
        val pending = pendingResets.remove(button)
        pending?.let { button.removeCallbacks(it) }
        if (pending == null) {
            originals[button] = button.drawable
            originalTints[button] = button.imageTintList
        }

        val context = button.context
        val normalTint = originalTints[button]
            ?: ColorStateList.valueOf(ContextCompat.getColor(context, R.color.xai_icon))
        val checkTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.xai_ink))
        val animated = Motion.areAnimationsEnabled(context)

        button.animate().cancel()
        if (!animated) {
            // Animations off: the check just appears, and the copy icon just returns.
            button.scaleX = 1f
            button.scaleY = 1f
            button.alpha = 1f
            button.setImageResource(R.drawable.ic_msg_check)
            button.imageTintList = checkTint
            val restore = Runnable {
                val original = originals.remove(button)
                if (original != null) button.setImageDrawable(original) else button.setImageResource(R.drawable.ic_msg_copy)
                button.imageTintList = normalTint
                pendingResets.remove(button)
            }
            pendingResets[button] = restore
            button.postDelayed(restore, 950L)
            return
        }
        button.animate()
            .scaleX(0.78f)
            .scaleY(0.78f)
            .alpha(0.55f)
            .setDuration(90)
            .withEndAction {
                button.setImageResource(R.drawable.ic_msg_check)
                button.imageTintList = checkTint
                button.scaleX = 0.65f
                button.scaleY = 0.65f
                button.alpha = 1f
                button.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(260)
                    .setInterpolator(OvershootInterpolator(1.15f))
                    .start()
            }
            .start()

        val reset = Runnable {
            button.animate().cancel()
            button.animate()
                .scaleX(0.82f)
                .scaleY(0.82f)
                .alpha(0.7f)
                .setDuration(110)
                .withEndAction {
                    val original = originals.remove(button)
                    if (original != null) button.setImageDrawable(original) else button.setImageResource(R.drawable.ic_msg_copy)
                    button.imageTintList = normalTint
                    button.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(160)
                        .start()
                }
                .start()
            pendingResets.remove(button)
        }
        pendingResets[button] = reset
        button.postDelayed(reset, 950L)
    }
}
