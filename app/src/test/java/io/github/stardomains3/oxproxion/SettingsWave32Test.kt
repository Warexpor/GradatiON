package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings wave 32: homelab hosts Java's parser drops, a query or fragment on the local
 * server address, and approximate location as a tool grant.
 * Run: ./gradlew :app:testDebugUnitTest --tests io.github.stardomains3.oxproxion.SettingsWave32Test
 */
class SettingsWave32Test {

    @Test
    fun httpAllowsAnUnderscoreHostAndAnAtSignInThePassword() {
        assertNull(LanEndpointValidator.validate("http://my_nas.local:11434"))
        assertNull(LanEndpointValidator.validate("http://nas_1.home:11434/v1"))
        assertNull(LanEndpointValidator.validate("http://user:p@ss@10.0.0.23:11434"))
        assertNull(LanEndpointValidator.validate("http://user:p@ss@192.168.1.5:11434/v1"))
        assertNull(LanEndpointValidator.validate("https://my_nas.local:11434"))
    }

    @Test
    fun httpStillRejectsAPublicLiteralWhenThePasswordContainsAnAtSign() {
        assertEquals(
            R.string.lan_error_url_http_public,
            LanEndpointValidator.validate("http://user:p@ss@8.8.8.8"),
        )
        assertEquals(
            R.string.lan_error_url_http_public,
            LanEndpointValidator.validate("http://user:p@ss@1.2.3.4:80"),
        )
        // A dotted literal Java cannot parse is still not treated as a hostname.
        assertEquals(R.string.lan_error_url_host, LanEndpointValidator.validate("http://0x7f.0.0.1:11434"))
    }

    @Test
    fun portMustBeOpenable() {
        assertEquals(R.string.lan_error_url_port, LanEndpointValidator.validate("http://10.0.0.23:0"))
        assertEquals(R.string.lan_error_url_port, LanEndpointValidator.validate("http://10.0.0.23:65536"))
        assertEquals(R.string.lan_error_url_port, LanEndpointValidator.validate("http://[fd00::1]:65536"))
        assertEquals(R.string.lan_error_url_port, LanEndpointValidator.validate("https://example.com:0"))
        assertNull(LanEndpointValidator.validate("http://10.0.0.23:11434"))
        assertNull(LanEndpointValidator.validate("http://172.16.0.2"))
        assertNull(LanEndpointValidator.validate("https://example.com:443"))
    }

    @Test
    fun lanRowHidesUserinfoWhenTheHostDidNotParse() {
        assertEquals("my_nas.local:11434", settingsLanRowValue("http://user:secret@my_nas.local:11434"))
        assertEquals("10.0.0.23:11434", settingsLanRowValue("http://user:p@ss@10.0.0.23:11434"))
        assertEquals("10.0.0.23:11434", settingsLanRowValue("http://user:sec ret@10.0.0.23:11434"))
        assertEquals("[fd00::1]:11434", settingsLanRowValue("http://user:secret@[fd00::1]:11434/v1"))
        assertEquals("nas.local", settingsLanRowValue("https://nas.local"))
    }

    @Test
    fun requestPathIsInsertedBeforeTheQueryAndTheFragmentIsDropped() {
        assertEquals(
            "http://10.0.0.23:11434?x=1",
            LanEndpointValidator.normalizedBase("http://10.0.0.23:11434/v1?x=1"),
        )
        assertEquals(
            "http://10.0.0.23:11434/v1/models?x=1",
            LanEndpointValidator.requestUrl("http://10.0.0.23:11434/v1?x=1", "/v1/models"),
        )
        assertEquals(
            "http://10.0.0.23:11434/v1/chat/completions",
            LanEndpointValidator.requestUrl("http://10.0.0.23:11434#section", "/v1/chat/completions"),
        )
        assertEquals(
            "http://10.0.0.23:11434/api/tags?token=abc",
            LanEndpointValidator.requestUrl("http://10.0.0.23:11434/v1/?token=abc#note", "/api/tags"),
        )
        // No query: the same path the old string append produced.
        assertEquals(
            "http://10.0.0.23:11434/v1/models",
            LanEndpointValidator.requestUrl("http://10.0.0.23:11434", "/v1/models"),
        )
        assertEquals(
            "http://10.0.0.23:11434/v1/models",
            LanEndpointValidator.requestUrl("http://10.0.0.23:11434/v1/", "/v1/models"),
        )
    }

    @Test
    fun approximateLocationHoldsTheLocationGrant() {
        assertTrue(ToolItem.locationGrantHeld(fineGranted = true, coarseGranted = false))
        assertTrue(ToolItem.locationGrantHeld(fineGranted = false, coarseGranted = true))
        assertFalse(ToolItem.locationGrantHeld(fineGranted = false, coarseGranted = false))
        assertTrue(
            ToolItem.toolSwitchEnabled(
                needsPermission = true,
                permissionGranted = ToolItem.locationGrantHeld(fineGranted = false, coarseGranted = true),
                toolOn = false,
            ),
        )
    }
}
