package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEditTest {

    @Test fun a_cut_that_has_not_been_sent_can_be_cancelled() {
        assertTrue(
            ChatEdit.awaitingSend(
                marked = true,
                sameChat = true,
                roleplay = false,
                hasFork = true,
                variant = 2,
                forkIndex = 2,
                messageCount = 2,
            )
        )
    }

    @Test fun regenerate_and_a_sent_replacement_are_not_an_open_edit() {
        assertFalse(
            ChatEdit.awaitingSend(
                marked = false,
                sameChat = true,
                roleplay = false,
                hasFork = true,
                variant = 2,
                forkIndex = 2,
                messageCount = 2,
            )
        )
        assertFalse(
            ChatEdit.awaitingSend(
                marked = true,
                sameChat = true,
                roleplay = false,
                hasFork = true,
                variant = 2,
                forkIndex = 2,
                messageCount = 3,
            )
        )
        assertFalse(
            ChatEdit.awaitingSend(
                marked = true,
                sameChat = false,
                roleplay = false,
                hasFork = true,
                variant = 2,
                forkIndex = 2,
                messageCount = 2,
            )
        )
        assertFalse(
            ChatEdit.awaitingSend(
                marked = true,
                sameChat = true,
                roleplay = true,
                hasFork = true,
                variant = 2,
                forkIndex = 2,
                messageCount = 2,
            )
        )
    }

    @Test fun cancel_puts_back_the_line_that_was_already_in_the_field() {
        assertEquals(
            "still thinking",
            ChatEdit.composerAfterCancel("still thinking", "the message", "the message"),
        )
        assertEquals(
            "",
            ChatEdit.composerAfterCancel("", "the message", "the message"),
        )
    }

    @Test fun a_roleplay_edit_keeps_its_photo_until_the_chat_changes() {
        assertTrue(ChatEdit.keepEditPhoto(roleplay = true, askEditStillOpen = false, sameChat = true))
        assertFalse(ChatEdit.keepEditPhoto(roleplay = true, askEditStillOpen = false, sameChat = false))
        assertTrue(
            ChatEdit.keepEditPhoto(roleplay = false, askEditStillOpen = true, sameChat = true)
        )
        assertFalse(
            ChatEdit.keepEditPhoto(roleplay = false, askEditStillOpen = false, sameChat = true)
        )
    }

    @Test fun cancel_without_a_saved_line_drops_a_copy_of_the_bubble() {
        assertEquals(
            "",
            ChatEdit.composerAfterCancel(null, "the message", "the message"),
        )
        assertEquals(
            "the message, but rewritten",
            ChatEdit.composerAfterCancel(null, "the message, but rewritten", "the message"),
        )
    }
}
