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
        val uri = runCatching { URI(url) }.getOrNull()
        // `user:p@ss@[fd00::1]` and `user:a b@10.0.0.23` throw. The first `@` makes Java
        // treat the brackets (or the space) as part of the host, so Save used to say the
        // URL was invalid and never read the address after the last `@`. OkHttp can call it.
        if (uri == null) return validateUnparsed(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return R.string.lan_error_url_scheme
        }
        val endpoint = endpointHostPort(uri) ?: return R.string.lan_error_url_host
        return endpointProblem(scheme, endpoint)
    }

    /**
     * Authority after `://` when [URI] threw. Only a host we could hand to the HTTP client
     * counts: no spaces, and an IPv6 address has to be in brackets (an unbracketed `fd00::1`
     * is not a URL OkHttp can open).
     */
    private fun validateUnparsed(url: String): Int? {
        val scheme = schemeBeforeAuthority(url) ?: return R.string.lan_error_url_invalid
        if (scheme != "http" && scheme != "https") return R.string.lan_error_url_scheme
        val endpoint = endpointFromRawAuthority(url) ?: return R.string.lan_error_url_host
        if (!recoverableHost(endpoint.host)) return R.string.lan_error_url_invalid
        return endpointProblem(scheme, endpoint)
    }

    private fun schemeBeforeAuthority(url: String): String? {
        val split = url.indexOf("://")
        if (split <= 0) return null
        val scheme = url.substring(0, split)
        if (scheme.isEmpty() || scheme.any { !it.isLetter() }) return null
        return scheme.lowercase()
    }

    private fun endpointFromRawAuthority(url: String): HostPort? {
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return null
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return null
        return hostPortFromAuthority(authority)
    }

    private fun recoverableHost(host: String): Boolean {
        if (host.isEmpty() || host.any { it.isWhitespace() || it == '@' || it == '/' }) return false
        val bracketed = host.startsWith("[") && host.endsWith("]") && host.length > 2
        val bare = if (bracketed) host.substring(1, host.length - 1) else host
        if (bare.isEmpty() || bare.any { it.isWhitespace() }) return false
        // A colon outside brackets is an unbracketed IPv6 address, which the client rejects.
        if (':' in bare && !bracketed) return false
        return true
    }

    private fun endpointProblem(scheme: String, endpoint: HostPort): Int? {
        // 0 and anything above 65535 are not a port a request can open. Java's URI keeps them.
        if (endpoint.port != -1 && endpoint.port !in 1..65535) return R.string.lan_error_url_port
        // `fd00::1` without brackets is not a URL the HTTP client can open. A bracketed literal
        // arrives as `[fd00::1]` from the parser, or from the authority fallback.
        val host = endpoint.host
        val bracketed = host.startsWith("[") && host.endsWith("]") && host.length > 2
        if (':' in host && !bracketed) return R.string.lan_error_url_invalid
        val bare = if (bracketed) host.substring(1, host.length - 1) else host
        // A zone id (`fe80::1%wlan0`, or `%25` for the percent) is a URL OkHttp will not open.
        // Save used to accept it, and the models request then failed.
        if (':' in bare && '%' in bare) return R.string.lan_error_url_invalid
        // A dotted tail with a leading zero (`::ffff:192.168.001.001`) parses here and then
        // OkHttp rejects the request. A literal this parser cannot read is the same failure.
        if (':' in bare && ipv6Hextets(bare) == null) return R.string.lan_error_url_invalid
        if (scheme == "http" && !isPrivateOrLocalHost(host)) {
            return R.string.lan_error_url_http_public
        }
        return null
    }

    /**
     * Host and port of a URL [java.net.URI] managed to parse.
     * [URI.getHost] is null for a hostname that contains `_` (common on a homelab) and for a
     * password that contains an unencoded `@`, even though the authority still has the host.
     * Those used to fail as "needs a host". A host with no `_` and a single `@` is left alone,
     * so a literal Java cannot parse stays a bad URL. One trailing dot on an IPv4 address
     * (`10.0.0.23.`) is the same miss: [URI.getHost] is null, and OkHttp still opens it.
     */
    internal fun endpointHostPort(raw: String): HostPort? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        return endpointHostPort(uri)
    }

    private fun endpointHostPort(uri: URI): HostPort? {
        val direct = uri.host?.trim()?.takeIf { it.isNotEmpty() }
        if (direct != null) return HostPort(direct, uri.port)
        val auth = uri.rawAuthority?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = hostPortFromAuthority(auth) ?: return null
        if ('_' !in auth && auth.count { it == '@' } <= 1 && !recoverableTrailingDot(parsed.host)) {
            return null
        }
        return parsed
    }

    /**
     * One trailing dot, and the name under it is a host Java will parse.
     * `10.0.0.23..` and `0x7f.0.0.1.` stay out: the client rejects the first, and the
     * second is the dotted literal this check already refuses.
     */
    private fun recoverableTrailingDot(host: String): Boolean {
        if (!host.endsWith('.') || host.endsWith("..")) return false
        val stripped = host.dropLast(1)
        if (stripped.isEmpty()) return false
        val again = runCatching { URI("http://$stripped/") }.getOrNull() ?: return false
        return !again.host.isNullOrEmpty()
    }

    /** Authority after userinfo, which ends at the last `@`. Brackets keep an IPv6 address intact. */
    private fun hostPortFromAuthority(authority: String): HostPort? {
        val at = authority.lastIndexOf('@')
        val hostport = if (at >= 0) authority.substring(at + 1) else authority
        if (hostport.isEmpty()) return null
        if (hostport.startsWith("[")) {
            val end = hostport.indexOf(']')
            if (end < 1) return null
            val host = hostport.substring(0, end + 1)
            val rest = hostport.substring(end + 1)
            if (rest.isEmpty()) return HostPort(host, -1)
            if (!rest.startsWith(":") || rest.length < 2) return null
            val port = rest.substring(1).toIntOrNull() ?: return null
            return HostPort(host, port)
        }
        val colon = hostport.lastIndexOf(':')
        if (colon > 0) {
            val portText = hostport.substring(colon + 1)
            if (portText.isNotEmpty() && portText.all { it.isDigit() }) {
                val port = portText.toIntOrNull() ?: return null
                val host = hostport.substring(0, colon)
                if (host.isEmpty()) return null
                return HostPort(host, port)
            }
        }
        return HostPort(hostport, -1)
    }

    internal data class HostPort(val host: String, val port: Int)

    /**
     * The base the app appends `/v1/...` to. A browser paste often has a trailing slash,
     * and an OpenAI-compatible base often already ends in `/v1`. Either one used to request
     * `//v1` or `/v1/v1`, and the local server answered 404.
     * A pasted models or chat URL (`/v1/models`, `/api/tags`, and the rest) is the same miss
     * one segment further on: the row showed the host, and the request called that route twice.
     * A `%` that is not an escape (`100%`, `p%ss`) is stored as `%25`. The client will not
     * open the raw form, so Save used to keep a URL that failed on every request.
     */
    fun normalizedBase(raw: String): String {
        var url = encodeBarePercents(raw.trim())
        // A fragment is not sent, and a path appended after '#' or '?' becomes part of it.
        val hash = url.indexOf('#')
        if (hash >= 0) url = url.substring(0, hash)
        val queryAt = url.indexOf('?')
        val query = if (queryAt >= 0) url.substring(queryAt + 1) else ""
        if (queryAt >= 0) url = url.substring(0, queryAt)
        url = stripOpenAiSuffix(url)
        if (query.isNotEmpty()) url = "$url?$query"
        return url
    }

    /**
     * `%25` is already the character `%`. A lone `%`, or `%` plus a non-hex pair, is not an
     * escape the HTTP client will open. Encoding only that `%` leaves a real `%3D` alone.
     */
    private fun encodeBarePercents(raw: String): String {
        if ('%' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '%' && i + 2 < raw.length && isHexDigit(raw[i + 1]) && isHexDigit(raw[i + 2])) {
                out.append(raw, i, i + 3)
                i += 3
            } else if (raw[i] == '%') {
                out.append("%25")
                i += 1
            } else {
                out.append(raw[i])
                i += 1
            }
        }
        return out.toString()
    }

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /**
     * [base] plus [path], with [path] before any query. String append put `/v1/models` inside
     * `?token=` or after `#`, so the server saw the base path and never the route.
     */
    fun requestUrl(base: String, path: String): String {
        val normalized = normalizedBase(base)
        val queryAt = normalized.indexOf('?')
        val root = (if (queryAt >= 0) normalized.substring(0, queryAt) else normalized).trimEnd('/')
        val query = if (queryAt >= 0) normalized.substring(queryAt) else ""
        val suffix = if (path.startsWith("/")) path else "/$path"
        return root + suffix + query
    }

    /**
     * Routes the app itself appends. A browser check of the server ends on one of these,
     * and that paste used to be stored as the base.
     */
    private val pastedRouteSuffixes = listOf(
        "/v1/chat/completions",
        "/v1/audio/transcriptions",
        "/v1/completions",
        "/v1/models",
        "/api/v0/models",
        "/api/tags",
        "/api/show",
    )

    private fun stripOpenAiSuffix(raw: String): String {
        var url = raw
        while (true) {
            while (url.endsWith("/")) url = url.dropLast(1)
            val next = stripOneOpenAiSuffix(url) ?: return url
            if (next.length >= url.length) return url
            url = next
        }
    }

    /** One trailing route, or the `/v1` base. Null when [url] is already the base. */
    private fun stripOneOpenAiSuffix(url: String): String? {
        val scheme = url.indexOf("://")
        if (scheme < 0) return null
        // `http://v1` and `http://v1/models` are a host, not a pasted route.
        val authorityStart = scheme + 3
        for (suffix in pastedRouteSuffixes) {
            val at = url.length - suffix.length
            if (at > authorityStart &&
                url.regionMatches(at, suffix, 0, suffix.length, ignoreCase = true)
            ) {
                return url.substring(0, at)
            }
        }
        val slash = url.length - 3
        // A path segment, not the "//" in "http://v1".
        if (slash > authorityStart &&
            url[slash - 1] != '/' &&
            url[slash - 1] != ':' &&
            url.regionMatches(slash, "/v1", 0, 3, ignoreCase = true)
        ) {
            return url.dropLast(3)
        }
        return null
    }

    fun isPrivateOrLocalHost(host: String): Boolean {
        // Zone id (fe80::1%wlan0) is the interface, not part of the address.
        // A trailing dot is the absolute DNS form of the same host (`10.0.0.23.`).
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]").substringBefore('%').trimEnd('.')
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
            val octets = nums.map { octet ->
                // `001` is not an octet OkHttp will open inside an IPv6 literal. A plain
                // IPv4 host is left alone; only this dotted tail is rejected.
                if (octet.length > 1 && octet[0] == '0') return null
                octet.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null
            }
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
