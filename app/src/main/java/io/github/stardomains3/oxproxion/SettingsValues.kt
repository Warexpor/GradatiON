package io.github.stardomains3.oxproxion

/** Request timeout the Advanced dialog will save, in minutes. */
internal const val SETTINGS_TIMEOUT_MIN_MINUTES = 1
internal const val SETTINGS_TIMEOUT_MAX_MINUTES = 45

/** Max completion tokens the Advanced dialog will save. */
internal const val SETTINGS_MAX_TOKENS_MIN = 1
internal const val SETTINGS_MAX_TOKENS_MAX = 999_999
internal const val SETTINGS_MAX_TOKENS_DEFAULT = "12000"

/**
 * The Settings home row names the engine stored under Voice, not [VoiceInput.resolve].
 * Resolve hides the mic (or falls back to Cloud) when the phone has no recognizer; the row
 * used to say Off or Cloud while the Voice screen still showed the Phone chip and the switch on.
 */
internal fun settingsVoiceRowEngine(providerKey: String?): VoiceEngine =
    VoiceEngine.fromKey(providerKey)

/**
 * Host (and port) for the Models local-server row. [java.net.URI.authority] includes
 * userinfo, so `http://user:secret@10.0.0.23:11434` used to print the password on the row.
 * IPv6 is bracketed so the port stays readable.
 */
internal fun settingsLanRowValue(endpoint: String?): String? {
    val raw = endpoint?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // URI.getHost() is null for `my_nas` and for a password that contains `@`. Falling back to
    // the raw string printed that password on the row.
    val parsed = LanEndpointValidator.endpointHostPort(raw) ?: hostPortAfterUserinfo(raw)
    if (parsed == null) return if ('@' in raw) null else raw
    return formatLanHostPort(parsed.host, parsed.port)
}

/** Last `@` ends userinfo, including when the URL is too messy for [java.net.URI]. */
private fun hostPortAfterUserinfo(raw: String): LanEndpointValidator.HostPort? {
    val authority = raw.substringAfter("://", raw)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    if (authority.isEmpty() || '@' !in authority) return null
    val hostport = authority.substringAfterLast('@')
    if (hostport.isEmpty() || hostport == authority) return null
    val colon = hostport.lastIndexOf(':')
    if (colon > 0 && hostport.substring(colon + 1).all { it.isDigit() }) {
        val port = hostport.substring(colon + 1).toIntOrNull() ?: return null
        return LanEndpointValidator.HostPort(hostport.substring(0, colon), port)
    }
    return LanEndpointValidator.HostPort(hostport, -1)
}

private fun formatLanHostPort(host: String, port: Int): String {
    val bare = host.removePrefix("[").removeSuffix("]")
    val shown = if (':' in bare) "[$bare]" else bare
    return if (port in 1..65535) "$shown:$port" else shown
}

internal enum class InferenceKind(val min: Double, val max: Double) {
    /** 0–2 is what OpenRouter accepts; a little headroom covers local servers. */
    TEMPERATURE(0.0, 5.0),
    TOP_P(0.0, 1.0),
    MIN_P(0.0, 1.0),
    REPETITION(0.0, 2.0),
    PRESENCE(-2.0, 2.0),
}

/** Dot or comma decimal, finite. Blank, "NaN" and "Infinity" are not numbers here. */
internal fun inferenceDecimalOrNull(raw: String): Double? {
    val t = raw.trim().replace(" ", "").replace(',', '.')
    if (t.isEmpty()) return null
    val v = t.toDoubleOrNull() ?: return null
    if (!v.isFinite()) return null
    return v
}

/**
 * Canonical text for a sampler value inside [kind], or null when [raw] must not be stored.
 * A European keyboard types "0,8"; [toDoubleOrNull] used to drop that and keep the old number.
 */
internal fun acceptedInferenceDecimal(kind: InferenceKind, raw: String): String? {
    val v = inferenceDecimalOrNull(raw) ?: return null
    if (v < kind.min || v > kind.max) return null
    return v.toString()
}

/** Positive whole top-K. "40,0" counts; 0, fractions and non-finite values do not. */
internal fun acceptedTopK(raw: String): Int? {
    val t = raw.trim().replace(" ", "").replace(',', '.')
    if (t.isEmpty()) return null
    val n = t.toIntOrNull()
        ?: t.toDoubleOrNull()?.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
    return n?.takeIf { it in 1..100_000 }
}

/** The dialog's 1–45. 0 used to make every request time out immediately. */
internal fun normalizedTimeoutMinutes(stored: Int): Int =
    stored.coerceIn(SETTINGS_TIMEOUT_MIN_MINUTES, SETTINGS_TIMEOUT_MAX_MINUTES)

/**
 * A token cap the dialog would accept, or [SETTINGS_MAX_TOKENS_DEFAULT].
 * "0", blank and the literal "null" (Kotlin's null toString) are not a cap.
 */
internal fun normalizedMaxTokens(raw: String?): String {
    val n = raw?.trim()?.toIntOrNull() ?: return SETTINGS_MAX_TOKENS_DEFAULT
    return if (n in SETTINGS_MAX_TOKENS_MIN..SETTINGS_MAX_TOKENS_MAX) n.toString()
    else SETTINGS_MAX_TOKENS_DEFAULT
}

/**
 * Which chat-memory row is checked. Only an exact preset: a count that is not in the list
 * used to highlight 8, and tapping that row saved 8 over the real count.
 */
internal fun chatMemoryCheckedIndex(stored: Int, counts: IntArray): Int = counts.indexOf(stored)
