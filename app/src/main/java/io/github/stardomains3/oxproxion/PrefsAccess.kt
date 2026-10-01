package io.github.stardomains3.oxproxion

import android.content.SharedPreferences
import android.util.Log

/**
 * SharedPreferences throws ClassCastException when a key's stored type does not match the getter.
 * A restored backup or a key whose type changed used to crash every screen that built
 * [SharedPreferencesHelper], including launch. The stored value is left in place and the default
 * is used, same as an unreadable JSON blob.
 */
internal class TolerantPrefs(private val base: SharedPreferences) : SharedPreferences by base {
    private val warned = HashSet<String>()

    override fun getString(key: String?, defValue: String?): String? =
        read(key, defValue) { base.getString(key, defValue) }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        read(key, defValues) { base.getStringSet(key, defValues) }

    override fun getInt(key: String?, defValue: Int): Int =
        read(key, defValue) { base.getInt(key, defValue) }

    override fun getLong(key: String?, defValue: Long): Long =
        read(key, defValue) { base.getLong(key, defValue) }

    override fun getFloat(key: String?, defValue: Float): Float =
        read(key, defValue) { base.getFloat(key, defValue) }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        read(key, defValue) { base.getBoolean(key, defValue) }

    private fun <T> read(key: String?, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: ClassCastException) {
            val name = key ?: ""
            if (synchronized(warned) { warned.add(name) }) {
                Log.w(TAG, "Preference '$name' has the wrong type; using the default")
            }
            fallback
        }

    private companion object {
        const val TAG = "SharedPrefs"
    }
}
