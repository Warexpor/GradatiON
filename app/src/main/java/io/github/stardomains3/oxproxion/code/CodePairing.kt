package io.github.stardomains3.oxproxion.code

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Pairing URI for Code mode: `gradation://pair?url=wss://…&token=…&fp=<sha256 cert>`.
 *
 * Pure JVM parse/normalize (no Android APIs) so unit tests run without Robolectric.
 * Fingerprint is stored as OkHttp pin form `sha256/<base64>` when [BridgeTls.normalizePin]
 * accepts it (same expectation as [CodeHost.fingerprint] → [WebSocketTransport]).
 */
object CodePairing {

    const val SCHEME = "gradation"
    const val HOST = "pair"

    data class Result(
        val url: String,
        val token: String,
        /** Normalized `sha256/<base64>` pin, or empty when the QR omitted `fp`. */
        val fingerprint: String,
    ) {
        /** Suggested machine name from the bridge host (no scheme/port/path). */
        val nameHint: String
            get() = BridgeTls.hostPatternOf(url).takeIf { it.isNotBlank() && it != "localhost" }.orEmpty()
    }

    enum class Reason {
        NOT_PAIR_URI,
        MISSING_URL,
        MISSING_TOKEN,
        BAD_URL,
        BAD_FINGERPRINT,
        /** Fingerprint present with cleartext `ws://` — pin requires `wss://`. */
        PIN_REQUIRES_WSS,
    }

    sealed class ParseResult {
        data class Ok(val pairing: Result) : ParseResult()
        data class Err(val reason: Reason) : ParseResult()
    }

    /** True when [raw] is a `gradation://pair` link (query may still be invalid). */
    fun isPairUri(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val parts = splitPair(raw.trim()) ?: return false
        return SCHEME.equals(parts.scheme, ignoreCase = true) &&
            HOST.equals(parts.host, ignoreCase = true)
    }

    /** Android deep-link convenience: scheme+host check without pulling Uri into the parser core. */
    fun isPairUri(scheme: String?, host: String?): Boolean =
        SCHEME.equals(scheme, ignoreCase = true) && HOST.equals(host, ignoreCase = true)

    /**
     * Parse a pairing QR / deep-link string.
     *
     * Query aliases: `url`|`address`|`ws`; `token`|`t`|`auth`; `fp`|`fingerprint`|`pin`.
     * A bridge address may contain its own query (`wss://host/v1?a=1&b=2`) even when that
     * `&` was not percent-encoded. Those parameters stay as written: the key's case and any
     * percent-encoding are part of the address. A `#` in that address is not an outer
     * fragment, and a later `#note` with no `&` after it is still dropped. A `#note`
     * stuck to the token or the fingerprint is dropped even when a later `#` keeps
     * the rest of the query (that note used to stay, and a pin with it was rejected).
     * A query key that happens to be named `token` / `auth` / `ws` / `fp` stays in the
     * address when a real pairing field follows it. The first bridge address wins when
     * a later parameter repeats it under another alias (`address` then `url`). A
     * fingerprint may contain spaces, including spaces a form encoder wrote as `+`.
     * Missing url/token → error. Present but unparseable `fp` → [Reason.BAD_FINGERPRINT].
     * Valid `fp` with cleartext `ws://` → [Reason.PIN_REQUIRES_WSS] (pin needs `wss://`).
     * Absent `fp` is allowed (legacy cleartext LAN / no-pin path).
     */
    fun parse(raw: String?): ParseResult {
        if (raw.isNullOrBlank()) return ParseResult.Err(Reason.NOT_PAIR_URI)
        val parts = splitPair(raw.trim()) ?: return ParseResult.Err(Reason.NOT_PAIR_URI)
        if (!SCHEME.equals(parts.scheme, ignoreCase = true) || !HOST.equals(parts.host, ignoreCase = true)) {
            return ParseResult.Err(Reason.NOT_PAIR_URI)
        }

        val params = queryMap(parts.query)
        val url = firstParam(params, "url", "address", "ws")
        val token = firstParam(params, "token", "t", "auth")
        val fpRaw = firstParam(params, "fp", "fingerprint", "pin")

        if (url.isEmpty()) return ParseResult.Err(Reason.MISSING_URL)
        if (token.isEmpty()) return ParseResult.Err(Reason.MISSING_TOKEN)
        if (!(url.startsWith("ws://", ignoreCase = true) || url.startsWith("wss://", ignoreCase = true))) {
            return ParseResult.Err(Reason.BAD_URL)
        }

        val fingerprint = when {
            fpRaw.isEmpty() -> ""
            else -> BridgeTls.normalizePin(fpRaw)
                ?: return ParseResult.Err(Reason.BAD_FINGERPRINT)
        }
        // Pin is inert over cleartext WS; never accept fingerprint + ws://.
        if (BridgeTls.pinRequiresWss(fingerprint, url)) {
            return ParseResult.Err(Reason.PIN_REQUIRES_WSS)
        }

        return ParseResult.Ok(Result(url = url, token = token, fingerprint = fingerprint))
    }

    private data class PairParts(val scheme: String, val host: String, val query: String)

    private data class QueryPart(val key: String, val value: String, val raw: String)

    /**
     * Scheme and host only. [java.net.URI] rejects spaces (a spaced fingerprint) and
     * treats `#` as a fragment, which used to drop the token that followed a bridge address.
     * A `#` with no `&` after it is the outer fragment (`…&token=abc#note`). The first `#`
     * is not that fragment when the bridge address has one and a note follows
     * (`…#section&token=abc#note`). A `#note` on the token or the pin is removed from
     * that field even when it is not the last `#` (`token=abc#note&url=…#section&fp=…`).
     */
    private fun splitPair(raw: String): PairParts? {
        val sep = raw.indexOf("://")
        if (sep <= 0) return null
        val scheme = raw.substring(0, sep)
        val afterScheme = raw.substring(sep + 3)
        if (afterScheme.isEmpty()) return null
        val cut = afterScheme.indexOfAny(charArrayOf('/', '?', '#'))
        val host = if (cut < 0) afterScheme else afterScheme.substring(0, cut)
        if (host.isEmpty()) return null
        if (cut < 0) return PairParts(scheme, host, "")
        val rest = afterScheme.substring(cut)
        val query = when {
            rest.startsWith("?") -> rest.substring(1)
            rest.startsWith("/") -> {
                val q = rest.indexOf('?')
                if (q < 0) "" else rest.substring(q + 1)
            }
            else -> ""
        }
        return PairParts(scheme, host, stripOuterFragment(query))
    }

    private fun stripOuterFragment(query: String): String {
        val hash = query.lastIndexOf('#')
        if (hash < 0) return query
        return if (query.substring(hash + 1).contains('&')) query else query.substring(0, hash)
    }

    private val urlKeys = setOf("url", "address", "ws")
    private val tokenKeys = setOf("token", "t", "auth")
    private val fpKeys = setOf("fp", "fingerprint", "pin")
    private val knownKeys = urlKeys + tokenKeys + fpKeys

    /**
     * The address keeps its own query until the pairing token and fingerprint.
     * A token written before the address wins, so a later `token=` inside the bridge
     * query stays in the address. Otherwise the last token / fingerprint after the
     * address is the pairing field (an earlier `auth` or `token` in that query is not).
     * A second address alias is a sibling only when the address has no `?` yet, and it
     * does not replace the first. `address` then `url` used to save the later `url`
     * because that name is checked first. `ws` inside `?room=1&ws=1&b=2` is part of
     * the address.
     *
     * Keys are matched case-insensitively, but a parameter that stays in the address is
     * copied as written. Lowercasing it, or decoding `%20` / `%2B` while gluing, changed
     * the bridge URL the QR actually carried.
     */
    private fun queryMap(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val parts = ArrayList<QueryPart>()
        for (part in rawQuery.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val rawKey = if (eq < 0) part else part.substring(0, eq)
            val key = decode(rawKey).lowercase()
            val value = if (eq < 0) "" else decode(part.substring(eq + 1))
            if (key.isEmpty()) continue
            parts.add(QueryPart(key, value, part))
        }
        val out = LinkedHashMap<String, String>()
        // The first non-empty address. An empty `url=` must not hide a later `address=`.
        val urlIndex = parts.indexOfFirst { it.key in urlKeys && bridgeUrl(it).isNotEmpty() }
        if (urlIndex < 0) {
            for (part in parts) {
                if (part.key in knownKeys && part.key !in out) out[part.key] = storedValue(part)
            }
            return out
        }
        val urlKey = parts[urlIndex].key
        var urlValue = bridgeUrl(parts[urlIndex])
        var tokenBefore = false
        var fpBefore = false
        for (i in 0 until urlIndex) {
            val part = parts[i]
            if (part.key !in knownKeys || part.key in out) continue
            val value = storedValue(part)
            if (value.isEmpty()) continue
            out[part.key] = value
            if (part.key in tokenKeys) tokenBefore = true
            if (part.key in fpKeys) fpBefore = true
        }
        val after = parts.subList(urlIndex + 1, parts.size)
        // Inside a bridge query, the last token/fingerprint is the pairing field so an
        // earlier auth= or token= stays in the address. With no '?', the first one wins,
        // matching a link that simply repeats the pairing field.
        val bridgeQuery = urlValue.contains('?')
        val lastToken = if (tokenBefore) -1 else pickPairingKey(after, tokenKeys, bridgeQuery)
        val lastFp = if (fpBefore) -1 else pickPairingKey(after, fpKeys, bridgeQuery)
        val lastTerminator = maxOf(lastToken, lastFp)
        for (i in after.indices) {
            val part = after[i]
            if (i == lastToken || i == lastFp) {
                if (part.key !in out) {
                    val value = storedValue(part)
                    if (value.isNotEmpty()) out[part.key] = value
                }
                continue
            }
            // A repeated address is not the bridge. Storing it used to win in firstParam
            // when its alias is checked before the one that actually came first.
            if (part.key in urlKeys && !bridgeQuery) continue
            if (lastTerminator >= 0 && i > lastTerminator) continue
            urlValue = "$urlValue&${part.raw}"
        }
        out[urlKey] = urlValue
        return out
    }

    /**
     * An address that is already `ws://` or `wss://` is stored as written, so `%20` stays
     * encoded and the key keeps its case. A percent-encoded query value (`wss%3A%2F%2F…`)
     * is decoded once, as a form value: `+` is a space there. The token decoder keeps
     * `+` as a plus, and using it here saved `hello+world` for an address that had a space.
     */
    private fun bridgeUrl(part: QueryPart): String {
        val rawValue = part.raw.substringAfter('=', missingDelimiterValue = "")
        val trimmed = rawValue.trim()
        if (trimmed.startsWith("ws://", ignoreCase = true) || trimmed.startsWith("wss://", ignoreCase = true)) {
            return trimmed
        }
        return decodeForm(trimmed)
    }

    /**
     * Token and fingerprint values as stored. A raw `#note` on that field is not part of
     * the value, even when a later `#section&…` kept the outer query intact. Cutting after
     * [decode] would also drop a `%23` that belongs in the token.
     */
    private fun storedValue(part: QueryPart): String {
        if (part.key !in tokenKeys && part.key !in fpKeys) return part.value
        val rawValue = part.raw.substringAfter('=', missingDelimiterValue = "")
        val hash = rawValue.indexOf('#')
        return decode(if (hash < 0) rawValue else rawValue.substring(0, hash))
    }

    /** One application/x-www-form-urlencoded decode. `+` is a space; `%2B` is a plus. */
    private fun decodeForm(s: String): String =
        runCatching { URLDecoder.decode(s, StandardCharsets.UTF_8.name()) }
            .getOrDefault(s)
            .trim()

    /** Prefer `token` / `t` over `auth` so a bridge `auth` query does not replace the pairing token. */
    private fun pickPairingKey(after: List<QueryPart>, keys: Set<String>, preferLast: Boolean): Int {
        val primary = if (keys === tokenKeys) setOf("token", "t") else keys
        val chosen = pick(after, primary, preferLast)
        if (chosen >= 0) return chosen
        if (primary !== keys) return pick(after, keys, preferLast)
        return -1
    }

    private fun pick(after: List<QueryPart>, keys: Set<String>, preferLast: Boolean): Int {
        val matches = after.indices.filter { after[it].key in keys && after[it].value.isNotEmpty() }
        if (matches.isEmpty()) return -1
        return if (preferLast) matches.last() else matches.first()
    }

    private fun firstParam(params: Map<String, String>, vararg keys: String): String {
        for (key in keys) {
            val v = params[key.lowercase()]?.trim().orEmpty()
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }
            .getOrDefault(s)
}
