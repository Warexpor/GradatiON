/**
 * When an idle roleplay thread (no user turn yet) should take the card's greeting again.
 * A rewrite of that opening stays until the greeting text on the card itself changes.
 * A bubble that is still the card's line follows a rename or a new persona name.
 * Replacing the bubble drops any rewrite versions of that opening: they would otherwise
 * linger and show a version navigator after the next user turn for text that is gone.
 */
object RpGreetingSync {
    data class Refresh(val expandedBefore: String, val templateChanged: Boolean)

    /** Trailing space from the editor is not a new greeting, so it must not replace a rewrite. */
    fun greetingTextChanged(before: String, after: String): Boolean = before.trim() != after.trim()

    fun shouldReplace(
        current: String,
        expandedBefore: String?,
        expandedNow: String,
        templateChanged: Boolean
    ): Boolean {
        if (current == expandedNow) return false
        if (current.isBlank()) return true
        // Without the previous card line, a non-empty bubble might be a rewrite.
        if (expandedBefore == null) return false
        if (current == expandedBefore) return true
        return templateChanged
    }
}
