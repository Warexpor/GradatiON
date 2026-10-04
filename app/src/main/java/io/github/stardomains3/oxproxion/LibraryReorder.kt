package io.github.stardomains3.oxproxion

/**
 * Drag-reorder writes the visible list back to prefs. A search shows a subset, so saving that
 * subset used to drop every prompt or system message that did not match. Drag is only safe
 * when the search is clear and the list is the full library (same [CharSequence.isEmpty] the
 * filters use).
 */
internal fun libraryDragAllowed(searchQuery: CharSequence?): Boolean = searchQuery.isNullOrEmpty()

/**
 * Customs to keep after a System messages drag. Default stays first when the list is unfiltered;
 * if a search hid it, position 0 is a custom and [List.drop] would delete that custom and every
 * non-matching row. Null means do not write.
 */
internal fun customSystemMessagesAfterReorder(visible: List<SystemMessage>): List<SystemMessage>? {
    if (visible.isEmpty() || !visible.first().isDefault) return null
    return visible.drop(1)
}

/**
 * Prefs row for a Prompt library delete. [Prompt.isExpanded] is UI-only but still in data-class
 * equals, so [List.remove] of an expanded row never matched the stored copy (always collapsed)
 * and Delete did nothing. Match title and body the same way System messages already do.
 */
internal fun indexOfStoredPrompt(stored: List<Prompt>, row: Prompt): Int =
    stored.indexOfFirst { it.title == row.title && it.prompt == row.prompt }

