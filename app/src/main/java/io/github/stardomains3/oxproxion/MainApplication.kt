package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.stardomains3.oxproxion.code.CodeHub

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Starts reading the settings file on its own thread now, while the native library
        // loads; the first screen's read then finds it ready instead of waiting on the disk.
        getSharedPreferences(SharedPreferencesHelper.MAIN_PREFS, MODE_PRIVATE)
        // Fail fast if the native lib is missing; Room open also loads it.
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("SQLCipher native library failed to load", e)
        }
        // Keep Code mode's bridge reconnect policy in sync with the whole app lifecycle. This must
        // not build the hub (encrypted hosts, database) for someone who never opens Code: the flag
        // is remembered, and a hub created later reads it.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                CodeHub.noteAppBackgrounded(this@MainApplication, false)
            }

            override fun onStop(owner: LifecycleOwner) {
                CodeHub.noteAppBackgrounded(this@MainApplication, true)
            }
        })
        // Kill leftover sticky "Running" FGS notifs from older builds
        ForegroundService.clearLegacyRunningNotification(this)
    }
}
