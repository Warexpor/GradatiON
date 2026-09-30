package io.github.stardomains3.oxproxion

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * One place that honours Settings > Haptics > Buttons, so a tap on the chat, the message menu
 * and a copied code block all go quiet together when the owner turns it off.
 */
object Haptics {

    fun tap(view: View, constant: Int = HapticFeedbackConstants.CLOCK_TICK) {
        if (!SharedPreferencesHelper(view.context).getHapticButtons()) return
        view.performHapticFeedback(constant)
    }
}
