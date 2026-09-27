package io.github.stardomains3.oxproxion

import androidx.lifecycle.ViewModelProvider

/** Shared Robolectric hygiene for tests that launch activities. */
object TestEnv {
    /**
     * The default ViewModel factory caches the first Application it sees in a static. On a phone
     * there is one Application per process; under Robolectric every test gets a new one, so a
     * cached factory hands later ViewModels a stale Application (and its stale prefs). Clear it.
     */
    fun resetViewModelFactory() {
        runCatching {
            ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance").apply {
                isAccessible = true
                set(null, null)
            }
        }
    }
}
