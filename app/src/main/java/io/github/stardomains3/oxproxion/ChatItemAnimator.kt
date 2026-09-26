package io.github.stardomains3.oxproxion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * Message arrival motion. A sent user bubble rises from the composer on a spring (the
 * iMessage "send" feel); an assistant row fades up gently. Changes stay instant (streaming
 * repaints in place) and removals/moves keep the default quick fades.
 */
class ChatItemAnimator(private val userViewType: Int) : DefaultItemAnimator() {

    private val running = HashMap<RecyclerView.ViewHolder, Animator>()

    /** Off while a whole transcript is being (re)loaded so history doesn't cascade in. */
    var animateAdds = true

    init {
        supportsChangeAnimations = false
        moveDuration = 320L
        removeDuration = 160L
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        endAnimation(holder)
        if (!animateAdds || !Motion.areAnimationsEnabled(view.context)) {
            dispatchAddFinished(holder)
            return false
        }
        val d = view.resources.displayMetrics.density
        val isUser = holder.itemViewType == userViewType
        val set = AnimatorSet()
        if (isUser) {
            view.pivotX = view.width.toFloat()
            view.pivotY = view.height.toFloat()
            view.alpha = 0f
            view.translationY = 56f * d
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            set.playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 1f).setDuration(180L),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f).setDuration(560L),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f).setDuration(560L),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f).setDuration(560L)
            )
            set.childAnimations.drop(1).forEach { it.interpolator = Motion.springBouncy }
            set.childAnimations.first().interpolator = Motion.iosOut
        } else {
            view.alpha = 0f
            view.translationY = 10f * d
            set.playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 1f).setDuration(260L),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f).setDuration(420L)
            )
            set.interpolator = Motion.iosOut
            set.startDelay = 60L
        }
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) = dispatchAddStarting(holder)
            override fun onAnimationEnd(animation: Animator) {
                reset(view)
                running.remove(holder)
                dispatchAddFinished(holder)
                if (!isRunning) dispatchAnimationsFinished()
            }
        })
        running[holder] = set
        set.start()
        return false
    }

    private fun reset(view: View) {
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        running.remove(item)?.let {
            it.end()
            reset(item.itemView)
        }
        super.endAnimation(item)
    }

    override fun endAnimations() {
        running.values.toList().forEach { it.end() }
        running.clear()
        super.endAnimations()
    }

    override fun isRunning(): Boolean = running.isNotEmpty() || super.isRunning()
}
