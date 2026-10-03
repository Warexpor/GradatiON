package io.github.stardomains3.oxproxion

import androidx.annotation.StringRes
import java.net.URI

/**
 * LAN endpoint rules: HTTPS anywhere; HTTP only to loopback / private / link-local /
 * `.local` hosts (homelab). Blocks cleartext to public IP literals.
 */
object LanEndpointValidator {
    /** @return a string resource for the problem, or null if valid (text lives in strings, not here). */
    @StringRes
    fun validate(rawUrl: String): Int? {
        val url = rawUrl.trim()
        if (url.isBlank()) return R.string.lan_error_url_blank
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return R.string.lan_error_url_invalid
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return R.string.lan_error_url_scheme
        }
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty()) return R.string.lan_error_url_host
        if (scheme == "http" && !isPrivateOrLocalHost(host)) {
            return R.string.lan_error_url_http_public
        }
        return null
    }

    /**
     * The base the app appends `/v1/...` to. A browser paste often has a trailing slash,
     * and an OpenAI-compatible base often already ends in `/v1`. Either one used to request
     * `//v1` or `/v1/v1`, and the local server answered 404.
     */
    fun normalizedBase(raw: String): String {
        var url = raw.trim()
        while (url.endsWith("/")) url = url.dropLast(1)
        val slash = url.length - 3
        // A path segment, not the "//" in "http://v1".
        if (slash > 0 &&
            url[slash - 1] != '/' &&
            url[slash - 1] != ':' &&
            url.regionMatches(slash, "/v1", 0, 3, ignoreCase = true)
        ) {
            url = url.dropLast(3)
            while (url.endsWith("/")) url = url.dropLast(1)
        }
        return url
    }

    fun isPrivateOrLocalHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        if (h.endsWith(".local")) return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 10 ||
                a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254)
        }
        // Non-IP hostname (router DNS / mDNS without .local) — allow for homelab HTTP
        return !h.contains(':') && parts.size >= 1 && parts[0].any { it.isLetter() }
    }
}
