package io.github.stardomains3.oxproxion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.stardomains3.oxproxion.code.CodeHost
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.TransportKind
import io.github.stardomains3.oxproxion.code.store.CodeAesGcm
import io.github.stardomains3.oxproxion.code.store.CodeHostSecrets
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

/** Keystore vault + one-shot plaintext-token migration for Code mode hosts. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class CodeHostSecretsTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(CodeStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("code_mode_secrets", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aesGcmRoundTripWithInjectedKey() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val sealed = CodeAesGcm.seal(key, "pairing-secret".toByteArray(Charsets.UTF_8))
        assertTrue(sealed.iv.isNotEmpty())
        assertTrue(sealed.ciphertext.isNotEmpty())
        val opened = String(CodeAesGcm.open(key, sealed.iv, sealed.ciphertext), Charsets.UTF_8)
        assertEquals("pairing-secret", opened)
    }

    @Test
    fun aesGcmTamperFails() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val sealed = CodeAesGcm.seal(key, "x".toByteArray(Charsets.UTF_8))
        val bad = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
        try {
            CodeAesGcm.open(key, sealed.iv, bad)
            throw AssertionError("expected AEAD failure")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun vaultPutGetRemove() {
        val vault = CodeHostSecrets(ctx)
        assertTrue(vault.putToken("h1", "tok-abc"))
        assertEquals("tok-abc", vault.getToken("h1"))
        vault.removeToken("h1")
        assertEquals("", vault.getToken("h1"))
    }

    @Test
    fun storeScrubsTokenFromPlainPrefs() {
        val store = CodeStore(ctx)
        store.hosts = listOf(
            CodeHost(
                id = "h1", name = "Laptop", url = "wss://host/v1", token = "secret-token",
                transport = TransportKind.BRIDGE, defaultHarness = HarnessKind.OPENCODE
            )
        )
        val plain = ctx.getSharedPreferences(CodeStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("hosts", "")!!
        assertFalse("token must not remain in plain hosts JSON", plain.contains("secret-token"))
        assertEquals("secret-token", store.hosts.single().token)
        assertEquals("wss://host/v1", store.hosts.single().url)
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

        val store = CodeStore(ctx)
        assertEquals("legacy-token", store.hosts.single().token)

        val plain = ctx.getSharedPreferences(CodeStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("hosts", "")!!
        assertFalse(plain.contains("legacy-token"))
        assertTrue(
            ctx.getSharedPreferences(CodeStore.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("host_tokens_migrated", false)
        )
        // Second read still rehydrates from vault.
        assertEquals("legacy-token", CodeStore(ctx).hosts.single().token)
    }

    @Test
    fun removeHostDropsVaultEntry() {
        val store = CodeStore(ctx)
        store.hosts = listOf(
            CodeHost(id = "a", name = "A", url = "wss://a/v1", token = "ta"),
            CodeHost(id = "b", name = "B", url = "wss://b/v1", token = "tb")
        )
        store.hosts = store.hosts.filter { it.id == "a" }
        val vault = CodeHostSecrets(ctx)
        assertEquals("ta", vault.getToken("a"))
        assertEquals("", vault.getToken("b"))
        assertEquals(1, store.hosts.size)
    }

    @Test
    fun demoHostWithEmptyTokenLeavesVaultEmpty() {
        val store = CodeStore(ctx)
        store.hosts = listOf(
            CodeHost(id = "demo", name = "Demo", transport = TransportKind.DEMO)
        )
        assertEquals("", store.hosts.single().token)
        assertEquals("", CodeHostSecrets(ctx).getToken("demo"))
        val plain = ctx.getSharedPreferences(CodeStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("hosts", "")!!
        assertTrue(plain.contains("demo"))
    }

    @Test
    fun ciphertextDiffersFromPlaintext() {
        val vault = CodeHostSecrets(ctx)
        assertTrue(vault.putToken("h", "visible-secret"))
        val blob = ctx.getSharedPreferences("code_mode_secrets", Context.MODE_PRIVATE)
            .getString("token_h_encrypted", "")!!
        assertTrue(blob.isNotBlank())
        assertNotEquals("visible-secret", blob)
        assertFalse(blob.contains("visible-secret"))
    }
}
