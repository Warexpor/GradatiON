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

    @Test fun composer_growth_leaves_a_short_thread_put() {
        val oldPad = 200
        val newPad = 280
        val lastBottom = 240
        assertEquals(0, KeyboardFollow.composerScroll(true, lastBottom, height, newPad, oldPad, slack))
    }

    @Test fun composer_growth_moves_a_pinned_message_by_the_growth() {
        val oldPad = 200
        val newPad = 280
        val lastBottom = height - oldPad
        assertEquals(80, KeyboardFollow.composerScroll(true, lastBottom, height, newPad, oldPad, slack))
    }

    @Test fun composer_growth_moves_only_the_covered_amount() {
        val oldPad = 200
        val newPad = 280
        val lastBottom = height - newPad + 30
        assertEquals(30, KeyboardFollow.composerScroll(true, lastBottom, height, newPad, oldPad, slack))
    }

    @Test fun composer_shrink_rides_a_pinned_message_back_down() {
        val oldPad = 280
        val newPad = 200
        val lastBottom = height - oldPad
        assertEquals(-80, KeyboardFollow.composerScroll(true, lastBottom, height, newPad, oldPad, slack))
    }

    @Test fun composer_resize_does_not_follow_when_scrolled_away_or_before_the_first_measure() {
        val lastBottom = height - 200
        assertEquals(0, KeyboardFollow.composerScroll(false, lastBottom, height, 280, 200, slack))
        assertEquals(0, KeyboardFollow.composerScroll(true, -1, height, 280, 200, slack))
    }
}
