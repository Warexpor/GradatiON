package io.github.stardomains3.oxproxion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeControllersTest {
    @Test
    fun askShowsSavedToolPrefs() {
        val ask = AskModeController()
        assertTrue(ask.toolsSelected(true))
        assertFalse(ask.toolsSelected(false))
        assertTrue(ask.webSearchSelected(true))
        assertTrue(ask.allowsToolToggle())
    }

    @Test
    fun roleplayHidesToolsWithoutReadingTheAskPref() {
        val rp = RpModeController()
        assertFalse(rp.toolsSelected(true))
        assertFalse(rp.webSearchSelected(true))
        assertFalse(rp.allowsToolToggle())
    }
}
