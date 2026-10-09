package io.github.stardomains3.oxproxion

import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

object BiometricGateHelper {

    /**
     * True from a successful unlock until the app is backgrounded. It lives in the process, not in
     * the activity's saved state, so an activity restored after process death is still gated, while a
     * rotation (same process, flag still set) is not.
     */
    @Volatile
    var unlocked = false
        private set

    private var stoppedAt = 0L

    /** The app left the screen. */
    fun noteStopped() {
        stoppedAt = SystemClock.elapsedRealtime()
    }

    /**
     * Re-arms the lock once the app has been away longer than [AWAY_MS]. A short trip to the camera
     * or the photo picker comes back without a second prompt.
     */
    fun relockIfAway() {
        if (unlocked && stoppedAt > 0L && SystemClock.elapsedRealtime() - stoppedAt > AWAY_MS) unlocked = false
    }

    private const val AWAY_MS = 30_000L

    fun gateIfNeeded(
        activity: AppCompatActivity,
        onUnlocked: () -> Unit
    ) {
        val prefs = SharedPreferencesHelper(activity)
        // Every entry point (assistant, share, spell check) checks the same away timer, not just MainActivity.
        relockIfAway()
        if (!prefs.getBiometricEnabled() || unlocked) {
            onUnlocked()
            return
        }

        when (BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> showPrompt(activity, onUnlocked)
            else -> {
                // No sensor or nothing enrolled turns the lock off. A busy sensor or a pending
                // security update is temporary: let this launch through but keep the setting.
                val permanent = setOf(
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
                )
                if (BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG) in permanent) {
                    prefs.saveBiometricEnabled(false)
                }
                onUnlocked()
                GlassNotice.show(activity, activity.getString(R.string.notice_biometrics_unavailable))
            }
        }
    }

    private fun showPrompt(
        activity: AppCompatActivity,
        onUnlocked: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                // Cancel, lockout or a system error: there is no unlocking this time, so the app closes.
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    activity.finish()
                }

                // One unrecognised finger is not the end: the system sheet stays up for another try.
                override fun onAuthenticationFailed() {}

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    onUnlocked()
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.biometric_title))
            .setSubtitle(activity.getString(R.string.biometric_subtitle))
            .setNegativeButtonText(activity.getString(R.string.action_cancel))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()

        prompt.authenticate(info)
    }
}
