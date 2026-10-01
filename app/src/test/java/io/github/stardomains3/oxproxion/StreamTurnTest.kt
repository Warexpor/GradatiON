package io.github.stardomains3.oxproxion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTurnTest {

    @Test fun the_turn_that_was_stopped_may_still_remove_its_own_placeholder() {
        val job = Any()
        assertTrue(StreamTurn.applyCancelCleanup(active = job, cancelled = job))
    }

    @Test fun a_cancel_does_not_touch_the_reply_a_later_send_already_owns() {
        val stopped = Any()
        val sending = Any()
        assertFalse(StreamTurn.applyCancelCleanup(active = sending, cancelled = stopped))
        assertFalse(StreamTurn.applyCancelCleanup(active = null, cancelled = stopped))
        assertFalse(StreamTurn.applyCancelCleanup(active = sending, cancelled = null))
    }
}
