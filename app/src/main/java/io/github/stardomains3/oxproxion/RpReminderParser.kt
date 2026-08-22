package io.github.stardomains3.oxproxion

object RpReminderParser {
    private val reminderRegex = Regex("""_\(Reminder:\s*(.+?)\)_""", RegexOption.DOT_MATCHES_ALL)

    data class Parsed(
        val userText: String,
        val reminder: String?
    )

    fun parse(input: String): Parsed {
        val match = reminderRegex.find(input) ?: return Parsed(input.trim(), null)
        val reminder = match.groupValues[1].trim()
        val cleaned = input.replace(reminderRegex, "").trim()
        return Parsed(cleaned, reminder)
    }
}
