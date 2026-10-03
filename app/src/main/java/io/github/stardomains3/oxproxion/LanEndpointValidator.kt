package io.github.stardomains3.oxproxion

import androidx.annotation.StringRes
import java.net.URI

/**
 * LAN endpoint rules: HTTPS anywhere; HTTP only to loopback, private, link-local,
 * unique-local IPv6, CGNAT (100.64/10), and `.local` hosts (homelab). Blocks
 * cleartext to public IP literals.
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
        // Zone id (fe80::1%wlan0) is the interface, not part of the address.
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]").substringBefore('%')
        if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        if (h.endsWith(".local")) return true
        if (':' in h) return isPrivateIpv6(h)
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 10 ||
                a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                // RFC 6598 CGNAT. Tailscale uses this range; it is not a public literal.
                (a == 100 && b in 64..127)
        }
        // Non-IP hostname (router DNS / mDNS without .local) — allow for homelab HTTP
        return parts.size >= 1 && parts[0].any { it.isLetter() }
    }

    /**
     * Link-local (fe80::/10) and unique-local (fc00::/7), plus an IPv4-mapped form of a
     * private IPv4 address. A global literal such as 2001:db8::1 stays public.
     */
    private fun isPrivateIpv6(host: String): Boolean {
        val h = ipv6Hextets(host) ?: return false
        val loopback = (0 until 7).all { h[it] == 0 } && h[7] == 1
        if (loopback) return true
        if (h[0] in 0xfe80..0xfebf) return true
        if ((h[0] and 0xfe00) == 0xfc00) return true
        val mapped = mappedIpv4(h) ?: return false
        return isPrivateOrLocalHost(mapped)
    }

    /** 0:0:0:0:0:ffff:x:x → dotted IPv4, or null when this is not an IPv4-mapped address. */
    private fun mappedIpv4(h: IntArray): String? {
        if (h[0] or h[1] or h[2] or h[3] or h[4] != 0 || h[5] != 0xffff) return null
        val hi = h[6]
        val lo = h[7]
        return "${hi shr 8}.${hi and 0xff}.${lo shr 8}.${lo and 0xff}"
    }

    /** Eight hextets, or null when [raw] is not an IPv6 literal. A dotted tail is expanded. */
    private fun ipv6Hextets(raw: String): IntArray? {
        var s = raw
        val dot = s.lastIndexOf('.')
        if (dot >= 0) {
            val colon = s.lastIndexOf(':')
            if (colon < 0 || colon > dot) return null
            val nums = s.substring(colon + 1).split('.')
            if (nums.size != 4) return null
            val octets = nums.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
            val hi = (octets[0] shl 8) or octets[1]
            val lo = (octets[2] shl 8) or octets[3]
            s = s.substring(0, colon + 1) + hi.toString(16) + ":" + lo.toString(16)
        }
        val first = s.indexOf("::")
        if (first != s.lastIndexOf("::")) return null
        val sides = if (first >= 0) listOf(s.substring(0, first), s.substring(first + 2)) else listOf(s)
        val groups = sides.map { side ->
            if (side.isEmpty()) IntArray(0) else {
                val parts = side.split(':')
                if (parts.any { it.isEmpty() || it.length > 4 }) return null
                IntArray(parts.size) { i -> parts[i].toIntOrNull(16)?.takeIf { it in 0..0xffff } ?: return null }
            }
        }
        val known = groups.sumOf { it.size }
        if (first < 0) return if (known == 8) groups[0] else null
        val missing = 8 - known
        if (missing < 1) return null
        return IntArray(8).also { out ->
            groups[0].copyInto(out)
            groups[1].copyInto(out, 8 - groups[1].size)
        }
    }
}
