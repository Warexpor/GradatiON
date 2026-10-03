package io.github.stardomains3.oxproxion

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings wave 33: an IPv6 local server whose password Java's parser throws on, and
 * location grants that ask for approximate as well as precise.
 * Run: ./gradlew :app:testDebugUnitTest --tests io.github.stardomains3.oxproxion.SettingsWave33Test
 */
class SettingsWave33Test {

    @Test
    fun httpAllowsAnIpv6HostWhenThePasswordBreaksJavasParser() {
        assertNull(LanEndpointValidator.validate("http://user:p@ss@[fd00::1]:11434"))
        assertNull(LanEndpointValidator.validate("http://user:p@ss@[fe80::1]:11434/v1"))
        assertNull(LanEndpointValidator.validate("https://user:p@ss@[2001:db8::1]:443"))
        assertNull(LanEndpointValidator.validate("http://user:a b@10.0.0.23:11434"))
        assertNull(LanEndpointValidator.validate("http://user:a b@[fd00::1]:11434"))
        assertNull(LanEndpointValidator.validate("http://user:p@ss@[::ffff:10.0.0.5]:11434"))
        assertNull(LanEndpointValidator.validate("http://user:p@ss@[::1]:8080"))
    }

    @Test
    fun thrownUrlsStillRefuseAPublicHostABadPortAndAnUnbracketedIpv6() {
        assertEquals(
            R.string.lan_error_url_http_public,
            LanEndpointValidator.validate("http://user:p@ss@[2001:db8::1]:443"),
        )
        assertEquals(
            R.string.lan_error_url_http_public,
            LanEndpointValidator.validate("http://user:p@ss@[::ffff:8.8.8.8]:80"),
        )
        assertEquals(
            R.string.lan_error_url_port,
            LanEndpointValidator.validate("http://user:p@ss@[fd00::1]:0"),
        )
        assertEquals(
            R.string.lan_error_url_port,
            LanEndpointValidator.validate("http://user:p@ss@[fd00::1]:65536"),
        )
        assertEquals(
            R.string.lan_error_url_scheme,
            LanEndpointValidator.validate("ftp://user:p@ss@[fd00::1]:11434"),
        )
        // OkHttp cannot open an unbracketed IPv6 literal. A space in the host is not a host.
        assertEquals(
            R.string.lan_error_url_invalid,
            LanEndpointValidator.validate("http://user:p@ss@fd00::1:11434"),
        )
        assertEquals(R.string.lan_error_url_invalid, LanEndpointValidator.validate("http://not a url"))
        // A dotted literal Java cannot parse is still not treated as a hostname.
        assertEquals(R.string.lan_error_url_host, LanEndpointValidator.validate("http://0x7f.0.0.1:11434"))
    }

    @Test
    fun ipv6RowHidesThePasswordAndTheRequestKeepsTheHost() {
        assertEquals("[fd00::1]:11434", settingsLanRowValue("http://user:p@ss@[fd00::1]:11434"))
        assertEquals("[fe80::1]:11434", settingsLanRowValue("http://user:a b@[fe80::1]:11434/v1"))
        assertEquals(
            "[::ffff:192.168.1.5]:11434",
            settingsLanRowValue("http://user:p@ss@[::ffff:192.168.1.5]:11434"),
        )
        assertEquals("[fd00::1]", settingsLanRowValue("http://user:p@ss@[fd00::1]"))
        val saved = LanEndpointValidator.normalizedBase("http://user:p@ss@[fd00::1]:11434/v1?x=1#note")
        assertEquals("http://user:p@ss@[fd00::1]:11434?x=1", saved)
        assertEquals(
            "http://user:p@ss@[fd00::1]:11434/v1/models?x=1",
            LanEndpointValidator.requestUrl(saved, "/v1/models"),
        )
        assertTrue(saved.contains("p@ss"))
        val row = settingsLanRowValue(saved)
        assertEquals("[fd00::1]:11434", row)
        assertFalse(row!!.contains("@"))
        assertFalse(row.contains("p@ss"))
    }

    @Test
    fun locationRequestAsksForPreciseAndApproximateTogether() {
        val permissions = ToolItem.locationPermissionsToRequest()
        assertEquals(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ).toList(),
            permissions.toList(),
        )
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
    }

    @Test
    fun approximateLocationUsesNetworkAndKeepsACoarseFix() {
        assertEquals(
            LocationFixSource.NETWORK,
            ToolItem.locationFixSource(
                fineGranted = false,
                coarseGranted = true,
                gpsEnabled = true,
                networkEnabled = true,
            ),
        )
        assertEquals(
            LocationFixSource.GPS,
            ToolItem.locationFixSource(
                fineGranted = true,
                coarseGranted = true,
                gpsEnabled = true,
                networkEnabled = true,
            ),
        )
        assertEquals(
            LocationFixSource.NETWORK,
            ToolItem.locationFixSource(
                fineGranted = true,
                coarseGranted = false,
                gpsEnabled = false,
                networkEnabled = true,
            ),
        )
        assertNull(
            ToolItem.locationFixSource(
                fineGranted = false,
                coarseGranted = true,
                gpsEnabled = true,
                networkEnabled = false,
            ),
        )
        assertNull(
            ToolItem.locationFixSource(
                fineGranted = false,
                coarseGranted = false,
                gpsEnabled = true,
                networkEnabled = true,
            ),
        )
        assertTrue(ToolItem.locationFixIsEnough(fineGranted = false, hasAccuracy = true, accuracyMeters = 1500f))
        assertTrue(ToolItem.locationFixIsEnough(fineGranted = false, hasAccuracy = false, accuracyMeters = 0f))
        assertFalse(ToolItem.locationFixIsEnough(fineGranted = true, hasAccuracy = true, accuracyMeters = 1500f))
        assertFalse(ToolItem.locationFixIsEnough(fineGranted = true, hasAccuracy = false, accuracyMeters = 3f))
        assertTrue(ToolItem.locationFixIsEnough(fineGranted = true, hasAccuracy = true, accuracyMeters = 10f))
    }
}
