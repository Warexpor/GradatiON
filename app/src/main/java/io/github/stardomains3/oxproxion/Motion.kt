package io.github.stardomains3.oxproxion

import android.animation.TimeInterpolator
import android.content.Context
import android.provider.Settings
import android.view.animation.PathInterpolator
import androidx.fragment.app.FragmentTransaction

object Motion {
    val easeOut = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** iOS-like decelerate for things arriving on screen. */
    val iosOut = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
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

    fun FragmentTransaction.withGrokFadeAnimations(): FragmentTransaction {
        return setCustomAnimations(
            R.anim.fade_in,
            R.anim.fade_out,
            R.anim.fade_in,
            R.anim.fade_out
        )
    }
}
