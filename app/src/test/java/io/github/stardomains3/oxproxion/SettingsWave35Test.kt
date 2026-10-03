package io.github.stardomains3.oxproxion

import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings wave 35: a pasted models or chat URL, a `%` that is not an escape, and the
 * fused location provider when the network provider is off.
 * Run: ./gradlew :app:testDebugUnitTest --tests io.github.stardomains3.oxproxion.SettingsWave35Test
 */
class SettingsWave35Test {

    @Test
    fun pastedModelAndChatRoutesAreTheBaseTheAppAppendsTo() {
        assertEquals(
            "http://10.0.0.23:11434",
            LanEndpointValidator.normalizedBase("http://10.0.0.23:11434/v1/models"),
        )
        assertEquals(
            "http://10.0.0.23:11434",
            LanEndpointValidator.normalizedBase("http://10.0.0.23:11434/v1/models/"),
        )
        assertEquals(
            "http://127.0.0.1:11434",
            LanEndpointValidator.normalizedBase("http://127.0.0.1:11434/v1/chat/completions"),
        )
        assertEquals(
            "http://127.0.0.1:11434",
            LanEndpointValidator.normalizedBase("http://127.0.0.1:11434/api/tags"),
        )
        assertEquals(
            "http://localhost:1234",
            LanEndpointValidator.normalizedBase("http://localhost:1234/v1/audio/transcriptions"),
        )
        assertEquals(
            "http://10.0.0.23:11434",
            LanEndpointValidator.normalizedBase("http://10.0.0.23:11434/api/v0/models"),
        )
        assertEquals(
            "http://10.0.0.23:11434",
            LanEndpointValidator.normalizedBase("http://10.0.0.23:11434/api/show"),
        )
        assertEquals(
            "HTTP://10.0.0.23:11434",
            LanEndpointValidator.normalizedBase("HTTP://10.0.0.23:11434/V1/Models"),
        )
        // A proxy prefix stays. A longer suffix that is not a route stays. The host `v1` stays.
        assertEquals(
            "http://10.0.0.23:11434/openai",
            LanEndpointValidator.normalizedBase("http://10.0.0.23:11434/openai/v1"),
        )
        assertEquals(
            "http://10.0.0.23:11434/v10",
            LanEndpointValidator.normalizedBase("http://10.0.0.23:11434/v10"),
        )
        assertEquals("http://v1", LanEndpointValidator.normalizedBase("http://v1"))
        assertEquals("http://v1/models", LanEndpointValidator.normalizedBase("http://v1/models"))
    }

    @Test
    fun pastedRouteKeepsTheQueryDropsTheFragmentAndTheRowHidesThePassword() {
        val saved = LanEndpointValidator.normalizedBase(
            "http://user:secret@10.0.0.23:11434/v1/models?token=abc#note",
        )
        assertEquals("http://user:secret@10.0.0.23:11434?token=abc", saved)
        assertEquals(
            "http://user:secret@10.0.0.23:11434/v1/models?token=abc",
            LanEndpointValidator.requestUrl(saved, "/v1/models"),
        )
        assertEquals(
            "http://127.0.0.1:11434/api/tags",
            LanEndpointValidator.requestUrl("http://127.0.0.1:11434/api/tags/", "/api/tags"),
        )
        assertEquals(
            "http://[fd00::1]:11434/v1/chat/completions",
            LanEndpointValidator.requestUrl("http://[fd00::1]:11434/v1/chat/completions", "/v1/chat/completions"),
        )
        val row = settingsLanRowValue(saved)
        assertEquals("10.0.0.23:11434", row)
        assertFalse(row!!.contains("secret"))
        assertFalse(row.contains("@"))
        assertFalse(row.contains("/v1"))
    }

    @Test
    fun aPercentThatIsNotAnEscapeIsStoredSoTheClientCanOpenIt() {
        assertNull(LanEndpointValidator.validate("http://user:100%@10.0.0.23:11434"))
        assertNull(LanEndpointValidator.validate("http://user:p%ss@[fd00::1]:11434"))
        assertNull(LanEndpointValidator.validate("http://user:a b%@[fd00::1]:11434/v1"))
        assertEquals(
            "http://user:100%25@10.0.0.23:11434",
            LanEndpointValidator.normalizedBase("http://user:100%@10.0.0.23:11434"),
        )
        assertEquals(
            "http://user:p%25ss@[fd00::1]:11434",
            LanEndpointValidator.normalizedBase("http://user:p%ss@[fd00::1]:11434/v1/models"),
        )
        assertEquals(
            "http://user:a b%25@[fd00::1]:11434?x=1",
            LanEndpointValidator.normalizedBase("http://user:a b%@[fd00::1]:11434/v1?x=1#note"),
        )
        // A real escape stays one escape. A query token with a bare percent is encoded, and the route is not doubled.
        assertEquals(
            "http://user:p%40ss@10.0.0.23:11434?token=abc%3D",
            LanEndpointValidator.normalizedBase("http://user:p%40ss@10.0.0.23:11434?token=abc%3D"),
        )
        val saved = LanEndpointValidator.normalizedBase("http://10.0.0.23:11434/v1/models?token=100%#note")
        assertEquals("http://10.0.0.23:11434?token=100%25", saved)
        assertEquals(
            "http://10.0.0.23:11434/v1/models?token=100%25",
            LanEndpointValidator.requestUrl(saved, "/v1/models"),
        )
        val row = settingsLanRowValue("http://user:p%ss@10.0.0.23.:11434")
        assertEquals("10.0.0.23.:11434", row)
        assertFalse(row!!.contains("%"))
        assertFalse(row.contains("@"))
        assertFalse(row.contains("p%ss"))
    }

    @Test
    fun aBarePercentStillRefusesAPublicHostABadPortAZoneAndALeadingZero() {
        assertEquals(
            R.string.lan_error_url_http_public,
            LanEndpointValidator.validate("http://user:100%@8.8.8.8:80"),
        )
        assertEquals(
            R.string.lan_error_url_http_public,
            LanEndpointValidator.validate("http://user:p%ss@[2001:db8::1]:443"),
        )
        assertEquals(
            R.string.lan_error_url_port,
            LanEndpointValidator.validate("http://user:100%@[fd00::1]:0"),
        )
        assertEquals(
            R.string.lan_error_url_invalid,
            LanEndpointValidator.validate("http://[fe80::1%wlan0]:11434"),
        )
        assertEquals(
            R.string.lan_error_url_invalid,
            LanEndpointValidator.validate("http://[::ffff:192.168.001.001]:11434"),
        )
        assertNull(LanEndpointValidator.validate("http://10.0.0.23.:11434"))
        assertNull(LanEndpointValidator.validate("http://[fe80::1]:11434"))
        assertNull(LanEndpointValidator.validate("https://user:100%@[2001:db8::1]:443"))
        assertEquals(
            "https://user:100%25@[2001:db8::1]:443",
            LanEndpointValidator.normalizedBase("https://user:100%@[2001:db8::1]:443"),
        )
    }

    @Test
    fun fusedProviderIsUsedWhenNetworkIsOff() {
        assertEquals(
            LocationFixSource.FUSED,
            ToolItem.locationFixSource(
                fineGranted = false,
                coarseGranted = true,
                gpsEnabled = true,
                networkEnabled = false,
                fusedEnabled = true,
            ),
        )
        assertEquals(
            LocationFixSource.FUSED,
            ToolItem.locationFixSource(
                fineGranted = true,
                coarseGranted = true,
                gpsEnabled = false,
                networkEnabled = false,
                fusedEnabled = true,
            ),
        )
        // GPS still wins when precise location can read it. Network still wins over fused.
        assertEquals(
            LocationFixSource.GPS,
            ToolItem.locationFixSource(
                fineGranted = true,
                coarseGranted = true,
                gpsEnabled = true,
                networkEnabled = true,
                fusedEnabled = true,
            ),
        )
        assertEquals(
            LocationFixSource.NETWORK,
            ToolItem.locationFixSource(
                fineGranted = false,
                coarseGranted = true,
                gpsEnabled = true,
                networkEnabled = true,
                fusedEnabled = true,
            ),
        )
        assertNull(
            ToolItem.locationFixSource(
                fineGranted = false,
                coarseGranted = true,
                gpsEnabled = true,
                networkEnabled = false,
                fusedEnabled = false,
            ),
        )
        assertNull(
            ToolItem.locationFixSource(
                fineGranted = false,
                coarseGranted = false,
                gpsEnabled = true,
                networkEnabled = true,
                fusedEnabled = true,
            ),
        )
        assertEquals(LocationManager.FUSED_PROVIDER, ToolItem.locationProviderName(LocationFixSource.FUSED))
        assertEquals(LocationManager.NETWORK_PROVIDER, ToolItem.locationProviderName(LocationFixSource.NETWORK))
        assertEquals(LocationManager.GPS_PROVIDER, ToolItem.locationProviderName(LocationFixSource.GPS))
    }

    @Test
    fun timeoutAndADisabledProviderFallBackToFusedWhenNetworkIsOff() {
        assertEquals(
            LocationFixSource.NETWORK,
            ToolItem.locationTimeoutFallback(networkEnabled = true, fusedEnabled = true),
        )
        assertEquals(
            LocationFixSource.FUSED,
            ToolItem.locationTimeoutFallback(networkEnabled = false, fusedEnabled = true),
        )
        assertNull(ToolItem.locationTimeoutFallback(networkEnabled = false, fusedEnabled = false))
        assertTrue(
            ToolItem.locationHasFallback(
                listening = LocationFixSource.GPS,
                networkEnabled = false,
                fusedEnabled = true,
            ),
        )
        assertTrue(
            ToolItem.locationHasFallback(
                listening = LocationFixSource.NETWORK,
                networkEnabled = true,
                fusedEnabled = true,
            ),
        )
        assertFalse(
            ToolItem.locationHasFallback(
                listening = LocationFixSource.GPS,
                networkEnabled = false,
                fusedEnabled = false,
            ),
        )
        assertFalse(
            ToolItem.locationHasFallback(
                listening = LocationFixSource.FUSED,
                networkEnabled = false,
                fusedEnabled = true,
            ),
        )
        // Coarse on GPS with neither network nor fused still has nothing else to read.
        assertFalse(
            ToolItem.locationHasFallback(
                listening = LocationFixSource.NETWORK,
                networkEnabled = false,
                fusedEnabled = false,
            ),
        )
    }
}
