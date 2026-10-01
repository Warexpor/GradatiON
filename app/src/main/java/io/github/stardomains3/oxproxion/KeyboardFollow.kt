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
        val wasPinned = lastBottom >= oldLine - pinnedSlack
        return if (wasPinned) overflow else 0
    }
}
