package io.github.stardomains3.oxproxion.code

/**
 * Shell and search logs are terminal text. Color, cursor hides, and a progress
 * line that rewrites itself with `\r` should read as the words, not the escapes.
 * File reads do not come through here: a source file may contain those bytes on purpose.
 */
object TerminalText {

    /** Text with the escapes removed. A string that has none is returned as it arrived. */
    fun readable(text: String): String {
        if (!needsWork(text)) return text
        return render(text)
    }

    private fun needsWork(text: String): Boolean {
        for (c in text) {
            if (c == '\u001b' || c == '\u009b' || c == '\r' || c == '\b') return true
            if (c.code < 32 && c != '\n' && c != '\t') return true
        }
        return false
    }

    private fun render(text: String): String {
        val lines = ArrayList<String>()
        val line = LineBuf()
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '\n' -> {
                    lines += line.take()
                    i++
                }
                '\r' -> {
                    line.col = 0
                    i++
                }
                '\b' -> {
                    if (line.col > 0) line.col--
                    i++
                }
                '\u001b', '\u009b' -> i = consumeEscape(text, i, line)
                else -> if (c.code < 32 && c != '\t') {
                    i++
                } else {
                    line.write(c)
                    i++
                }
            }
        }
        lines += line.take()
        return lines.joinToString("\n")
    }

    /** ESC / C1 CSI. Returns the index just past the sequence. */
    private fun consumeEscape(text: String, i: Int, line: LineBuf): Int {
        if (text[i] == '\u009b') return consumeCsi(text, i + 1, line)
        val n = i + 1
        if (n >= text.length) return text.length
        return when (text[n]) {
            '[' -> consumeCsi(text, n + 1, line)
            ']' -> skipOsc(text, n + 1)
            else -> n + 1
        }
    }

    /**
     * CSI. Color and private modes (`?25l`) are dropped. Erase-line and a few
     * cursor moves are applied so `\r` plus clear-to-end does not leave the old line behind.
     */
    private fun consumeCsi(text: String, start: Int, line: LineBuf): Int {
        var i = start
        var privateSeq = false
        val params = ArrayList<Int>(4)
        var current = -1
        var anyDigit = false
        while (i < text.length) {
            val c = text[i]
            when {
                c == '?' || c == '>' || c == '!' || c == '=' -> {
                    privateSeq = true
                    i++
                }
                c in '0'..'9' -> {
                    val d = c - '0'
                    current = if (current < 0) d else (current * 10 + d).coerceAtMost(9_999)
                    anyDigit = true
                    i++
                }
                c == ';' -> {
                    params += if (anyDigit) current else 0
                    current = -1
                    anyDigit = false
                    i++
                }
                c.code in 0x20..0x2F -> i++
                c.code in 0x40..0x7E -> {
                    if (anyDigit) params += current
                    if (!privateSeq) applyCsi(c, params, line)
                    return i + 1
                }
                else -> return i + 1
            }
        }
        return i
    }

    private fun applyCsi(final: Char, params: List<Int>, line: LineBuf) {
        val p0 = params.firstOrNull() ?: 0
        when (final) {
            'K' -> when (p0) {
                1 -> line.eraseStart()
                2 -> line.eraseAll()
                else -> line.eraseEnd()
            }
            'C' -> line.move(if (p0 == 0) 1 else p0)
            'D' -> line.move(-(if (p0 == 0) 1 else p0))
            'G' -> line.col = ((if (p0 == 0) 1 else p0) - 1).coerceAtLeast(0)
            else -> Unit
        }
    }

    /** OSC (window title and the like), ended by BEL or ST. A newline ends it too. */
    private fun skipOsc(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            if (text[i] == '\u0007') return i + 1
            if (text[i] == '\u001b' && i + 1 < text.length && text[i + 1] == '\\') return i + 2
            if (text[i] == '\n') return i
            i++
        }
        return i
    }

    private class LineBuf {
        private val text = StringBuilder()
        var col = 0

        fun write(ch: Char) {
            while (text.length < col) text.append(' ')
            if (col < text.length) text.setCharAt(col, ch) else text.append(ch)
            col++
        }

        fun move(delta: Int) {
            col = (col + delta).coerceAtLeast(0)
        }

        fun eraseEnd() {
            if (col < text.length) text.delete(col, text.length)
        }

        fun eraseStart() {
            val n = minOf(col, text.length)
            for (k in 0 until n) text.setCharAt(k, ' ')
        }

        fun eraseAll() {
            text.setLength(0)
            col = 0
        }

        fun take(): String {
            val s = text.toString()
            text.setLength(0)
            col = 0
            return s
        }
    }
}
