package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.BridgeTls
import okhttp3.CertificatePinner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Pure (no network) coverage of [BridgeTls] pin normalization and the pinned TrustManager.
 */
class BridgeTlsTest {

    private val pinB64 = "sha256/Eq1QWSMDHn11qJctyjqeDO3p/lCG+3wIj0LgtWqYk6k="
    private val pinHex = "12ad505923031e7d75a8972dca3a9e0cede9fe5086fb7c088f42e0b56a9893a9"
    private val pinHexColons =
        "12:AD:50:59:23:03:1E:7D:75:A8:97:2D:CA:3A:9E:0C:ED:E9:FE:50:86:FB:7C:08:8F:42:E0:B5:6A:98:93:A9"

    private val certPem = """
-----BEGIN CERTIFICATE-----
MIIDDDCCAfSgAwIBAgITJf7zO1Jz/5ZAR+QAhG85/Dy/+DANBgkqhkiG9w0BAQsF
ADAWMRQwEgYDVQQDDAticmlkZ2UudGVzdDAeFw0yNjA5MjYyMTQwMjJaFw0yNzA5
MjYyMTQwMjJaMBYxFDASBgNVBAMMC2JyaWRnZS50ZXN0MIIBIjANBgkqhkiG9w0B
AQEFAAOCAQ8AMIIBCgKCAQEA3UZzVITLdg1p5/AQq+W0ViO0mT2Pax9JBBifkfsV
yByHWL0ElFZ41nHzncu0He2t4kkngNQ606F99aCAZXTOBRKgq/j82WnywKhc5M2O
2WnzyadDIgJY/RxC2fXPr1Q4SVlnoYujORwfLi7ckaA0nl83hbn9lBntcAywWfXj
34gvbGWqSbVYU8JBzZWrwMA2Gtvq23qXoto4TZcCh8jJpZQa3U1HPnsYRqdtwSge
3vsEkL+Je7d6qKRXD+XdTTZGgI57IgDDZx/mnMtbGl1Qi8V+kFvElcNL33n+8cQx
DfqweyKrVYmxV6zG9P8VRg4KccM5reabPHDvHR9L3X5NdwIDAQABo1MwUTAdBgNV
HQ4EFgQURO/CajlA5e1ZpGvsIDWB5nHURu4wHwYDVR0jBBgwFoAURO/CajlA5e1Z
pGvsIDWB5nHURu4wDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEA
LyXZcTQeCtMOVb2uk3gj2/gVSgqBlYFjZZk4G0ix4jgHm4yB2HMDXfsTG83DeP3m
q5ShSJxbaLZXgNrug++amo9bcNy5pZXRFS04v18eo8+2Dnx9Ho07sEc9Q+LdZdpk
AFfL6LjR2Zy9llj2YqK6GH3U4Z4GQJ1j1S9aXeu3sHh600FvhdabmUsiw6zga6pd
uQcsLlzuCs03ikQy7Xli8oY5GqcNPLW9iok80GwofoFDUrJnmqIzel/kZxHFWR3q
+XWGMTWXTpBNC0+UXYmr8/HwAJZItGPZTurUXS/M/NdWq05qfK011G7vXIVGqjwg
xPMEFWB2A/uH38YwTrzmLA==
-----END CERTIFICATE-----
    """.trimIndent()

    private val otherCertPem = """
-----BEGIN CERTIFICATE-----
MIIDCzCCAfOgAwIBAgIULXpvIo0/TUjQyOsRW2APuUKmeWIwDQYJKoZIhvcNAQEL
BQAwFTETMBEGA1UEAwwKb3RoZXIudGVzdDAeFw0yNjA5MjYyMTQwMjJaFw0yNzA5
MjYyMTQwMjJaMBUxEzARBgNVBAMMCm90aGVyLnRlc3QwggEiMA0GCSqGSIb3DQEB
AQUAA4IBDwAwggEKAoIBAQDEQs7fhDnBut8AGcg6wBx6t3o++l9xuLOdYCTg5ZmA
7+YW3Sfe4Jgkw2QeC/HrKf2QesH5fGuw7HFCj0xZc8Pr91phU9aIorg+VJRtUUgi
mE2ZD36XAC6dIExp6WGhaZOouyFBsY+sNyvq/7iNZTYgYOMIyczsI3QnbbO5lJP0
h3EpoUXJdpmgi9VvB2927QGycdAtvgfC1w4MtvierjkwuMsGnt4+GIQRgGAigQkc
kHhp10RPrNfgktYz+l9FqmxgG69AVChiA6RHjBgeeAg7uqoOKxrWCs64M7eqAnpu
ox/Oo5eEDU1oCJy/oZSDLkFcGmfwcpq3n5ZBqmcpFOBvAgMBAAGjUzBRMB0GA1Ud
DgQWBBSb6wCZCEglK1536CzR2kMIdmQp/jAfBgNVHSMEGDAWgBSb6wCZCEglK153
6CzR2kMIdmQp/jAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQB8
YRhxBZXku5lL5dgVuoSwLI5mYF0msX8MiQJYKwOm4wwuEBNhchXLdjuqX62RtRiT
4VlzlVbGWVYZreCtUXlzE4eZEEeXm/Tvm2bNLOxh8eyA7Up2KApTwQ2A5z0WA19S
u3eEuCp+6SkM5FNPY7IvJ6nOC50xMuPAxSz0yBVzOgxwjvIKj8lbEXzcq+7J6oir
eVsffXSdkVVjA55mn2sWytcaCSJdKQsmvX/SOTF4G93WSOMSmO7k0ZYv2+q7/DdW
UWLmk0AsNktFAk24PMZAAnqetT8LafO4hYoR38JPcaWTzF7Cv2gyWsjETozOMU99
nwonylCc+6YBfSJvgp8P
-----END CERTIFICATE-----
    """.trimIndent()

    private fun loadCert(pem: String): X509Certificate {
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
    }

    @Test
    fun normalizePin_blankIsNull() {
        assertNull(BridgeTls.normalizePin(""))
        assertNull(BridgeTls.normalizePin("   "))
        assertFalse(BridgeTls.shouldPin(""))
    }

    @Test
    fun normalizePin_hexWithAndWithoutColons() {
        assertEquals(pinB64, BridgeTls.normalizePin(pinHex))
        assertEquals(pinB64, BridgeTls.normalizePin(pinHexColons))
        assertEquals(pinB64, BridgeTls.normalizePin(pinHex.uppercase()))
    }

    @Test
    fun normalizePin_alreadyPrefixed() {
        assertEquals(pinB64, BridgeTls.normalizePin(pinB64))
        assertEquals(pinB64, BridgeTls.normalizePin("SHA256/" + pinB64.removePrefix("sha256/")))
    }

    @Test
    fun normalizePin_rawBase64Digest() {
        assertEquals(pinB64, BridgeTls.normalizePin("Eq1QWSMDHn11qJctyjqeDO3p/lCG+3wIj0LgtWqYk6k="))
    }

    @Test
    fun normalizePin_garbageIsNull() {
        assertNull(BridgeTls.normalizePin("not-a-fingerprint"))
        assertNull(BridgeTls.normalizePin("abcd"))
        assertNull(BridgeTls.normalizePin("sha256/"))
    }

    @Test
    fun pinOf_matchesKnownCert() {
        val cert = loadCert(certPem)
        assertEquals(pinB64, BridgeTls.pinOf(cert))
    }

    @Test
    fun hostPatternOf_stripsWsScheme() {
        assertEquals("laptop.tailnet.ts.net", BridgeTls.hostPatternOf("wss://laptop.tailnet.ts.net:7878/v1"))
        assertEquals("192.168.1.10", BridgeTls.hostPatternOf("ws://192.168.1.10:7878/"))
        assertEquals("bridge.test", BridgeTls.hostPatternOf("https://bridge.test/path"))
    }

    @Test
    fun pinnedTrustManager_acceptsMatchingLeaf() {
        val cert = loadCert(certPem)
        val tm = BridgeTls.pinnedTrustManager(pinHex)
        tm.checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test
    fun pinnedTrustManager_rejectsMismatch() {
        val other = loadCert(otherCertPem)
        val tm = BridgeTls.pinnedTrustManager(pinB64)
        try {
            tm.checkServerTrusted(arrayOf(other), "RSA")
            fail("expected CertificateException")
        } catch (e: CertificateException) {
            assertTrue(e.message!!.contains("pin mismatch"))
        }
    }

    @Test
    fun pinnedTrustManager_rejectsEmptyChain() {
        val tm = BridgeTls.pinnedTrustManager(pinB64)
        try {
            tm.checkServerTrusted(emptyArray(), "RSA")
            fail("expected CertificateException")
        } catch (_: CertificateException) {
            // ok
        }
    }

    @Test
    fun clientFor_blankFingerprintReturnsSameBase() {
        val base = BridgeTls.freshBaseClient()
        assertSame(base, BridgeTls.clientFor("", "wss://bridge.test/v1", base))
        assertSame(base, BridgeTls.clientFor("   ", "wss://bridge.test/v1", base))
    }

    @Test
    fun clientFor_nonBlankBuildsPinnedClient() {
        val base = BridgeTls.freshBaseClient()
        val pinned = BridgeTls.clientFor(pinHex, "wss://bridge.test:7878/v1", base)
        assertNotSame(base, pinned)
        val expected = CertificatePinner.Builder()
            .add("bridge.test", pinB64)
            .build()
        assertEquals(expected.pins, pinned.certificatePinner.pins)
    }

    @Test
    fun pinRequiresWss_trueForCleartextWithPin() {
        assertTrue(BridgeTls.pinRequiresWss(pinHex, "ws://192.168.1.10:7878/v1"))
        assertTrue(BridgeTls.pinRequiresWss(pinB64, "WS://laptop.local/v1"))
        assertTrue(BridgeTls.isCleartextWs("ws://192.168.1.10:7878/v1"))
    }

    @Test
    fun pinRequiresWss_falseWhenNoPinOrWss() {
        assertFalse(BridgeTls.pinRequiresWss("", "ws://192.168.1.10:7878/v1"))
        assertFalse(BridgeTls.pinRequiresWss("   ", "ws://192.168.1.10:7878/v1"))
        assertFalse(BridgeTls.pinRequiresWss(pinHex, "wss://bridge.test:7878/v1"))
        assertFalse(BridgeTls.pinRequiresWss("garbage", "ws://192.168.1.10:7878/v1"))
        assertFalse(BridgeTls.isCleartextWs("wss://bridge.test/v1"))
    }

    @Test
    fun clientFor_cleartextWithPinDoesNotInstallPinner() {
        // Defense in depth: never install an inert pin on ws:// (pairing rejects this combo).
        val base = BridgeTls.freshBaseClient()
        val client = BridgeTls.clientFor(pinHex, "ws://192.168.1.10:7878/v1", base)
        assertSame(base, client)
    }
}
