package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.stardomains3.oxproxion.LanCertPins.Decision
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Trust on first use for the LAN "trust self-signed certificates" mode: the pin decision, the pin
 * store in preferences, and forgetting every pin when the setting goes off.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*LanCertPinsTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class LanCertPinsTest {

    private class MapStore : LanCertPins.Store {
        val pins = HashMap<String, String>()
        override fun pinFor(hostPort: String) = pins[hostPort]
        override fun savePin(hostPort: String, pin: String) { pins[hostPort] = pin }
    }

    @Test
    fun firstCertificateIsPinnedThenOnlyThatOneIsAccepted() {
        val store = MapStore()
        val pins = LanCertPins(store)

        assertEquals(Decision.PinnedFirstUse, pins.check("192.168.1.5:1234", "sha256/AAA"))
        assertEquals("sha256/AAA", store.pins["192.168.1.5:1234"])
        assertEquals(Decision.Accepted, pins.check("192.168.1.5:1234", "sha256/AAA"))
        assertEquals(Decision.Mismatch, pins.check("192.168.1.5:1234", "sha256/BBB"))
        // A refused certificate never replaces the pin.
        assertEquals("sha256/AAA", store.pins["192.168.1.5:1234"])
    }

    @Test
    fun eachHostAndPortHasItsOwnPin() {
        val pins = LanCertPins(MapStore())
        assertEquals(Decision.PinnedFirstUse, pins.check("nas.local:8443", "sha256/AAA"))
        assertEquals(Decision.PinnedFirstUse, pins.check("nas.local:9443", "sha256/BBB"))
        assertEquals(Decision.PinnedFirstUse, pins.check("other.local:8443", "sha256/CCC"))
        assertEquals(Decision.Accepted, pins.check("nas.local:9443", "sha256/BBB"))
        assertEquals(Decision.Mismatch, pins.check("nas.local:8443", "sha256/BBB"))
    }

    @Test
    fun hostPortKeyUsesTheUrlsHostAndEffectivePort() {
        assertEquals("192.168.1.5:1234", LanCertPins.hostPortOf("https://192.168.1.5:1234/v1/models".toHttpUrl()))
        assertEquals("nas.local:443", LanCertPins.hostPortOf("https://NAS.local/v1".toHttpUrl()))
    }

    @Test
    fun prefsStoreRoundTripsAndTurningTrustOffClearsEveryPin() {
        val prefs = SharedPreferencesHelper(ApplicationProvider.getApplicationContext<Application>())
        prefs.saveTrustSelfSignedLan(true)
        val store = prefs.lanCertPinStore()
        assertNull(store.pinFor("a:1"))

        store.savePin("a:1", "sha256/AAA")
        store.savePin("b:2", "sha256/BBB")
        assertEquals("sha256/AAA", store.pinFor("a:1"))
        assertEquals("sha256/BBB", prefs.lanCertPinStore().pinFor("b:2"))

        prefs.saveTrustSelfSignedLan(false)
        assertFalse(prefs.getTrustSelfSignedLan())
        assertNull(store.pinFor("a:1"))
        assertNull(store.pinFor("b:2"))

        // Back on, the next certificate is trusted on first use again.
        prefs.saveTrustSelfSignedLan(true)
        assertEquals(Decision.PinnedFirstUse, LanCertPins(store).check("a:1", "sha256/NEW"))
    }

    @Test
    fun theChangedCertificateMessageIsTheOneTheUserSees() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(
            "The local server's certificate changed. Turn Trust self-signed certificates off and on to accept the new one.",
            app.getString(R.string.error_lan_cert_changed)
        )
        assertTrue(LanCertChangedException("x") is java.io.IOException)
    }
}
