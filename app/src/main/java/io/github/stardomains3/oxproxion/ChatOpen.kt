package io.github.stardomains3.oxproxion

/** When a transcript landing on screen is a chat being opened (unit-tested). */
object ChatOpen {
    /** Replies parsed off the main thread before an opened chat shows: its last screen or two. */
    const val WARM_ROWS = 8

    /**
     * True when this list is a saved chat that was just opened ([newOpen]), including the one
     * already on screen tapped again. The mode's cached thread put back at its spot is not an
     * open, nor is an empty list.
     */
    fun opensThread(newOpen: Boolean, cachedThreadShown: Boolean, incomingEmpty: Boolean): Boolean =
        newOpen && !cachedThreadShown && !incomingEmpty
}
