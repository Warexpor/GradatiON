package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun a_finished_pump_is_not_still_the_live_turn() {
        val finished = Any()
        assertNull(StreamTurn.retainPump(active = finished, finished = finished))
        assertNull(StreamTurn.retainPump(active = null, finished = finished))
    }

    @Test fun a_newer_pump_is_kept_when_the_old_one_finishes() {
        val finished = Any()
        val newer = Any()
        assertEquals(newer, StreamTurn.retainPump(active = newer, finished = finished))
        assertEquals(newer, StreamTurn.retainPump(active = newer, finished = null))
    }
}
