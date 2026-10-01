package io.github.stardomains3.oxproxion.code

/**
 * One line under a tool title. A shell call keeps its command when the only location is the
 * working folder; a file location keeps its line. The row ellipsizes, so this stays bounded.
 */
object ToolCallDetail {

    data class Location(val path: String, val line: Int?)

    fun format(
        kind: String?,
        locations: List<Location>,
        command: String? = null,
        query: String? = null,
        filePath: String? = null,
    ): String? {
        val cmd = clip(command)
        val q = clip(query)
        val file = clip(filePath)
        val loc = locationText(locations)
        val hasLine = locations.any { (it.line ?: 0) > 0 }
        return when (kind) {
            "execute" -> cmd ?: loc ?: file
            "search" -> q ?: loc ?: cmd ?: file
            "fetch" -> q ?: loc ?: cmd
            "read", "edit", "delete", "move" -> loc ?: file ?: cmd ?: q
            else -> when {
                hasLine -> loc ?: cmd ?: file ?: q
                else -> cmd ?: loc ?: file ?: q
            }
        }
    }

    fun locationText(locations: List<Location>): String? {
        val parts = locations.mapNotNull { loc ->
            val path = loc.path.trim()
            if (path.isEmpty()) return@mapNotNull null
            val line = loc.line
            if (line != null && line > 0) "$path:$line" else path
        }
        if (parts.isEmpty()) return null
        val shown = parts.take(MAX_LOCATIONS)
        val extra = parts.size - shown.size
        val joined = shown.joinToString(" · ")
        return if (extra > 0) "$joined +$extra" else joined
    }

    private fun clip(s: String?): String? {
        val t = s?.trim()?.ifEmpty { null } ?: return null
        return if (t.length > MAX_CHARS) t.take(MAX_CHARS) + "…" else t
    }

    private const val MAX_LOCATIONS = 3
    private const val MAX_CHARS = 2000
}
