/**
 * When an idle roleplay thread (no user turn yet) should take the card's greeting again.
 * A rewrite of that opening stays until the greeting text on the card itself changes.
 * A bubble that is still the card's line follows a rename or a new persona name.
 */
object RpGreetingSync {
    data class Refresh(val expandedBefore: String, val templateChanged: Boolean)

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
