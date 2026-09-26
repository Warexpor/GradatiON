package io.github.stardomains3.oxproxion.code.store

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM seal/open used by [CodeHostSecrets]. Takes an injected [SecretKey] so unit tests
 * can exercise the crypto without Android Keystore.
 */
internal object CodeAesGcm {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    data class Sealed(val iv: ByteArray, val ciphertext: ByteArray)

    fun seal(key: SecretKey, plaintext: ByteArray): Sealed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Sealed(iv = cipher.iv, ciphertext = cipher.doFinal(plaintext))
    }

    fun open(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
