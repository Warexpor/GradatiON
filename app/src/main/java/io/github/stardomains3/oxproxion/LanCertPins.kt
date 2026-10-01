package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.BridgeTls
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Trust on first use for self-signed certificates on a LAN model server.
 *
 * The first certificate a host:port presents is remembered (SHA-256 of the leaf, the same
 * `sha256/<base64>` form [BridgeTls] uses); from then on only that certificate is accepted.
 * A self-signed LAN certificate often has no matching name, so the name is not checked, but
 * only a pinned certificate gets that pass. Turning the setting off forgets every pin.
 */
class LanCertPins(private val store: Store) {

    /** Where pins live: [SharedPreferencesHelper] in the app, a map in tests. */
    interface Store {
        fun pinFor(hostPort: String): String?
        fun savePin(hostPort: String, pin: String)
    }

    enum class Decision {
        /** Nothing was pinned for this host yet; the certificate is now. */
        PinnedFirstUse,
        /** Matches the pin. */
        Accepted,
        /** A different certificate than the pin: refuse. */
        Mismatch
    }

    /** Decides for [hostPort], remembering [actualPin] when the host has none yet. */
    fun check(hostPort: String, actualPin: String): Decision {
        val pinned = store.pinFor(hostPort)
        if (pinned == null) {
            store.savePin(hostPort, actualPin)
            return Decision.PinnedFirstUse
        }
        return if (pinned == actualPin) Decision.Accepted else Decision.Mismatch
    }

    companion object {
        fun hostPortOf(url: HttpUrl): String = "${url.host}:${url.port}"
    }
}

/** The certificate on a LAN host is not the one that was pinned. The message is shown to the user. */
class LanCertChangedException(message: String) : SSLPeerUnverifiedException(message)

/**
 * Network interceptor: runs after the TLS handshake and before the request is written, so a
 * certificate that fails the pin never receives the bearer token or the chat. The handshake
 * itself has to accept an unknown certificate (that is what makes first use possible).
 */
class LanCertPinInterceptor(
    private val pins: LanCertPins,
    private val changedMessage: String
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (url.isHttps) {
            val leaf = chain.connection()?.handshake()?.peerCertificates?.firstOrNull() as? X509Certificate
                ?: throw LanCertChangedException(changedMessage)
            val decision = pins.check(LanCertPins.hostPortOf(url), BridgeTls.pinOf(leaf))
            if (decision == LanCertPins.Decision.Mismatch) throw LanCertChangedException(changedMessage)
        }
        return chain.proceed(chain.request())
    }
}
