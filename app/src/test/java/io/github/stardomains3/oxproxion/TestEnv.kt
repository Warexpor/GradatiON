package io.github.stardomains3.oxproxion

import androidx.lifecycle.ViewModelProvider

/** Shared Robolectric hygiene for tests that launch activities. */
object TestEnv {
    /**
     * Production code uses [AppViewModelFactory]. This still clears the framework factory's static
     * Application, in case a test constructs a ViewModel without that factory.
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
