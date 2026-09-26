package io.github.stardomains3.oxproxion.code

/**
 * Pure home-list filter for Code sessions: case-insensitive substring match on
 * title, preview (list summary), or session id. Blank query returns [sessions] unchanged.
 */
object CodeSessionFilter {

    fun filterSessions(query: String, sessions: List<CodeSessionState>): List<CodeSessionState> {
        val q = query.trim()
        if (q.isEmpty()) return sessions
        return sessions.filter { s ->
            val sum = s.summary
            sum.title.contains(q, ignoreCase = true) ||
                sum.preview.contains(q, ignoreCase = true) ||
                sum.id.contains(q, ignoreCase = true)
        }
    }
}
