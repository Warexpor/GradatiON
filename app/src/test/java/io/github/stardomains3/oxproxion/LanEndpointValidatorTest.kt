package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanEndpointValidatorTest {
    @Test
    fun allowsPrivateHttp() {
        assertNull(LanEndpointValidator.validate("http://192.168.1.10:11434"))
        assertNull(LanEndpointValidator.validate("http://10.0.0.5:1234"))
        assertNull(LanEndpointValidator.validate("http://172.16.0.2"))
        assertNull(LanEndpointValidator.validate("http://localhost:8080"))
    }

    @Test
    fun rejectsPublicHttpIp() {
        assertNotNull(LanEndpointValidator.validate("http://8.8.8.8:80"))
        assertEquals(R.string.lan_error_url_http_public, LanEndpointValidator.validate("http://8.8.8.8:80"))
    }

    @Test
    fun saysWhatIsWrongAsAResource() {
        assertEquals(R.string.lan_error_url_blank, LanEndpointValidator.validate("  "))
        assertEquals(R.string.lan_error_url_scheme, LanEndpointValidator.validate("ftp://192.168.1.2"))
        assertEquals(R.string.lan_error_url_host, LanEndpointValidator.validate("http:///v1"))
    }

    @Test
    fun allowsHttpsAnywhere() {
        assertNull(LanEndpointValidator.validate("https://example.com/v1"))
    }

    @Test
    fun privateHostHelper() {
        assertTrue(LanEndpointValidator.isPrivateOrLocalHost("192.168.0.1"))
        assertTrue(LanEndpointValidator.isPrivateOrLocalHost("nas.local"))
    }
}
