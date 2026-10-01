package io.github.stardomains3.oxproxion.code

/**
 * Working-tree review helpers. Porcelain status decides what "revert" can mean:
 * only a path git already tracks can be restored to HEAD. Untracked files stay.
 */
object GitChanges {

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
        return files.filter { it.path.contains(q, ignoreCase = true) }
    }
}
