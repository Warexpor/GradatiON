package io.github.stardomains3.oxproxion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.stardomains3.oxproxion.code.CodeHost
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.TransportKind
import io.github.stardomains3.oxproxion.code.store.CodeAesGcm
import io.github.stardomains3.oxproxion.code.store.CodeHostSecrets
import io.github.stardomains3.oxproxion.code.store.CodeSecretKeySource
import io.github.stardomains3.oxproxion.code.store.CodeStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Pure AES helper tests plus Robolectric store/migration tests.
 * Robolectric's AndroidKeyStore is unreliable, so vault tests inject a software AES key
 * (same CodeAesGcm path production uses with the Keystore key).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class CodeHostSecretsTest {

    private lateinit var ctx: Context
    private lateinit var softKey: SecretKey
    private lateinit var keySource: CodeSecretKeySource

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(CodeStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences(CodeHostSecrets.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        softKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        keySource = CodeSecretKeySource { softKey }
    }

    private fun vault() = CodeHostSecrets(ctx, keySource)
    private fun store() = CodeStore(ctx, vault())

    @Test
    fun aesGcmRoundTripWithInjectedKey() {
        val sealed = CodeAesGcm.seal(softKey, "pairing-secret".toByteArray(Charsets.UTF_8))
        assertTrue(sealed.iv.isNotEmpty())
        assertTrue(sealed.ciphertext.isNotEmpty())
        val opened = String(CodeAesGcm.open(softKey, sealed.iv, sealed.ciphertext), Charsets.UTF_8)
        assertEquals("pairing-secret", opened)
    }

    @Test
    fun aesGcmTamperFails() {
        val sealed = CodeAesGcm.seal(softKey, "x".toByteArray(Charsets.UTF_8))
        val bad = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
        try {
            CodeAesGcm.open(softKey, sealed.iv, bad)
            throw AssertionError("expected AEAD failure")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun vaultPutGetRemove() {
        val v = vault()
        assertTrue(v.putToken("h1", "tok-abc"))
        assertEquals("tok-abc", v.getToken("h1"))
        v.removeToken("h1")
        assertEquals("", v.getToken("h1"))
    }

    @Test
    fun storeScrubsTokenFromPlainPrefs() {
        val s = store()
        s.hosts = listOf(
            CodeHost(
                id = "h1", name = "Laptop", url = "wss://host/v1", token = "secret-token",
                transport = TransportKind.BRIDGE, defaultHarness = HarnessKind.OPENCODE
            )
        )
        val plain = ctx.getSharedPreferences(CodeStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("hosts", "")!!
        assertFalse("token must not remain in plain hosts JSON", plain.contains("secret-token"))
        assertEquals("secret-token", s.hosts.single().token)
        assertEquals("wss://host/v1", s.hosts.single().url)
    }

    @Test
    fun migratesLegacyPlaintextTokensOnFirstRead() {
        val json = Json { encodeDefaults = true }
        val legacy = listOf(
            CodeHost(
                id = "legacy", name = "Old", url = "ws://lan:7878/v1", token = "legacy-token",
                transport = TransportKind.BRIDGE
            )
        )
        ctx.getSharedPreferences(CodeStore.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("hosts", json.encodeToString(ListSerializer(CodeHost.serializer()), legacy))
            .commit()

        val s = store()
        assertEquals("legacy-token", s.hosts.single().token)

        val plain = ctx.getSharedPreferences(CodeStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("hosts", "")!!
        assertFalse(plain.contains("legacy-token"))
        assertTrue(
            ctx.getSharedPreferences(CodeStore.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("host_tokens_migrated", false)
        )
        assertEquals("legacy-token", store().hosts.single().token)
    }

    @Test
    fun removeHostDropsVaultEntry() {
        val s = store()
        s.hosts = listOf(
            CodeHost(id = "a", name = "A", url = "wss://a/v1", token = "ta"),
            CodeHost(id = "b", name = "B", url = "wss://b/v1", token = "tb")
        )
        s.hosts = s.hosts.filter { it.id == "a" }
        val v = vault()
        assertEquals("ta", v.getToken("a"))
        assertEquals("", v.getToken("b"))
        assertEquals(1, s.hosts.size)
    }

    @Test
    fun demoHostWithEmptyTokenLeavesVaultEmpty() {
        val s = store()
        s.hosts = listOf(
            CodeHost(id = "demo", name = "Demo", transport = TransportKind.DEMO)
        )
        assertEquals("", s.hosts.single().token)
        assertEquals("", vault().getToken("demo"))
        val plain = ctx.getSharedPreferences(CodeStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("hosts", "")!!
        assertTrue(plain.contains("demo"))
    }

    @Test
    fun ciphertextDiffersFromPlaintext() {
        val v = vault()
        assertTrue(v.putToken("h", "visible-secret"))
        val blob = ctx.getSharedPreferences(CodeHostSecrets.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(CodeHostSecrets.encKey("h"), "")!!
        assertTrue(blob.isNotBlank())
        assertNotEquals("visible-secret", blob)
        assertFalse(blob.contains("visible-secret"))
    }

    @Test
    fun vaultFailureKeepsPlaintextToken() {
        val failing = CodeSecretKeySource { error("keystore unavailable") }
        val s = CodeStore(ctx, CodeHostSecrets(ctx, failing))
        s.hosts = listOf(
            CodeHost(id = "h1", name = "X", url = "wss://x/v1", token = "keep-me")
        )
        val plain = ctx.getSharedPreferences(CodeStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("hosts", "")!!
        assertTrue("plaintext retained when vault fails", plain.contains("keep-me"))
        assertEquals("keep-me", s.hosts.single().token)
    }
}
