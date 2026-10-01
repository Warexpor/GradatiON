package io.github.stardomains3.oxproxion

/**
 * Insets for the History drawer. The chat shell consumes window insets on its own
 * root, and the drawer is a sibling, so it never sees the keyboard. [bottom] is the
 * larger of the nav bar and the keyboard: the keyboard inset already includes the
 * nav bar, and adding them would leave a nav-bar gap above the keys.
 */
object HistoryChrome {
    fun bottom(systemBottom: Int, imeBottom: Int): Int = maxOf(systemBottom, imeBottom)
}
