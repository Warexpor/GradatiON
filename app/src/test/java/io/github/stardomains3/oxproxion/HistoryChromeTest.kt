package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryChromeTest {
    @Test fun keyboard_covers_the_nav_bar_instead_of_stacking_on_it() {
        assertEquals(48, HistoryChrome.bottom(systemBottom = 48, imeBottom = 0))
        assertEquals(48, HistoryChrome.bottom(systemBottom = 48, imeBottom = 20))
        assertEquals(820, HistoryChrome.bottom(systemBottom = 48, imeBottom = 820))
    }
}
