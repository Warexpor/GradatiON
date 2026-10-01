package io.github.stardomains3.oxproxion.code

import java.io.ByteArrayOutputStream

/**
 * Working-tree review helpers. Porcelain status decides what "revert" can mean:
 * only a path git already tracks can be restored to HEAD. Untracked files stay.
 *
 * A path may still be git's own spelling: C-quoted (`"my file.kt"`, octal UTF-8)
 * or a rename (`old -> new`, each side quoted when it has a space). The diff
 * request needs the path git has now, not that spelling.
 */
object GitChanges {

    /** Path to ask the bridge for, plus the previous name when this row is a rename. */
    data class ChangePath(val diffPath: String, val renamedFrom: String?)

    /** `??` (and a one-character `?` some bridges send). Ignored paths are not untracked. */
    fun isUntracked(status: String): Boolean {
        val t = status.trim()
        return t == "??" || t == "?"
    }

    /**
     * A path `git checkout --` / `git restore` can put back. Untracked (`??`) and ignored
     * (`!!`) paths are not in HEAD, so a revert prompt must leave them out.
     */
    fun isTrackedChange(status: String): Boolean {
        val t = status.trim()
        if (t.isEmpty() || isUntracked(status)) return false
        if (t == "!!" || t == "!") return false
        return true
    }

    fun trackedCount(files: List<GitFileStatus>): Int = files.count { isTrackedChange(it.status) }

    fun untrackedCount(files: List<GitFileStatus>): Int = files.count { isUntracked(it.status) }

    /** Case-insensitive path match. A blank query returns [files] unchanged. */
    fun filter(files: List<GitFileStatus>, query: String): List<GitFileStatus> {
        val q = query.trim()
        if (q.isEmpty()) return files
        return files.filter { f ->
            val p = changePath(f)
            f.path.contains(q, ignoreCase = true) ||
                p.diffPath.contains(q, ignoreCase = true) ||
                p.renamedFrom?.contains(q, ignoreCase = true) == true
        }
    }

    /**
     * The path a diff, commit, or copy should use. A rename's label can still
     * mention [ChangePath.renamedFrom]; the wire path is the new one.
     */
    fun changePath(file: GitFileStatus): ChangePath {
        val trimmed = file.path.trim()
        val split = splitRename(trimmed)
        if (split != null) return ChangePath(split.second, split.first)
        val plain = unquote(trimmed)
        return ChangePath(plain.ifEmpty { trimmed }, null)
    }

    /** ` -> ` outside quotes. Git writes that between the old path and the new one. */
    private fun splitRename(path: String): Pair<String, String>? {
        var i = 0
        var quoted = false
        while (i < path.length) {
            val c = path[i]
            if (quoted && c == '\\' && i + 1 < path.length) {
                i += 2
                continue
            }
            if (c == '"') {
                quoted = !quoted
                i++
                continue
            }
            if (!quoted && path.startsWith(" -> ", i)) {
                val from = unquote(path.substring(0, i).trim())
                val to = unquote(path.substring(i + 4).trim())
                if (from.isNotEmpty() && to.isNotEmpty()) return from to to
                return null
            }
            i++
        }
        return null
    }

    /**
     * Git C-quoting. Octal escapes are raw UTF-8 bytes (`\344\270\255` is 中),
     * not Latin-1 characters. A path that is not quoted is returned unchanged.
     */
    internal fun unquote(path: String): String {
        val t = path.trim()
        if (t.length < 2 || t.first() != '"' || t.last() != '"') return t
        val inner = t.substring(1, t.length - 1)
        val buf = ByteArrayOutputStream(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                when (val n = inner[i + 1]) {
                    '\\', '"' -> {
                        buf.write(n.code)
                        i += 2
                    }
                    'n' -> {
                        buf.write('\n'.code)
                        i += 2
                    }
                    't' -> {
                        buf.write('\t'.code)
                        i += 2
                    }
                    'b' -> {
                        buf.write('\b'.code)
                        i += 2
                    }
                    'f' -> {
                        buf.write(0x0c)
                        i += 2
                    }
                    'r' -> {
                        buf.write('\r'.code)
                        i += 2
                    }
                    in '0'..'7' -> {
                        var j = i + 1
                        var v = 0
                        var k = 0
                        while (k < 3 && j < inner.length && inner[j] in '0'..'7') {
                            v = v * 8 + (inner[j] - '0')
                            j++
                            k++
                        }
                        buf.write(v)
                        i = j
                    }
                    else -> {
                        buf.write(n.code)
                        i += 2
                    }
                }
            } else {
                buf.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }
}
