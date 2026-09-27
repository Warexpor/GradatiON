package io.github.stardomains3.oxproxion

import android.animation.TimeInterpolator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator
import androidx.fragment.app.FragmentTransaction

object Motion {
    val easeOut = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** iOS-like decelerate for things arriving on screen. */
    val iosOut = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
    /** UINavigationController-like push/pop curve. */
    val iosPush = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    /** Quick accelerate for things leaving. */
    val iosIn = PathInterpolator(0.4f, 0f, 1f, 1f)
    /** Critically-damped-ish spring: arrives fast, settles with a whisper of overshoot. */
    val spring: TimeInterpolator = SpringInterpolator(dampingRatio = 0.78f)
    /** Softer spring for small elements (bubbles, pills). */
    val springBouncy: TimeInterpolator = SpringInterpolator(dampingRatio = 0.66f)

    /**
     * Damped harmonic oscillator mapped onto 0..1 so it can drive ordinary animators.
     * [stiffness] sets how many radians the spring travels over the animation; the default
     * settles within the duration for damping ratios down to ~0.6.
     */
    class SpringInterpolator(
        private val dampingRatio: Float,
        private val stiffness: Float = 9.5f
    ) : TimeInterpolator {
        private val damped = stiffness * kotlin.math.sqrt(1f - dampingRatio * dampingRatio)
        override fun getInterpolation(t: Float): Float {
            if (t >= 1f) return 1f
            val decay = kotlin.math.exp(-dampingRatio * stiffness * t)
            val phase = damped * t
            return 1f - decay * (kotlin.math.cos(phase) + (dampingRatio * stiffness / damped) * kotlin.math.sin(phase))
        }
    }

    fun areAnimationsEnabled(context: Context): Boolean {
        return try {
            val durationScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            durationScale != 0.0f
        } catch (_: Exception) {
            true
        }
    }

    fun FragmentTransaction.withGrokStackAnimations(): FragmentTransaction {
        return setCustomAnimations(
            R.anim.fragment_open_enter,
            R.anim.fragment_open_exit,
            R.anim.fragment_close_enter,
            R.anim.fragment_close_exit
        )
    }

    /** Push a screen over one that stays visible (the history panel): it slides, never fades through. */
    fun FragmentTransaction.withGrokPushOver(): FragmentTransaction {
        return setCustomAnimations(R.anim.fragment_open_enter, 0, 0, R.anim.fragment_close_exit)
    }

    fun FragmentTransaction.withGrokFadeAnimations(): FragmentTransaction {
        return setCustomAnimations(
            R.anim.fade_in,
            R.anim.fade_out,
            R.anim.fade_in,
            R.anim.fade_out
        )
    }

    /**
     * Show/hide a small floating control (scroll buttons, chips) with a springy pop instead
     * of blinking. Repeated calls toward the same state are no-ops, so it is safe from scroll
     * callbacks.
     */
    fun View.setShownAnimated(show: Boolean, hiddenVisibility: Int = View.INVISIBLE) {
        val target = if (show) View.VISIBLE else hiddenVisibility
        val pending = getTag(R.id.tag_visibility_animator) as? Boolean
        if (pending == show) return
        if (pending == null && visibility == target) return
        animate().cancel()
        if (!areAnimationsEnabled(context)) {
            setTag(R.id.tag_visibility_animator, null)
            visibility = target
            alpha = 1f; scaleX = 1f; scaleY = 1f
            return
        }
        setTag(R.id.tag_visibility_animator, show)
        if (show) {
            if (visibility != View.VISIBLE) {
                alpha = 0f; scaleX = 0.8f; scaleY = 0.8f
                visibility = View.VISIBLE
            }
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(springBouncy)
                .withEndAction { setTag(R.id.tag_visibility_animator, null) }.start()
        } else {
            animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(160).setInterpolator(iosIn)
                .withEndAction {
                    visibility = hiddenVisibility
                    alpha = 1f; scaleX = 1f; scaleY = 1f
                    setTag(R.id.tag_visibility_animator, null)
                }.start()
        }
    }
}
