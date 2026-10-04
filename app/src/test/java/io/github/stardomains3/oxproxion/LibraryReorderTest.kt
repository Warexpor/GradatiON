package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drag-reorder used to write the visible (possibly filtered) list back to prefs, which dropped
 * every Prompt library or System messages row that did not match the search.
 */
class LibraryReorderTest {

    @Test
    fun dragIsOffWhileSearching() {
        assertTrue(libraryDragAllowed(null))
        assertTrue(libraryDragAllowed(""))
        // Filters use isEmpty, not isBlank: spaces count as a query and hide non-matches.
        assertFalse(libraryDragAllowed(" "))
        assertFalse(libraryDragAllowed("zzz"))
        assertFalse(libraryDragAllowed("Stand"))
    }

    @Test
    fun systemMessageCustomsOnlyWhenDefaultLeads() {
        val default = SystemMessage("Default", "You are helpful.", isDefault = true)
        val a = SystemMessage("A", "one")
        val b = SystemMessage("B", "two")
        assertEquals(listOf(a, b), customSystemMessagesAfterReorder(listOf(default, a, b)))
        assertEquals(emptyList<SystemMessage>(), customSystemMessagesAfterReorder(listOf(default)))
        // A search that hid Default: position 0 is a custom — must not write.
        assertNull(customSystemMessagesAfterReorder(listOf(a, b)))
        assertNull(customSystemMessagesAfterReorder(emptyList()))
    }

    @Test
    fun promptDeleteIgnoresExpandState() {
        val saved = Prompt("Stand", "say hi")
        val expanded = Prompt("Stand", "say hi", isExpanded = true)
        // Data-class equals includes isExpanded, so List.remove of the expanded row would miss.
        assertTrue(saved != expanded)
        assertEquals(0, indexOfStoredPrompt(listOf(saved, Prompt("Other", "x")), expanded))
        assertEquals(-1, indexOfStoredPrompt(listOf(Prompt("Stand", "other body")), expanded))
        assertEquals(-1, indexOfStoredPrompt(emptyList(), expanded))
    }
}
