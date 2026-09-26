package io.github.stardomains3.oxproxion.code

/**
 * Path helpers for the Code folder browser (popover over [bridge/browse]).
 * Keeps parent/child/title logic out of the fragment so it can be unit-tested.
 */
object BrowsePaths {

    /** Parent directory, or null at filesystem / home root (`/`, `~`, blank). */
    fun parentOf(path: String): String? {
        val n = normalize(path)
        if (n == "~" || n == "/") return null
        val slash = n.lastIndexOf('/')
        if (slash < 0) return null
        if (slash == 0) return "/"
        return n.substring(0, slash).ifEmpty { null }
    }

    /** Join [parent] and a single [name] segment (no trailing slash). */
    fun child(parent: String, name: String): String {
        val n = name.trim('/')
        if (n.isEmpty()) return normalize(parent)
        val p = parent.trimEnd('/')
        return when {
            parent == "/" || p.isEmpty() && parent.startsWith("/") -> "/$n"
            p.isEmpty() -> n
            else -> "$p/$n"
        }
    }

    /** Normalize for browse RPC / titles: trim trailing slash; blank becomes `~`. */
    fun normalize(path: String): String = when {
        path.isBlank() -> "~"
        path == "/" -> "/"
        else -> path.trimEnd('/').ifEmpty { "~" }
    }

    /**
     * Popover title: last few path segments joined with ` / ` so the trail reads as breadcrumbs
     * without new chrome (e.g. `~/code/GradatiON` → `code / GradatiON`).
     */
    fun breadcrumbTitle(path: String, maxSegments: Int = 3): String {
        val n = normalize(path)
        if (n == "~" || n == "/") return n
        val stripped = if (n.startsWith("~/")) n.substring(2) else n.trimStart('/')
        val parts = stripped.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return n
        val tail = parts.takeLast(maxSegments)
        val prefix = if (parts.size > maxSegments) "… / " else ""
        return prefix + tail.joinToString(" / ")
    }
}
