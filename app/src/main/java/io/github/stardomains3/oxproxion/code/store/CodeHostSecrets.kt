package io.github.stardomains3.oxproxion.code.store

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Android Keystore-backed vault for Code mode host pairing tokens.
 * Ciphertext lives in a dedicated prefs file; the AES key never leaves the Keystore.
 * Non-secret host fields (url, name, …) stay in the plain [CodeStore] prefs.
 */
class CodeHostSecrets(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Persist [token] for [hostId]. Returns false if Keystore/encrypt failed (caller must not scrub plaintext). */
    fun putToken(hostId: String, token: String): Boolean {
        if (token.isEmpty()) {
            removeToken(hostId)
            return true
        }
        return try {
            val sealed = CodeAesGcm.seal(getOrCreateKey(), token.toByteArray(Charsets.UTF_8))
            prefs.edit {
                putString(encKey(hostId), Base64.encodeToString(sealed.ciphertext, Base64.NO_WRAP))
                putString(ivKey(hostId), Base64.encodeToString(sealed.iv, Base64.NO_WRAP))
            }
            // Round-trip before caller drops plaintext.
            if (getToken(hostId) != token) {
                Log.e(TAG, "Round-trip verification failed for host $hostId")
                removeToken(hostId)
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt token for host $hostId", e)
            false
        }
    }

    fun getToken(hostId: String): String {
        val enc = prefs.getString(encKey(hostId), null)?.trim().orEmpty()
        val iv = prefs.getString(ivKey(hostId), null)?.trim().orEmpty()
        if (enc.isEmpty() || iv.isEmpty()) return ""
        return try {
            val bytes = CodeAesGcm.open(
                getOrCreateKey(),
                Base64.decode(iv, Base64.DEFAULT),
                Base64.decode(enc, Base64.DEFAULT)
            )
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt token for host $hostId", e)
            ""
        }
    }

    fun removeToken(hostId: String) {
        prefs.edit {
            remove(encKey(hostId))
            remove(ivKey(hostId))
        }
    }

    /** Drop secrets for host ids no longer in the saved host list. */
    fun retainOnly(hostIds: Set<String>) {
        val keep = hostIds.map { encKey(it) }.toSet() + hostIds.map { ivKey(it) }.toSet()
        val stale = prefs.all.keys.filter { it !in keep }
        if (stale.isEmpty()) return
        prefs.edit { stale.forEach { remove(it) } }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
        }
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val TAG = "CodeHostSecrets"
        const val PREFS_NAME = "code_mode_secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "code_mode_host_secrets"
        fun encKey(hostId: String) = "token_${hostId}_encrypted"
        fun ivKey(hostId: String) = "token_${hostId}_iv"
    }
}
