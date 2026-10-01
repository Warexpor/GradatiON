package io.github.stardomains3.oxproxion

object RpReminderParser {
    /** Stops at the closing `)_`, so a note may contain parentheses. Every note is kept. The label matches in any case. */
    private val reminderRegex = Regex(
        """_\(Reminder:\s*(.*?)\)_""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    data class Parsed(
        val userText: String,
        val reminder: String?
    )

    fun parse(input: String): Parsed {
        val matches = reminderRegex.findAll(input).toList()
        if (matches.isEmpty()) return Parsed(input.trim(), null)
        val bodies = matches.map { it.groupValues[1].trim() }
        val cleaned = reminderRegex.replace(input, "").trim()
        val reminder = bodies.filter { it.isNotEmpty() }.joinToString("\n")
        return Parsed(cleaned, if (bodies.all { it.isEmpty() }) "" else reminder)
    }
}
