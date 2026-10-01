package io.github.stardomains3.oxproxion

/**
 * How far the transcript scrolls when the keyboard moves, in px. Positive moves the
 * newest message up.
 *
 * The list is full-screen and its bottom padding is the composer plus the keyboard, so
 * scrolling by the whole keyboard travel yanks a short thread that was sitting in the
 * gap above the composer. A message the composer would cover moves by only the covered
 * amount. A message already resting on the composer (within [pinnedSlack] of the old
 * line) rides back down with it when the keyboard closes.
 */
object KeyboardFollow {

    fun scroll(
        lastBottom: Int,
        listHeight: Int,
        newBottomPad: Int,
        oldBottomPad: Int,
        pinnedSlack: Int,
    ): Int {
        if (listHeight <= 0) return 0
        val newLine = listHeight - newBottomPad
        val oldLine = listHeight - oldBottomPad
        val overflow = lastBottom - newLine
        if (overflow > 0) return overflow
        // Resting on the old composer line, not merely somewhere above it. A short thread
        // has a gap; closing the keyboard must leave that gap instead of pulling the row down.
        val wasPinned = kotlin.math.abs(lastBottom - oldLine) <= pinnedSlack
        return if (wasPinned) overflow else 0
    }

    /**
     * Scroll when the composer itself changes height (another line, a staged photo).
     * [following] is false on the first measure, and when the reader has scrolled up.
     * [lastBottom] is negative when the newest row is not on screen.
     */
    fun composerScroll(
        following: Boolean,
        lastBottom: Int,
        listHeight: Int,
        newBottomPad: Int,
        oldBottomPad: Int,
        pinnedSlack: Int,
    ): Int {
        if (!following || lastBottom < 0) return 0
        return scroll(lastBottom, listHeight, newBottomPad, oldBottomPad, pinnedSlack)
    }

    /**
     * Padding for the composer dock when the field takes the screen.
     * [bottom] is the nav bar plus the keyboard. Replacing it with 0 drops the
     * buttons under the keys until the next inset event, which may not come.
     */
    fun dockPadding(expanded: Boolean, topBarHeight: Int, left: Int, right: Int, bottom: Int): IntArray {
        val top = if (expanded) topBarHeight.coerceAtLeast(0) else 0
        return intArrayOf(left, top, right, bottom)
    }
}
