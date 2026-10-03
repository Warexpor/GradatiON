package io.github.stardomains3.oxproxion.code

import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * TLS helpers for GradatiON bridge WebSocket connections.
 *
 * Pairing QR carries `fp=<sha256 of leaf cert>` (hex or `sha256/<base64>`). When a [CodeHost]
 * stores a non-blank fingerprint we pin that leaf and reject mismatches. When the fingerprint
 * is empty (legacy hosts / demo / plain `ws://`), callers keep the current system-CA trust
 * behavior — no custom TrustManager and no [CertificatePinner].
 */
object BridgeTls {

    private val HEX = Regex("^[0-9a-fA-F]+$")
    private val BASE64ISH = Regex("^[A-Za-z0-9+/=_-]+$")
    /** Spaces a wrapper inserted. `+` is not one of these: base64 uses it. */
    private val PIN_FOLD = Regex("[\\s\\u00A0\\u2028\\u2029\\u0085]+")

    /**
     * Normalize a pairing `fp=` value to OkHttp pin form `sha256/<base64>`.
     * Accepts hex (optional colons, spaces, or `+` where a form encoder wrote a space),
     * URL-safe or standard base64 of the digest, or an already-prefixed `sha256/...` pin.
     * A line break, tab, or non-breaking space from a wrapped pin is ignored.
     * Blank / unparseable → null. A `+` inside base64 is still a plus: it is only a
     * separator on the hex path, where base64 is the wrong length.
     */
    fun normalizePin(fingerprint: String): String? {
        val raw = fingerprint.trim()
        if (raw.isEmpty()) return null
        val body = when {
            raw.startsWith("sha256/", ignoreCase = true) -> raw.substring(7).trim()
            raw.startsWith("sha256:", ignoreCase = true) -> raw.substring(7).trim()
            else -> raw
        }
        if (body.isEmpty()) return null

        // URLEncoder writes the spaces in "12 AD …" as '+'. Removing those only for the
        // hex check leaves a base64 '+' in place for the digest path below. A wrapped
        // pin also has newlines; those are not part of either encoding.
        val unfolded = PIN_FOLD.replace(body, "")
        if (unfolded.isEmpty()) return null
        val hexCandidate = unfolded.replace(":", "").replace("+", "")
        val digest: ByteArray = when {
            hexCandidate.length == 64 && HEX.matches(hexCandidate) ->
                hexCandidate.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            BASE64ISH.matches(unfolded) && unfolded.length in 40..48 -> {
                val standard = unfolded.replace('-', '+').replace('_', '/')
                runCatching { Base64.getDecoder().decode(standard) }.getOrNull() ?: return null
            }
            else -> return null
        }
        if (digest.size != 32) return null
        return "sha256/" + Base64.getEncoder().encodeToString(digest)
    }

    /** OkHttp pin (`sha256/<base64>`) of the DER-encoded certificate. */
    fun pinOf(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return "sha256/" + Base64.getEncoder().encodeToString(digest)
    }

    /** Hostname (or IP) for [CertificatePinner], from a ws(s):// or http(s):// URL. */
    fun hostPatternOf(url: String): String {
        val normalized = url.trim().let {
            when {
                it.startsWith("wss://", ignoreCase = true) -> "https://" + it.substring(6)
                it.startsWith("ws://", ignoreCase = true) -> "http://" + it.substring(5)
                else -> it
            }
        }
        return normalized.toHttpUrlOrNull()?.host?.takeIf { it.isNotBlank() } ?: "localhost"
    }

    /**
     * OkHttp client for a bridge URL.
     *
     * - Blank / unparseable [fingerprint]: returns [base] unchanged (legacy system-CA trust).
     * - Cleartext `ws://` URL: returns [base] unchanged — TLS pinning is inert without TLS.
     *   Callers must reject fingerprint + `ws://` at pairing (see [pinRequiresWss]).
     * - Non-blank pin + `wss://`: [CertificatePinner] for [hostPatternOf] plus a TrustManager
     *   that only accepts a leaf whose SHA-256 matches; hostname verification is skipped
     *   because the pin from the pairing QR is the identity.
     */
    fun clientFor(
        fingerprint: String,
        url: String,
        base: OkHttpClient = defaultBaseClient()
    ): OkHttpClient {
        val pin = normalizePin(fingerprint) ?: return base
        // Pinning without TLS would be silent false security; never install an inert pinner.
        if (isCleartextWs(url)) return base
        val host = hostPatternOf(url)
        val trust = pinnedTrustManager(pin)
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trust), null)
        }
        val pinner = CertificatePinner.Builder().add(host, pin).build()
        return base.newBuilder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .certificatePinner(pinner)
            .build()
    }

    /** True when [url] is a cleartext WebSocket (`ws://`). */
    fun isCleartextWs(url: String): Boolean =
        url.trim().startsWith("ws://", ignoreCase = true)

    /**
     * True when a usable pin is present but [url] is cleartext — pinning cannot protect
     * the bearer token over `ws://`. Pairing / host UI must reject this combination and
     * require `wss://` (or an explicit no-pin cleartext LAN path).
     */
    fun pinRequiresWss(fingerprint: String, url: String): Boolean =
        shouldPin(fingerprint) && isCleartextWs(url)

    /** True when [fingerprint] is non-blank and normalizes to a pin. */
    fun shouldPin(fingerprint: String): Boolean = normalizePin(fingerprint) != null

    /**
     * TrustManager that accepts only a leaf certificate whose SHA-256 pin equals [expectedPin]
     * (`sha256/<base64>`). Used for self-signed bridges where the system CA store would reject
     * the cert before [CertificatePinner] runs.
     */
    fun pinnedTrustManager(expectedPin: String): X509TrustManager {
        val expected = normalizePin(expectedPin)
            ?: throw IllegalArgumentException("invalid pin: $expectedPin")
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("client auth not supported")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw CertificateException("empty certificate chain")
                val actual = pinOf(chain[0])
                if (actual != expected) {
                    throw CertificateException("bridge certificate pin mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    /** Shared base client (pings / timeouts) matching [WebSocketTransport] defaults. */
    fun defaultBaseClient(): OkHttpClient = WebSocketTransport.defaultClient

    /** Same timeouts as [WebSocketTransport.defaultClient], for tests that need a fresh builder. */
    fun freshBaseClient(): OkHttpClient =
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
}
