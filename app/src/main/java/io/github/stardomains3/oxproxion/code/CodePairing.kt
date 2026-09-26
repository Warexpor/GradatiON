package io.github.stardomains3.oxproxion.code

import java.net.URI
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
    }

    sealed class ParseResult {
        data class Ok(val pairing: Result) : ParseResult()
        data class Err(val reason: Reason) : ParseResult()
    }

    /** True when [raw] is a `gradation://pair` link (query may still be invalid). */
    fun isPairUri(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val uri = parseUri(raw.trim()) ?: return false
        return SCHEME.equals(uri.scheme, ignoreCase = true) &&
            HOST.equals(uri.host, ignoreCase = true)
    }

    /** Android deep-link convenience: scheme+host check without pulling Uri into the parser core. */
    fun isPairUri(scheme: String?, host: String?): Boolean =
        SCHEME.equals(scheme, ignoreCase = true) && HOST.equals(host, ignoreCase = true)

    /**
     * Parse a pairing QR / deep-link string.
     *
     * Query aliases: `url`|`address`|`ws`; `token`|`t`|`auth`; `fp`|`fingerprint`|`pin`.
     * Missing url/token → error. Present but unparseable `fp` → [Reason.BAD_FINGERPRINT].
     * Absent `fp` is allowed (legacy / plain ws).
     */
    fun parse(raw: String?): ParseResult {
        if (raw.isNullOrBlank()) return ParseResult.Err(Reason.NOT_PAIR_URI)
        val trimmed = raw.trim()
        val uri = parseUri(trimmed) ?: return ParseResult.Err(Reason.NOT_PAIR_URI)
        if (!SCHEME.equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.host, ignoreCase = true)) {
            return ParseResult.Err(Reason.NOT_PAIR_URI)
        }

        val params = queryMap(uri.rawQuery)
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

        return ParseResult.Ok(Result(url = url, token = token, fingerprint = fingerprint))
    }

    private fun parseUri(raw: String): URI? =
        runCatching { URI(raw) }.getOrNull()

    private fun queryMap(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in rawQuery.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val key = decode(if (eq < 0) part else part.substring(0, eq)).lowercase()
            val value = if (eq < 0) "" else decode(part.substring(eq + 1))
            if (key.isNotEmpty() && key !in out) out[key] = value
        }
        return out
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
