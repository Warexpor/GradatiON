package io.github.stardomains3.oxproxion.code

/** One rendered line of a diff. Line numbers are 1-based; null where the side has no line. */
data class DiffLine(val type: Type, val oldNo: Int?, val newNo: Int?, val text: String) {
    enum class Type { CONTEXT, ADD, DELETE, HUNK }
}

/**
 * Line diffs for the transcript and the diff viewer. Harnesses report edits either as unified
 * diff text (Codex, git) or as old/new file contents (ACP `diff` content), so both are handled.
 */
object Diff {

    /**
     * Parses unified diff text into lines. Git preamble (rename, mode, index) is dropped so it
     * is not drawn as file content. A `---` / `+++` header needs the space git puts before the
     * path: a real line that starts with `++` or `--` stays a change. Carriage returns are
     * stripped so a Windows patch still matches hunk headers.
     */
    fun parseUnified(text: String): List<DiffLine> {
        val out = ArrayList<DiffLine>()
        var oldNo = 0
        var newNo = 0
        var inHunk = false
        var skippingBinary = false
        val hunk = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$""")
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("diff ")) {
                inHunk = false
                skippingBinary = false
                continue
            }
            if (skippingBinary) continue
            when {
                line.startsWith("@@") -> {
                    val m = hunk.find(line) ?: continue
                    oldNo = m.groupValues[1].toInt()
                    newNo = m.groupValues[2].toInt()
                    inHunk = true
                    out += DiffLine(DiffLine.Type.HUNK, null, null, m.groupValues[3].trim())
                }
                isPreamble(line) -> Unit
                line.startsWith("Binary files ") || line.startsWith("GIT binary patch") -> {
                    inHunk = false
                    skippingBinary = line.startsWith("GIT binary patch")
                    out += DiffLine(DiffLine.Type.HUNK, null, null, line)
                }
                line.startsWith("+") -> out += DiffLine(DiffLine.Type.ADD, null, newNo++, line.substring(1))
                line.startsWith("-") -> out += DiffLine(DiffLine.Type.DELETE, oldNo++, null, line.substring(1))
                line.startsWith(" ") -> out += DiffLine(DiffLine.Type.CONTEXT, oldNo++, newNo++, line.substring(1))
                line.isEmpty() && out.isNotEmpty() -> Unit
                inHunk -> out += DiffLine(DiffLine.Type.CONTEXT, oldNo++, newNo++, line)
                else -> Unit
            }
        }
        return out
    }

    /**
     * Diffs two file contents into hunks with [context] lines around each change. The common
     * prefix and suffix are trimmed first (edits are usually small and local), then LCS runs over
     * what is left. Only when that remainder passes [maxCells] comparisons does the changed span
     * degrade to "everything in it replaced" rather than stall the caller's thread (call this off
     * the main thread for big files anyway); the untouched prefix and suffix stay context.
     */
    fun between(oldText: String?, newText: String, context: Int = 3, maxCells: Long = 1_000_000L): List<DiffLine> {
        val a = oldText?.let { linesOf(it) } ?: emptyList()
        val b = linesOf(newText)
        return hunks(ops(a, b, maxCells), context)
    }

    /** `\r\n` and a bare `\r` become `\n`, so a Windows file is not a change on every line. */
    private fun linesOf(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    fun counts(lines: List<DiffLine>): Pair<Int, Int> =
        lines.count { it.type == DiffLine.Type.ADD } to lines.count { it.type == DiffLine.Type.DELETE }

    /**
     * A unified patch that creates a file (`new file mode`, `--- /dev/null`, or `@@ -0,0`).
     * A deletion (`deleted file mode`, `+++ /dev/null`, `+0,0`) is not a new file.
     */
    fun unifiedIsNewFile(text: String): Boolean {
        var sawNew = false
        var sawDelete = false
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("deleted file mode") ||
                line.startsWith("+++ /dev/null") ||
                line.startsWith("+++ b/dev/null") ||
                (line.startsWith("@@ -") && line.contains(" +0,0"))
            ) sawDelete = true
            if (line.startsWith("new file mode") ||
                line.startsWith("--- /dev/null") ||
                line.startsWith("--- a/dev/null") ||
                line.startsWith("@@ -0,0 ")
            ) sawNew = true
        }
        return sawNew && !sawDelete
    }

    /**
     * Git metadata that is not a hunk body. File headers are `--- a/path` / `+++ b/path`
     * (space or tab after the dashes), not a content line that itself starts with dashes.
     */
    private fun isPreamble(line: String): Boolean {
        if (line.startsWith("--- ") || line.startsWith("---\t") ||
            line.startsWith("+++ ") || line.startsWith("+++\t") ||
            line.startsWith("index ") || line.startsWith("\\ No newline")
        ) return true
        return line.startsWith("similarity ") || line.startsWith("dissimilarity ") ||
            line.startsWith("rename from") || line.startsWith("rename to") ||
            line.startsWith("copy from") || line.startsWith("copy to") ||
            line.startsWith("old mode") || line.startsWith("new mode") ||
            line.startsWith("deleted file mode") || line.startsWith("new file mode")
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────

    private fun ops(a: List<String>, b: List<String>, maxCells: Long): List<DiffLine> {
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }
        val n = endA - start
        val m = endB - start
        val out = ArrayList<DiffLine>(a.size + m)
        for (k in 0 until start) out += DiffLine(DiffLine.Type.CONTEXT, k + 1, k + 1, a[k])
        if (n.toLong() * m <= maxCells) lcsMiddle(a, b, start, n, m, out) else replaceMiddle(a, b, start, n, m, out)
        for (k in 0 until a.size - endA) out += DiffLine(DiffLine.Type.CONTEXT, endA + k + 1, endB + k + 1, a[endA + k])
        return out
    }

    private fun replaceMiddle(a: List<String>, b: List<String>, start: Int, n: Int, m: Int, out: MutableList<DiffLine>) {
        for (i in 0 until n) out += DiffLine(DiffLine.Type.DELETE, start + i + 1, null, a[start + i])
        for (j in 0 until m) out += DiffLine(DiffLine.Type.ADD, null, start + j + 1, b[start + j])
    }

    private fun lcsMiddle(a: List<String>, b: List<String>, start: Int, n: Int, m: Int, out: MutableList<DiffLine>) {
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[start + i] == b[start + j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && a[start + i] == b[start + j] -> {
                    out += DiffLine(DiffLine.Type.CONTEXT, start + i + 1, start + j + 1, a[start + i]); i++; j++
                }
                // Removals before additions at a change, like git.
                i < n && (j >= m || dp[i + 1][j] >= dp[i][j + 1]) -> {
                    out += DiffLine(DiffLine.Type.DELETE, start + i + 1, null, a[start + i]); i++
                }
                else -> { out += DiffLine(DiffLine.Type.ADD, null, start + j + 1, b[start + j]); j++ }
            }
        }
    }

    /** Keeps [context] unchanged lines around each change and inserts a hunk header per gap. */
    private fun hunks(ops: List<DiffLine>, context: Int): List<DiffLine> {
        val changed = ops.indices.filter { ops[it].type != DiffLine.Type.CONTEXT }
        if (changed.isEmpty()) return emptyList()
        val keep = BooleanArray(ops.size)
        for (c in changed) for (k in maxOf(0, c - context)..minOf(ops.size - 1, c + context)) keep[k] = true
        val out = ArrayList<DiffLine>()
        var inHunk = false
        for (k in ops.indices) {
            if (!keep[k]) { inHunk = false; continue }
            if (!inHunk) {
                val o = ops[k]
                out += DiffLine(DiffLine.Type.HUNK, null, null, "line ${o.newNo ?: o.oldNo ?: 1}")
                inHunk = true
            }
            out += ops[k]
        }
        return out
    }
}
