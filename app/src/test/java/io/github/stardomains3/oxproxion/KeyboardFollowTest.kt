package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardFollowTest {

    private val height = 800
    private val slack = 24

    @Test fun pinned_message_rides_up_with_the_keyboard() {
        // Last row rested on the composer. Keyboard grows the bottom pad by 300.
        val oldPad = 200
        val newPad = 500
        val lastBottom = height - oldPad
        assertEquals(300, KeyboardFollow.scroll(lastBottom, height, newPad, oldPad, slack))
    }

    @Test fun short_thread_stays_put() {
        // A few messages near the top, still above the composer after the keyboard opens.
        val oldPad = 200
        val newPad = 500
        val lastBottom = 240
        assertEquals(0, KeyboardFollow.scroll(lastBottom, height, newPad, oldPad, slack))
    }

    @Test fun covered_message_moves_only_by_the_overlap() {
        // The row was above the composer, but the keyboard would cover its bottom 40px.
        val oldPad = 200
        val newPad = 500
        val lastBottom = height - newPad + 40
        assertEquals(40, KeyboardFollow.scroll(lastBottom, height, newPad, oldPad, slack))
    }

    @Test fun pinned_message_rides_back_down_when_the_keyboard_closes() {
        val oldPad = 500
        val newPad = 200
        val lastBottom = height - oldPad
        assertEquals(-300, KeyboardFollow.scroll(lastBottom, height, newPad, oldPad, slack))
    }

    @Test fun gap_under_a_short_thread_stays_when_the_keyboard_closes() {
        val oldPad = 500
        val newPad = 200
        val lastBottom = 360
        assertEquals(0, KeyboardFollow.scroll(lastBottom, height, newPad, oldPad, slack))
    }

    @Test fun no_list_yet_does_not_scroll() {
        assertEquals(0, KeyboardFollow.scroll(100, 0, 40, 20, slack))
    }
}
