package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings wave 34: a trailing dot on an IPv4 local server, a link-local zone id,
 * and an IPv4-mapped address with a leading zero.
 * Run: ./gradlew :app:testDebugUnitTest --tests io.github.stardomains3.oxproxion.SettingsWave34Test
 */
class SettingsWave34Test {

    @Test
    fun httpAllowsAnIpv4HostWithOneTrailingDot() {
        assertNull(LanEndpointValidator.validate("http://10.0.0.23.:11434"))
        assertNull(LanEndpointValidator.validate("http://172.16.0.1.:11434/v1"))
        assertNull(LanEndpointValidator.validate("http://100.64.0.1.:11434"))
        assertNull(LanEndpointValidator.validate("http://169.254.1.1.:80"))
        assertNull(LanEndpointValidator.validate("http://127.0.0.1.:8080"))
        assertNull(LanEndpointValidator.validate("https://8.8.8.8.:443"))
        assertNull(LanEndpointValidator.validate("http://user:secret@10.0.0.23.:11434"))
        assertNull(LanEndpointValidator.validate("http://user:p@ss@10.0.0.23.:11434/v1"))
        assertNull(LanEndpointValidator.validate("http://192.168.001.001:11434"))
    }

    @Test
    fun trailingDotStillRefusesAPublicLiteralABadPortAndAnUnparsedDottedHost() {
        assertEquals(
            R.string.lan_error_url_http_public,
            LanEndpointValidator.validate("http://8.8.8.8.:53"),
        )
        assertEquals(
            R.string.lan_error_url_http_public,
            LanEndpointValidator.validate("http://user:p@ss@8.8.8.8.:80"),
        )
        assertEquals(R.string.lan_error_url_port, LanEndpointValidator.validate("http://10.0.0.23.:0"))
        assertEquals(R.string.lan_error_url_port, LanEndpointValidator.validate("http://10.0.0.23.:65536"))
        // Two dots are not a host the client opens. A dotted literal Java cannot parse stays one.
        assertEquals(R.string.lan_error_url_host, LanEndpointValidator.validate("http://10.0.0.23..:11434"))
        assertEquals(R.string.lan_error_url_host, LanEndpointValidator.validate("http://0x7f.0.0.1:11434"))
        assertEquals(R.string.lan_error_url_host, LanEndpointValidator.validate("http://0x7f.0.0.1.:11434"))
        assertTrue(LanEndpointValidator.isPrivateOrLocalHost("10.0.0.23."))
        assertTrue(LanEndpointValidator.isPrivateOrLocalHost("nas.local."))
        assertFalse(LanEndpointValidator.isPrivateOrLocalHost("8.8.8.8."))
    }

    @Test
    fun trailingDotRowHidesThePasswordAndTheRequestKeepsTheHost() {
        assertEquals("10.0.0.23.:11434", settingsLanRowValue("http://10.0.0.23.:11434"))
        assertEquals("10.0.0.23.:11434", settingsLanRowValue("http://user:secret@10.0.0.23.:11434/v1"))
        assertEquals("10.0.0.23.:11434", settingsLanRowValue("http://user:p@ss@10.0.0.23.:11434"))
        val saved = LanEndpointValidator.normalizedBase("http://user:p@ss@10.0.0.23.:11434/v1?x=1#note")
        assertEquals("http://user:p@ss@10.0.0.23.:11434?x=1", saved)
        assertEquals(
            "http://user:p@ss@10.0.0.23.:11434/v1/models?x=1",
            LanEndpointValidator.requestUrl(saved, "/v1/models"),
        )
        val row = settingsLanRowValue(saved)
        assertEquals("10.0.0.23.:11434", row)
        assertFalse(row!!.contains("@"))
        assertFalse(row.contains("p@ss"))
        assertFalse(row.contains("secret"))
    }

    @Test
    fun httpRefusesAZoneIdTheClientCannotOpen() {
        assertEquals(
            R.string.lan_error_url_invalid,
            LanEndpointValidator.validate("http://[fe80::1%wlan0]:11434"),
        )
        assertEquals(
            R.string.lan_error_url_invalid,
            LanEndpointValidator.validate("http://[fe80::1%25wlan0]:11434/v1"),
        )
        assertEquals(
            R.string.lan_error_url_invalid,
            LanEndpointValidator.validate("http://user:p@ss@[fe80::1%25wlan0]:11434"),
        )
        assertEquals(
            R.string.lan_error_url_invalid,
            LanEndpointValidator.validate("http://user:a b@[fe80::1%wlan0]:11434"),
        )
        assertEquals(
            R.string.lan_error_url_invalid,
            LanEndpointValidator.validate("https://[2001:db8::1%25eth0]:443"),
        )
        // The same link-local address without a zone is still a local server.
        assertNull(LanEndpointValidator.validate("http://[fe80::1]:11434"))
        assertTrue(LanEndpointValidator.isPrivateOrLocalHost("fe80::1%wlan0"))
    }

    @Test
    fun httpRefusesAMappedAddressWithALeadingZero() {
        assertEquals(
            R.string.lan_error_url_invalid,
            LanEndpointValidator.validate("http://[::ffff:192.168.001.001]:11434"),
        )
        assertEquals(
            R.string.lan_error_url_invalid,
            LanEndpointValidator.validate("http://[::ffff:192.168.1.01]:11434/v1"),
        )
        assertEquals(
            R.string.lan_error_url_invalid,
            LanEndpointValidator.validate("http://user:p@ss@[::ffff:10.0.0.023]:11434"),
        )
        assertNull(LanEndpointValidator.validate("http://[::ffff:192.168.1.1]:11434"))
        assertNull(LanEndpointValidator.validate("http://[::ffff:10.0.0.1]:11434"))
        assertNull(LanEndpointValidator.validate("http://[fd00:0001::1]:11434"))
        assertEquals(
            "[::ffff:192.168.1.5]:11434",
            settingsLanRowValue("http://user:secret@[::ffff:192.168.1.5]:11434"),
        )
    }
}
