package io.github.stardomains3.oxproxion.code

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What the phone does once ACP `initialize` has returned.
 *
 * This client and the GradatiON bridge speak protocol version 1 (`clientCapabilities`,
 * `session/load`, `authenticate`). A peer that answers with another version is refused
 * instead of continuing in a dialect that fails on the next call. The bridge usually
 * omits `authMethods` because the socket already carried the pairing token. An
 * agent-type method is completed with `authenticate`. A terminal method is not: the
 * phone has no terminal to run it.
 */
object AcpHandshake {

    const val SPOKEN_VERSION = 1

    sealed class Decision {
        data object Ready : Decision()
        data class Authenticate(val methodId: String) : Decision()
        /** Only terminal login was offered. The user has to sign in on the computer. */
        data class NeedsTerminal(val name: String) : Decision()
        data class UnsupportedVersion(val version: Int) : Decision()
    }

    fun decide(result: JsonObject?): Decision {
        if (result == null) return Decision.Ready
        val version = protocolVersion(result)
        if (version != null && version != SPOKEN_VERSION) return Decision.UnsupportedVersion(version)
        val methods = authMethods(result)
        if (methods.isEmpty()) return Decision.Ready
        val agent = methods.firstOrNull { it.type == "agent" }
        if (agent != null) return Decision.Authenticate(agent.id)
        val terminal = methods.firstOrNull { it.type == "terminal" }
        if (terminal != null) return Decision.NeedsTerminal(terminal.name)
        return Decision.Ready
    }

    /**
     * Omitted means the bridge did not say, and pictures stay allowed.
     * An explicit `false` or `null` means this agent will reject an image block.
     * An object (capability present) means images are allowed.
     */
    fun acceptsImages(result: JsonObject?): Boolean {
        val caps = result?.get("agentCapabilities") as? JsonObject ?: return true
        val prompt = caps["promptCapabilities"] as? JsonObject ?: return true
        if (!prompt.containsKey("image")) return true
        return when (val image = prompt["image"]) {
            null, is JsonNull -> false
            is JsonPrimitive -> image.booleanOrNull ?: true
            is JsonObject -> true
            else -> true
        }
    }

    /**
     * `authenticate` failed because the peer does not implement it. The GradatiON
     * bridge authenticates the socket and may still list a method. Any other
     * failure is a real refusal.
     */
    fun isSkippableAuthError(message: String?): Boolean {
        val m = message?.lowercase().orEmpty()
        if (m.isEmpty()) return false
        // JSON-RPC -32601, and the phrases agents use when the method was never implemented.
        // A real refusal ("sign in required") must still fail the handshake.
        return m.contains("method not found") ||
            m.contains("-32601") ||
            m.contains("unknown method") ||
            m.contains("not implemented") ||
            m.contains("unimplemented")
    }

    private data class AuthMethod(val id: String, val name: String, val type: String)

    private fun protocolVersion(result: JsonObject): Int? {
        val el = result["protocolVersion"] as? JsonPrimitive ?: return null
        el.intOrNull?.let { return it }
        el.longOrNull?.let { return it.toInt() }
        // A JSON number written as 1.0 / 2.0 still has to refuse an unsupported dialect.
        el.doubleOrNull?.let { d ->
            if (d.isFinite() && d == kotlin.math.floor(d) &&
                d in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
            ) return d.toInt()
        }
        el.contentOrNull?.toIntOrNull()?.let { return it }
        return el.contentOrNull?.toDoubleOrNull()?.takeIf {
            it.isFinite() && it == kotlin.math.floor(it) &&
                it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
        }?.toInt()
    }

    private fun authMethods(result: JsonObject): List<AuthMethod> {
        val arr = result["authMethods"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            // Whole-number doubles (5.0 / "5.0") still match the authenticate methodId as "5".
            val id = idString(o["id"]) ?: idString(o["methodId"]) ?: return@mapNotNull null
            val type = o.str("type")?.lowercase() ?: "agent"
            AuthMethod(id, o.str("name") ?: id, type)
        }
    }

    /** Stable auth method id: numeric ids become `"5"`, not `"5.0"`. */
    private fun idString(el: JsonElement?): String? {
        val p = el as? JsonPrimitive ?: return null
        wholeNumberLong(p)?.let { return it.toString() }
        return p.contentOrNull?.trim()?.ifEmpty { null }
    }

    /** Integers stay long; a double like `5.0` and a string `"5.0"` still match. */
    private fun wholeNumberLong(p: JsonPrimitive): Long? {
        p.longOrNull?.let { return it }
        p.doubleOrNull?.let { d ->
            if (d.isFinite() && d == kotlin.math.floor(d) &&
                d in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
            ) return d.toLong()
        }
        val c = p.contentOrNull ?: return null
        c.toLongOrNull()?.let { return it }
        return c.toDoubleOrNull()?.takeIf {
            it.isFinite() && it == kotlin.math.floor(it) &&
                it in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
        }?.toLong()
    }

    private fun JsonObject.str(k: String): String? =
        (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
}
