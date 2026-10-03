package io.github.stardomains3.oxproxion

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Text files staged on the Chat composer. Several picks used to be read at once, and each
 * one measured the total before any of them was added, so four files that together pass
 * 3 MB were all kept. The read also finished before the size check, so a huge file was
 * allocated and then thrown away.
 */
object ComposerFiles {
    const val MAX_TOTAL_BYTES = 3 * 1024 * 1024
    const val MAX_SINGLE_BYTES = 1024 * 1024

    enum class Decision { ACCEPT, TOO_BIG, OVER_TOTAL }

    /**
     * Whether a file of [fileBytes] can join a composer that already holds [alreadyBytes].
     * Call this at the moment of adding, against the total after earlier files in the same
     * pick, not against a snapshot taken before those reads.
     */
    fun decide(alreadyBytes: Long, fileBytes: Long): Decision {
        if (fileBytes < 0 || fileBytes > MAX_SINGLE_BYTES) return Decision.TOO_BIG
        if (alreadyBytes < 0 || alreadyBytes > MAX_TOTAL_BYTES - fileBytes) return Decision.OVER_TOTAL
        return Decision.ACCEPT
    }

    data class CappedRead(val text: String, val bytes: Int, val overflow: Boolean)

    /**
     * UTF-8 text up to [maxBytes]. One more byte means the file does not fit: [text] is
     * empty so a truncated copy is not what gets attached, and the rest of the stream
     * is left unread.
     */
    fun readCapped(input: InputStream, maxBytes: Int = MAX_SINGLE_BYTES): CappedRead {
        val limit = maxBytes.coerceAtLeast(0)
        val buf = ByteArray(8192)
        val out = ByteArrayOutputStream(minOf(limit, 8192).coerceAtLeast(0))
        var total = 0
        while (total < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - total))
            if (n < 0) return finish(out, total, overflow = false)
            out.write(buf, 0, n)
            total += n
        }
        if (input.read() < 0) return finish(out, total, overflow = false)
        return CappedRead(text = "", bytes = total, overflow = true)
    }

    private fun finish(out: ByteArrayOutputStream, total: Int, overflow: Boolean): CappedRead {
        // Notepad writes a UTF-8 BOM. Leaving it on the first line sent a different file.
        val text = out.toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF")
        return CappedRead(text, total, overflow)
    }

    /**
     * Whether this pick is a text file. Any text type counts, including one whose
     * subtype is not on the short list, or whose charset parameter is glued on.
     * A generic type still counts when the name is an extension we already accept,
     * including the siblings of those extensions (tsx next to ts, kts next to kt).
     */
    fun accepts(mimeType: String?, fileName: String): Boolean {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (mime.startsWith("text/")) return true
        if (mime in DOCUMENT_TYPES) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && '.' in fileName && ext in CODE_EXTENSIONS
    }

    /** One header or dialog line. A line break in the name used to split the list. */
    fun singleLineName(fileName: String): String =
        fileName.replace(NEWLINE, " ").ifBlank { "file" }

    /**
     * One file, as it is pasted into the prompt. A body that contains a fence of three
     * backticks used to close the wrapper, so the rest of the file was sent as the message.
     * The fence is one longer than any run in the body. A line break in the name stays
     * on the header line, including a Unicode separator that is not CR or LF.
     */
    fun section(number: Int, fileName: String, content: String): String {
        val name = singleLineName(fileName)
        // trim() also ate the indent on the first line, so a snippet or a patch
        // that starts with spaces was sent as a different file. Only surrounding
        // line breaks are dropped, so the closing fence still sits on its own line.
        // A leading BOM is not a line break, and it is not part of the file.
        val raw = content.removePrefix("\uFEFF")
        if (raw.isBlank()) return "File $number ($name): (empty file)"
        val body = raw.trim('\r', '\n')
        if (body.isEmpty()) return "File $number ($name): (empty file)"
        val fence = "`".repeat(fenceLength(body))
        return "File $number ($name):\n\n${fence}text\n$body\n$fence"
    }

    private fun fenceLength(body: String): Int {
        var longest = 0
        var run = 0
        for (c in body) {
            if (c == '`') {
                run++
                if (run > longest) longest = run
            } else {
                run = 0
            }
        }
        return maxOf(3, longest + 1)
    }

    /** Any Unicode linebreak. CR/LF used to be the only ones folded into the header. */
    private val NEWLINE = Regex("\\R")

    private val DOCUMENT_TYPES = setOf(
        "application/javascript",
        "application/json",
        "application/xml",
        "application/toml",
        "application/sql",
        "image/svg+xml",
    )

    private val CODE_EXTENSIONS = setOf(
        "kt", "kts", "java", "py", "js", "jsx", "mjs", "cjs", "ts", "tsx", "cpp", "c", "h",
        "cs", "php", "rb", "go", "rs", "swift", "html", "css", "json", "xml", "yaml", "yml",
        "toml", "md", "txt", "sh", "sql", "csv", "log", "vue", "svelte", "svg",
    )
}
