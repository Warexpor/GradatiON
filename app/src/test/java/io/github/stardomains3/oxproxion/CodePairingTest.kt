package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.CodePairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodePairingTest {

    private val pinHex = "12ad505923031e7d75a8972dca3a9e0cede9fe5086fb7c088f42e0b56a9893a9"
    private val pinB64 = "sha256/Eq1QWSMDHn11qJctyjqeDO3p/lCG+3wIj0LgtWqYk6k="
    private val goodUrl = "wss://studio.tailnet.ts.net:7878/v1"
    private val goodToken = "pair-token-abc"

    private fun uri(
        url: String = goodUrl,
        token: String = goodToken,
        fp: String? = pinHex,
        extra: String = "",
    ): String {
        val fpPart = if (fp != null) "&fp=$fp" else ""
        return "gradation://pair?url=${java.net.URLEncoder.encode(url, "UTF-8")}" +
            "&token=${java.net.URLEncoder.encode(token, "UTF-8")}$fpPart$extra"
    }

    @Test
    fun parse_goodHexFingerprint() {
        val r = CodePairing.parse(uri()) as CodePairing.ParseResult.Ok
        assertEquals(goodUrl, r.pairing.url)
        assertEquals(goodToken, r.pairing.token)
        assertEquals(pinB64, r.pairing.fingerprint)
        assertEquals("studio.tailnet.ts.net", r.pairing.nameHint)
    }

    @Test
    fun parse_goodPrefixedPin() {
        val r = CodePairing.parse(uri(fp = pinB64)) as CodePairing.ParseResult.Ok
        assertEquals(pinB64, r.pairing.fingerprint)
    }

    @Test
    fun parse_fpAliasFingerprint() {
        val raw = "gradation://pair?url=${java.net.URLEncoder.encode(goodUrl, "UTF-8")}" +
            "&token=$goodToken&fingerprint=$pinHex"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals(pinB64, r.pairing.fingerprint)
    }

    @Test
    fun parse_tokenAliasT() {
        val raw = "gradation://pair?url=${java.net.URLEncoder.encode(goodUrl, "UTF-8")}" +
            "&t=$goodToken&fp=$pinHex"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals(goodToken, r.pairing.token)
    }

    @Test
    fun parse_urlAliasAddress() {
        val raw = "gradation://pair?address=${java.net.URLEncoder.encode(goodUrl, "UTF-8")}" +
            "&token=$goodToken&pin=$pinHex"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals(goodUrl, r.pairing.url)
        assertEquals(pinB64, r.pairing.fingerprint)
    }

    @Test
    fun parse_missingFpAllowed() {
        val r = CodePairing.parse(uri(fp = null)) as CodePairing.ParseResult.Ok
        assertEquals("", r.pairing.fingerprint)
        assertEquals(goodUrl, r.pairing.url)
    }

    @Test
    fun parse_wsAllowedWithoutFingerprint() {
        val r = CodePairing.parse(uri(url = "ws://192.168.1.10:7878/v1", fp = null))
            as CodePairing.ParseResult.Ok
        assertEquals("ws://192.168.1.10:7878/v1", r.pairing.url)
        assertEquals("", r.pairing.fingerprint)
        assertEquals("192.168.1.10", r.pairing.nameHint)
    }

    @Test
    fun parse_rejectsWsWithFingerprint() {
        val err = CodePairing.parse(uri(url = "ws://192.168.1.10:7878/v1", fp = pinHex))
            as CodePairing.ParseResult.Err
        assertEquals(CodePairing.Reason.PIN_REQUIRES_WSS, err.reason)
    }

    @Test
    fun parse_rejectsWsWithPrefixedPin() {
        val err = CodePairing.parse(uri(url = "ws://192.168.1.10:7878/v1", fp = pinB64))
            as CodePairing.ParseResult.Err
        assertEquals(CodePairing.Reason.PIN_REQUIRES_WSS, err.reason)
    }

    @Test
    fun parse_wssWithFingerprintAllowed() {
        val r = CodePairing.parse(uri(url = goodUrl, fp = pinHex))
            as CodePairing.ParseResult.Ok
        assertEquals(goodUrl, r.pairing.url)
        assertEquals(pinB64, r.pairing.fingerprint)
    }

    @Test
    fun parse_rejectsMissingUrl() {
        val err = CodePairing.parse("gradation://pair?token=$goodToken&fp=$pinHex")
            as CodePairing.ParseResult.Err
        assertEquals(CodePairing.Reason.MISSING_URL, err.reason)
    }

    @Test
    fun parse_rejectsMissingToken() {
        val err = CodePairing.parse(
            "gradation://pair?url=${java.net.URLEncoder.encode(goodUrl, "UTF-8")}&fp=$pinHex"
        ) as CodePairing.ParseResult.Err
        assertEquals(CodePairing.Reason.MISSING_TOKEN, err.reason)
    }

    @Test
    fun parse_rejectsBadUrlScheme() {
        val err = CodePairing.parse(uri(url = "https://studio.example/v1", fp = null))
            as CodePairing.ParseResult.Err
        assertEquals(CodePairing.Reason.BAD_URL, err.reason)
    }

    @Test
    fun parse_rejectsBadFingerprint() {
        val err = CodePairing.parse(uri(fp = "not-a-fingerprint"))
            as CodePairing.ParseResult.Err
        assertEquals(CodePairing.Reason.BAD_FINGERPRINT, err.reason)
    }

    @Test
    fun parse_rejectsWrongScheme() {
        val err = CodePairing.parse("https://pair?url=$goodUrl&token=$goodToken")
            as CodePairing.ParseResult.Err
        assertEquals(CodePairing.Reason.NOT_PAIR_URI, err.reason)
    }

    @Test
    fun parse_rejectsWrongHost() {
        val err = CodePairing.parse("gradation://other?url=$goodUrl&token=$goodToken")
            as CodePairing.ParseResult.Err
        assertEquals(CodePairing.Reason.NOT_PAIR_URI, err.reason)
    }

    @Test
    fun parse_rejectsBlank() {
        assertEquals(CodePairing.Reason.NOT_PAIR_URI, (CodePairing.parse(null) as CodePairing.ParseResult.Err).reason)
        assertEquals(CodePairing.Reason.NOT_PAIR_URI, (CodePairing.parse("  ") as CodePairing.ParseResult.Err).reason)
    }

    @Test
    fun isPairUri_schemeAndHost() {
        assertTrue(CodePairing.isPairUri(uri()))
        assertFalse(CodePairing.isPairUri("https://example.com"))
        assertFalse(CodePairing.isPairUri("gradation://settings"))
    }

    @Test
    fun parse_keepsUnencodedQueryInsideBridgeUrl() {
        val raw = "gradation://pair?url=wss://studio.tailnet.ts.net:7878/v1?a=1&b=2&token=$goodToken"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals("wss://studio.tailnet.ts.net:7878/v1?a=1&b=2", r.pairing.url)
        assertEquals(goodToken, r.pairing.token)
    }

    @Test
    fun parse_queryUrlStillReadsFingerprint() {
        val raw = "gradation://pair?url=wss://h/v1?a=1&b=2&token=$goodToken&fp=$pinHex"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals("wss://h/v1?a=1&b=2", r.pairing.url)
        assertEquals(pinB64, r.pairing.fingerprint)
    }

    @Test
    fun parse_extraAfterTokenDoesNotJoinToken() {
        val raw = "gradation://pair?url=${java.net.URLEncoder.encode(goodUrl, "UTF-8")}" +
            "&token=$goodToken&extra=1"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals(goodUrl, r.pairing.url)
        assertEquals(goodToken, r.pairing.token)
    }

    @Test
    fun parse_spacedFingerprintIsNotRejected() {
        val spaced = pinHex.chunked(2).joinToString(" ")
        val raw = "gradation://pair?url=$goodUrl&token=$goodToken&fp=$spaced"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals(pinB64, r.pairing.fingerprint)
        assertEquals(goodUrl, r.pairing.url)
        assertTrue(CodePairing.isPairUri(raw))
    }

    @Test
    fun parse_hashInsideBridgeUrlDoesNotDropToken() {
        val raw = "gradation://pair?url=wss://h/v1?a=1#section&token=$goodToken"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals("wss://h/v1?a=1#section", r.pairing.url)
        assertEquals(goodToken, r.pairing.token)
    }

    @Test
    fun parse_trailingFragmentDoesNotJoinToken() {
        val raw = "gradation://pair?url=$goodUrl&token=$goodToken#note"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals(goodToken, r.pairing.token)
        assertEquals(goodUrl, r.pairing.url)
    }

    @Test
    fun parse_bridgeQueryKeepsReservedNamesWhenPairingFieldsFollow() {
        val raw = "gradation://pair?url=wss://h/v1?room=1&ws=1&auth=session&b=2&token=$goodToken&fp=$pinHex"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals("wss://h/v1?room=1&ws=1&auth=session&b=2", r.pairing.url)
        assertEquals(goodToken, r.pairing.token)
        assertEquals(pinB64, r.pairing.fingerprint)
    }

    @Test
    fun parse_tokenBeforeUrlStaysThePairingToken() {
        val raw = "gradation://pair?token=$goodToken&url=wss://h/v1?room=1&token=bridge"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals(goodToken, r.pairing.token)
        assertEquals("wss://h/v1?room=1&token=bridge", r.pairing.url)
    }

    @Test
    fun parse_repeatedTokenWithoutBridgeQueryKeepsTheFirst() {
        val raw = "gradation://pair?url=wss://h/v1&token=first-token&token=second-token"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals("first-token", r.pairing.token)
        assertEquals("wss://h/v1", r.pairing.url)
    }

    @Test
    fun parse_tokenInsideBridgeQueryDoesNotStealThePairingToken() {
        val raw = "gradation://pair?url=wss://h/v1?room=1&token=bridge&b=2&token=$goodToken"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals(goodToken, r.pairing.token)
        assertEquals("wss://h/v1?room=1&token=bridge&b=2", r.pairing.url)
    }

    @Test
    fun parse_authAfterTokenDoesNotReplaceIt() {
        val raw = "gradation://pair?url=wss://h/v1?a=1&b=2&token=$goodToken&auth=session"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals(goodToken, r.pairing.token)
        assertEquals("wss://h/v1?a=1&b=2", r.pairing.url)
    }

    @Test
    fun parse_siblingAddressIsNotSwallowed() {
        val raw = "gradation://pair?url=wss://h/v1&address=wss://other.example/v1&token=$goodToken"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals("wss://h/v1", r.pairing.url)
        assertEquals(goodToken, r.pairing.token)
    }

    @Test
    fun parse_bridgeQueryKeepsKeyCaseAndPercentEncoding() {
        val raw = "gradation://pair?url=wss://h/v1?room=1&Session=Ab%2B1&name=hello%20world&token=$goodToken&fp=$pinHex"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals("wss://h/v1?room=1&Session=Ab%2B1&name=hello%20world", r.pairing.url)
        assertEquals(goodToken, r.pairing.token)
        assertEquals(pinB64, r.pairing.fingerprint)
        assertFalse(r.pairing.url.contains(' '))
    }

    @Test
    fun parse_encodedOctetInTheFirstBridgeParameterStays() {
        val raw = "gradation://pair?url=wss://h/v1?name=hello%20world&token=$goodToken"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals("wss://h/v1?name=hello%20world", r.pairing.url)
    }

    @Test
    fun parse_fullyEncodedBridgeUrlStillDecodesOnce() {
        val bridge = "wss://h/v1?name=hello%20world&Session=Ab"
        val raw = "gradation://pair?url=${java.net.URLEncoder.encode(bridge, "UTF-8")}&token=$goodToken"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals(bridge, r.pairing.url)
        assertEquals(goodToken, r.pairing.token)
    }

    @Test
    fun parse_fullyEncodedBridgeUrlDecodesAFormSpace() {
        // URLEncoder writes a space as '+'. The token decoder keeps '+', so this used to
        // save hello+world and turn the real plus into the same character.
        val bridge = "wss://h/v1?name=hello world&q=a+b"
        val raw = "gradation://pair?url=${java.net.URLEncoder.encode(bridge, "UTF-8")}&token=$goodToken"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals(bridge, r.pairing.url)
        assertEquals(goodToken, r.pairing.token)
    }

    @Test
    fun parse_tokenPlusIsNotASpace() {
        val raw = "gradation://pair?url=$goodUrl&token=ab+cd"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals("ab+cd", r.pairing.token)
        assertEquals(goodUrl, r.pairing.url)
    }

    @Test
    fun parse_trailingNoteAfterBridgeHashDoesNotJoinTheToken() {
        val raw = "gradation://pair?url=wss://h/v1?a=1#section&token=$goodToken#note"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals("wss://h/v1?a=1#section", r.pairing.url)
        assertEquals(goodToken, r.pairing.token)
    }

    @Test
    fun parse_trailingNoteAfterBridgeHashDoesNotRejectThePin() {
        val raw = "gradation://pair?url=wss://h/v1?a=1#section&token=$goodToken&fp=$pinHex#note"
        val r = CodePairing.parse(raw) as CodePairing.ParseResult.Ok
        assertEquals("wss://h/v1?a=1#section", r.pairing.url)
        assertEquals(goodToken, r.pairing.token)
        assertEquals(pinB64, r.pairing.fingerprint)
    }

    @Test
    fun parse_hexWithColons() {
        val colons =
            "12:AD:50:59:23:03:1E:7D:75:A8:97:2D:CA:3A:9E:0C:ED:E9:FE:50:86:FB:7C:08:8F:42:E0:B5:6A:98:93:A9"
        val r = CodePairing.parse(uri(fp = java.net.URLEncoder.encode(colons, "UTF-8")))
            as CodePairing.ParseResult.Ok
        assertEquals(pinB64, r.pairing.fingerprint)
    }
}
