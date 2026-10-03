package io.github.stardomains3.oxproxion.code

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The approval mode an agent reports on `session/new` or `session/load`.
 * The pill otherwise stays on whatever the phone asked for, even when the
 * harness started in another mode and never sent `current_mode_update`.
 * An id this phone does not show is null, so that request is left alone.
 */
object AcpSessionMode {

    fun fromResult(result: JsonElement?): PermissionMode? {
        val root = result as? JsonObject ?: return null
        val modes = root["modes"] as? JsonObject
        // A modes object that names something unknown must not fall through to a
        // top-level echo of the mode the phone just requested.
        if (modes != null) {
            val named = firstText(modes, "currentModeId", "modeId")
            if (named != null) return PermissionMode.fromAcpModeId(named)
        }
        val top = firstText(root, "currentModeId", "modeId", "permissionMode", "mode") ?: return null
        return PermissionMode.fromAcpModeId(top)
    }

    private fun firstText(obj: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            val text = (obj[k] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } ?: continue
            return text
        }
        return null
    }
}
