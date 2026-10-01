package io.github.stardomains3.oxproxion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.stardomains3.oxproxion.code.PermissionMode
import io.github.stardomains3.oxproxion.code.store.CodeHostSecrets
import io.github.stardomains3.oxproxion.code.store.CodeSecretKeySource
import io.github.stardomains3.oxproxion.code.store.CodeStore
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fresh install: main screen shows Chat only. Roleplay and Code are opt-in; Code runs full auto. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class ModesDefaultTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun store(): CodeStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return CodeStore(ctx, CodeHostSecrets(ctx, CodeSecretKeySource { key }))
    }

    @Test fun roleplayOffByDefault() {
        val prefs = SharedPreferencesHelper(ctx)
        assertFalse(prefs.isRoleplayEnabled())
        prefs.setRoleplayEnabled(true)
        assertTrue(prefs.isRoleplayEnabled())
    }

    @Test fun codeOffByDefault() {
        assertFalse(store().enabled)
    }

    @Test fun newCodeSessionsDefaultToFullAuto() {
        val s = store()
        assertEquals(PermissionMode.FULL_AUTO, s.defaultPermissionMode)
        s.defaultPermissionMode = PermissionMode.ASK
        assertEquals(PermissionMode.ASK, s.defaultPermissionMode)
    }
}
