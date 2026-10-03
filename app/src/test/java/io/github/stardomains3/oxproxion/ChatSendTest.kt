package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSendTest {

    @Test fun a_send_while_a_chat_is_still_opening_stays_in_the_composer() {
        assertEquals(ChatSend.Outcome.Keep, ChatSend.decide(transitionActive = true))
    }

    @Test fun a_settled_chat_can_take_the_send() {
        assertEquals(ChatSend.Outcome.Start, ChatSend.decide(transitionActive = false))
    }

    @Test fun a_send_whose_chat_changed_before_the_turn_stays_in_the_composer() {
        assertEquals(ChatSend.Outcome.Keep, ChatSend.decide(transitionActive = false, sameChat = false))
        assertEquals(ChatSend.Outcome.Keep, ChatSend.decide(transitionActive = true, sameChat = false))
        assertEquals(ChatSend.Outcome.Start, ChatSend.decide(transitionActive = false, sameChat = true))
    }
}
