package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.SharedPreferencesHelper.Companion.LAN_PROVIDER_KOBOLDCPP
import io.github.stardomains3.oxproxion.SharedPreferencesHelper.Companion.LAN_PROVIDER_LLAMA_CPP
import io.github.stardomains3.oxproxion.SharedPreferencesHelper.Companion.LAN_PROVIDER_LM_STUDIO
import io.github.stardomains3.oxproxion.SharedPreferencesHelper.Companion.LAN_PROVIDER_OLLAMA
import io.github.stardomains3.oxproxion.SharedPreferencesHelper.Companion.LAN_PROVIDER_OPENAI_COMPAT
import java.net.URI

/**
 * The server kinds the Local server sheet offers. Older installs may still hold mlx_lm, omlx, nativ
 * or hermes_agent in prefs; those show up as [OTHER] and keep their stored provider on save.
 */
enum class LanServerType(val provider: String, val defaultPort: Int?) {
    OLLAMA(LAN_PROVIDER_OLLAMA, 11434),
    LM_STUDIO(LAN_PROVIDER_LM_STUDIO, 1234),
    LLAMA_CPP(LAN_PROVIDER_LLAMA_CPP, 8080),
    KOBOLDCPP(LAN_PROVIDER_KOBOLDCPP, 5001),
    OTHER(LAN_PROVIDER_OPENAI_COMPAT, null);

    companion object {
        /** Unknown or legacy providers map to [OTHER]. */
        fun fromProvider(provider: String?): LanServerType =
            entries.firstOrNull { it.provider == provider } ?: OTHER
    }
}

/** Pure host-field rules for the Local server sheet (unit-tested). */
object LanEndpoints {
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val TRAILING_V1 = Regex("/v1(/.*)?$")
    private val HOST_PORT = Regex("^(.*):(\\d+)/?$")

    /**
     * Turns what people type (`192.168.1.20`, `nas.local:1234`, `http://x:11434/v1/`) into the base
     * URL the rest of the app appends `/v1/...` to: scheme added, default port applied for plain
     * http, trailing `/` and `/v1` stripped. Null when no host can be read.
     */
    fun normalize(raw: String, defaultPort: Int?): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val withScheme = if (SCHEME.containsMatchIn(text)) text else "http://$text"
        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "http" -> defaultPort
            else -> null
        }
        val path = uri.rawPath.orEmpty().replace(TRAILING_V1, "").trimEnd('/')
        return buildString {
            append(scheme).append("://").append(host)
            if (port != null) append(':').append(port)
            append(path)
        }
    }

    /** What the host field shows for a saved base URL: plain http is left implicit. */
    fun editText(endpoint: String): String = endpoint.removePrefix("http://")

    /** `192.168.1.20:11434` for a saved base URL. */
    fun hostLabel(endpoint: String?): String {
        if (endpoint.isNullOrBlank()) return ""
        return try {
            val uri = URI(endpoint)
            val host = uri.host ?: return endpoint
            if (uri.port != -1) "$host:${uri.port}" else host
        } catch (_: Exception) {
            endpoint
        }
    }

    /**
     * What the host field should read after the server type changes. An empty field, a field that
     * still holds only the old type's port, or a host whose port is the old type's default follows
     * the new type; anything the user shaped by hand is left alone.
     */
    fun retargetPort(current: String, previous: LanServerType?, next: LanServerType): String {
        val text = current.trim()
        val oldPort = previous?.defaultPort
        val newPort = next.defaultPort
        // A bare ":port" in an empty field reads as a broken address; normalize adds the port anyway.
        if (text.isEmpty() || text.matches(Regex("^:\\d*$"))) return ""
        if (oldPort != null) {
            val m = HOST_PORT.matchEntire(text)
            if (m != null && m.groupValues[2] == oldPort.toString()) {
                val host = m.groupValues[1]
                return if (newPort != null) "$host:$newPort" else host
            }
        }
        return current
    }
}

/** The server being talked to. The sheet's Test connection runs on values that aren't saved yet. */
data class LanServer(val endpoint: String, val provider: String, val apiKey: String)

sealed interface LanFetchState {
    data object Loading : LanFetchState
    data class Loaded(val models: List<LlmModel>) : LanFetchState
    data class Failed(val failure: LanFailure, val message: String) : LanFetchState
}
