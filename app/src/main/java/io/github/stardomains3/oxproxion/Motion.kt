package io.github.stardomains3.oxproxion

import android.animation.TimeInterpolator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator
import androidx.fragment.app.FragmentTransaction
import kotlin.math.abs

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

    /**
     * A physical spring for settling whatever the finger just let go of. It starts at the
     * release [velocity] (px/s, positive toward the target), so the hand-off from finger to
     * animation has no seam: no stall, no sudden kick. Its duration comes out of the physics,
     * so a hard flick lands sooner than a slow release. Give every layer that moves together
     * the same instance (or equal ones) and they stay pinned to each other.
     *
     * [response] is the spring's period in seconds (lower is snappier); [damping] 1 never
     * overshoots, which suits whole pages whose edges must not reveal what is beyond.
     */
    class Fling(
        distance: Float,
        velocity: Float,
        response: Float = 0.42f,
        private val damping: Float = 1f
    ) : TimeInterpolator {
        private val omega = (2 * Math.PI / response).toFloat()
        private val v0: Float
        private val damped: Float
        val duration: Long

        init {
            // Velocity as a fraction of the distance per second. Capped so a flick never
            // throws a critically damped page past its target.
            val v = if (abs(distance) < 1f) 0f else velocity / abs(distance)
            v0 = v.coerceIn(-omega, if (damping >= 0.999f) omega * 0.9f else omega * 1.5f)
            damped = if (damping < 0.999f) omega * kotlin.math.sqrt(1f - damping * damping) else 0f
            // Run until what is left of the travel is under half a percent, so the final
            // snap to the target is invisible.
            val b = if (damped > 0f) (damping * omega - v0) / damped else 0f
            var s = 0f
            while (s < 0.9f) {
                s += 0.005f
                val decay = kotlin.math.exp(-damping * omega * s)
                val left = if (damped > 0f) decay * kotlin.math.sqrt(1f + b * b) else decay * (1f + abs(omega - v0) * s)
                if (left < 0.005f) break
            }
            duration = (s * 1000f).toLong().coerceIn(160L, 900L)
        }

        override fun getInterpolation(t: Float): Float {
            if (t >= 1f) return 1f
            val s = t * duration / 1000f
            val decay = kotlin.math.exp(-damping * omega * s)
            return if (damping >= 0.999f) {
                1f - decay * (1f + (omega - v0) * s)
            } else {
                val b = (damping * omega - v0) / damped
                1f - decay * (kotlin.math.cos(damped * s) + b * kotlin.math.sin(damped * s))
            }
        }
    }

    /** [Fling] for a view's translationX, from where it is now to [to]. */
    fun flingX(view: View, to: Float, velocity: Float, response: Float = 0.42f): Fling {
        val d = to - view.translationX
        return Fling(d, if (d == 0f) 0f else velocity * kotlin.math.sign(d), response)
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
