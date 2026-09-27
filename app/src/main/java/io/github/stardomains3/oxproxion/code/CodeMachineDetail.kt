package io.github.stardomains3.oxproxion.code

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

/**
 * Pure helpers for the Code machine-detail screen: URL redaction, fingerprint short form,
 * connection status kind, and bridge version extracted from an already-received ACP
 * `initialize` result (`_meta.bridge.version` or `serverInfo.version`). No new RPCs.
 */
object CodeMachineDetail {

    /** Connection label kind for the machine-detail status row. */
    enum class StatusKind { DEMO, CONNECTED, CONNECTING, OFFLINE, ERROR }

    /**
     * Redact credentials in a bridge URL for display: strip `user:pass@` userinfo and
     * replace token-like query params (`token`, `t`, `auth`, `pairing`) with `•••`.
     * The pairing token itself is never part of the URL (Bearer header), but QR / pasted
     * addresses may carry one in the query string.
     */
    fun redactUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        var s = trimmed
        val schemeSep = s.indexOf("://")
        if (schemeSep >= 0) {
            val after = schemeSep + 3
            val slash = s.indexOf('/', after).let { if (it < 0) Int.MAX_VALUE else it }
            val q = s.indexOf('?', after).let { if (it < 0) Int.MAX_VALUE else it }
            val end = minOf(slash, q, s.length)
            val at = s.indexOf('@', after)
            if (at in (after + 1) until end) {
                s = s.substring(0, after) + "•••@" + s.substring(at + 1)
            }
        }
        val qIdx = s.indexOf('?')
        if (qIdx < 0) return s
        val base = s.substring(0, qIdx)
        val query = s.substring(qIdx + 1)
        if (query.isEmpty()) return base
        val parts = query.split('&').map { part ->
            val eq = part.indexOf('=')
            val key = (if (eq < 0) part else part.substring(0, eq)).lowercase()
            when (key) {
                "token", "t", "auth", "pairing" -> {
                    if (eq < 0) "$key=•••" else "${part.substring(0, eq)}=•••"
                }
                else -> part
            }
        }
        return base + "?" + parts.joinToString("&")
    }

    /**
     * Short display form of a TLS fingerprint / pin, or null when blank.
     * Prefer first 4 bytes as lowercase hex + ellipsis when [BridgeTls.normalizePin] works;
     * otherwise truncate the raw string.
     */
    fun fingerprintShort(fingerprint: String): String? {
        val raw = fingerprint.trim()
        if (raw.isEmpty()) return null
        val pin = BridgeTls.normalizePin(raw)
        if (pin != null) {
            val b64 = pin.removePrefix("sha256/")
            val bytes = runCatching {
                Base64.getDecoder().decode(b64.replace('-', '+').replace('_', '/'))
            }.getOrNull()
            if (bytes != null && bytes.size >= 4) {
                val hex = bytes.take(4).joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
                return "$hex…"
            }
            return if (b64.length <= 10) b64 else b64.take(8) + "…"
        }
        val compact = raw.replace(":", "").replace(" ", "")
        return if (compact.length <= 12) compact else compact.take(8) + "…"
    }

    fun statusKind(isDemo: Boolean, state: ConnectionState): StatusKind = when {
        isDemo -> StatusKind.DEMO
        state == ConnectionState.CONNECTED -> StatusKind.CONNECTED
        state == ConnectionState.CONNECTING -> StatusKind.CONNECTING
        state == ConnectionState.FAILED -> StatusKind.ERROR
        else -> StatusKind.OFFLINE
    }

    /**
     * Pull bridge / server version from an ACP `initialize` result.
     * Prefers GradatiON `_meta.bridge.version`, then ACP `serverInfo.version`.
     */
    fun parseBridgeVersion(initializeResult: JsonObject?): String? {
        if (initializeResult == null) return null
        val meta = initializeResult["meta"] as? JsonObject
            ?: initializeResult["_meta"] as? JsonObject
        val bridge = meta?.get("bridge") as? JsonObject
        val fromBridge = (bridge?.get("version") as? JsonPrimitive)?.contentOrNull?.trim()
        if (!fromBridge.isNullOrEmpty()) return fromBridge
        val serverInfo = initializeResult["serverInfo"] as? JsonObject
        val fromServer = (serverInfo?.get("version") as? JsonPrimitive)?.contentOrNull?.trim()
        if (!fromServer.isNullOrEmpty()) return fromServer
        return null
    }

    /** Optional model-count suffix for a harness row (null when none reported). */
    fun harnessModelCountLabel(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 model"
        else -> "$count models"
    }
}
