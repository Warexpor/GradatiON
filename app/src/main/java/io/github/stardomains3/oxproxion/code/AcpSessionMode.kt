package io.github.stardomains3.oxproxion.code

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The approval mode an agent reports on `session/new` or `session/load`.
 * The pill otherwise stays on whatever the phone asked for, even when the
 * harness started in another mode and never sent `current_mode_update`.
 * An id this phone does not show is null, so that request is left alone.
 *
 * ACP now prefers a `configOptions` select (`category: "mode"`, or id `mode`)
 * over the older `modes` object. A named value there is the mode, including
 * when it is one this phone does not show: that must not fall through to
 * `modes` or to a top-level echo of the mode the phone just requested.
 */
object AcpSessionMode {

    fun fromResult(result: JsonElement?): PermissionMode? {
        val root = result as? JsonObject ?: return null
        when (val named = readConfigMode(root["configOptions"])) {
            is ConfigMode.Named -> return named.mode
            ConfigMode.Absent -> Unit
        }
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

    /**
     * The mode select inside a `configOptions` list (`session/new`, `session/load`,
     * or `config_option_update`). Null when the list has no mode select, the value
     * is blank, or the id is not one this phone shows.
     */
    fun fromConfigOptions(options: JsonElement?): PermissionMode? = when (val named = readConfigMode(options)) {
        is ConfigMode.Named -> named.mode
        ConfigMode.Absent -> null
    }

    private fun readConfigMode(options: JsonElement?): ConfigMode {
        val arr = options as? JsonArray ?: return ConfigMode.Absent
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            if (!isModeSelect(o)) continue
            val value = currentValue(o) ?: continue
            return ConfigMode.Named(PermissionMode.fromAcpModeId(value))
        }
        return ConfigMode.Absent
    }

    /** `category: "mode"`, or an uncategorized select whose id is `mode` (`configId` on v2). */
    private fun isModeSelect(o: JsonObject): Boolean {
        val category = firstText(o, "category")?.lowercase()
        if (category == "mode") return true
        if (category != null) return false
        val id = firstText(o, "id", "configId")?.lowercase()
        return id == "mode"
    }

    /** A boolean option is not a mode id. Blank is not a name. */
    private fun currentValue(o: JsonObject): String? {
        val p = o["currentValue"] as? JsonPrimitive ?: return null
        if (p.booleanOrNull != null) return null
        return p.contentOrNull?.trim()?.ifEmpty { null }
    }

    private fun firstText(obj: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            val text = (obj[k] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } ?: continue
            return text
        }
        return null
    }

    private sealed interface ConfigMode {
        data class Named(val mode: PermissionMode?) : ConfigMode
        data object Absent : ConfigMode
    }
}
