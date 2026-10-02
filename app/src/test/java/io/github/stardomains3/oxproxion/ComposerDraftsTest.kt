package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerDraftsTest {

    @Test fun a_blank_draft_is_dropped_and_text_comes_back_for_that_chat() {
        val stored = ComposerDrafts.remember(emptyMap(), 4L, "still writing")
        assertEquals("still writing", ComposerDrafts.text(stored, 4L))
        assertEquals("", ComposerDrafts.text(stored, 5L))
        assertEquals("", ComposerDrafts.text(ComposerDrafts.remember(stored, 4L, "  \n"), 4L))
    }

    @Test fun the_unsaved_chat_keeps_its_own_slot() {
        val stored = ComposerDrafts.remember(emptyMap(), null, "not sent")
        assertEquals("not sent", ComposerDrafts.text(stored, null))
        assertEquals("not sent", ComposerDrafts.text(stored, 0L))
        assertFalse(stored.containsKey("4"))
    }

    @Test fun a_new_id_takes_the_field_and_clears_the_unsaved_slot() {
        val parked = ComposerDrafts.remember(emptyMap(), null, "old")
        val moved = ComposerDrafts.rekey(parked, from = null, to = 8L, text = "next line")
        assertEquals("", ComposerDrafts.text(moved, null))
        assertEquals("next line", ComposerDrafts.text(moved, 8L))
        val sent = ComposerDrafts.rekey(parked, from = null, to = 8L, text = "")
        assertTrue(sent.isEmpty())
    }

    @Test fun oldest_drafts_fall_off_and_the_round_trip_keeps_order() {
        var stored: Map<String, String> = emptyMap()
        for (id in 1..ComposerDrafts.MAX_KEPT + 5) {
            stored = ComposerDrafts.remember(stored, id.toLong(), "m$id")
        }
        assertEquals(ComposerDrafts.MAX_KEPT, stored.size)
        assertFalse(stored.containsKey("1"))
        assertEquals("m${ComposerDrafts.MAX_KEPT + 5}", ComposerDrafts.text(stored, (ComposerDrafts.MAX_KEPT + 5).toLong()))
        val again = ComposerDrafts.decode(ComposerDrafts.encode(stored))
        assertEquals(stored.keys.toList(), again.keys.toList())
        assertEquals(stored["10"], again["10"])
    }

    @Test fun deleting_a_chat_drops_its_draft_and_leaves_the_unsaved_slot() {
        val stored = ComposerDrafts.remember(
            ComposerDrafts.remember(emptyMap(), null, "not sent"),
            4L,
            "keep me",
        )
        val dropped = ComposerDrafts.drop(stored, 8L)
        assertEquals(stored.keys.toList(), dropped.keys.toList())
        val gone = ComposerDrafts.drop(stored, 4L)
        assertEquals("", ComposerDrafts.text(gone, 4L))
        assertEquals("not sent", ComposerDrafts.text(gone, null))
        assertEquals(listOf(ComposerDrafts.NEW), gone.keys.toList())
    }

    @Test fun a_broken_blob_decodes_as_no_drafts() {
        assertTrue(ComposerDrafts.decode("").isEmpty())
        assertTrue(ComposerDrafts.decode("{not json").isEmpty())
        assertTrue(ComposerDrafts.readable(""))
        assertTrue(ComposerDrafts.readable("[]"))
        assertFalse(ComposerDrafts.readable("{not json"))
    }
}

class AskComposerDraftTest {

    @Test fun the_first_id_is_a_bind_not_a_switch() {
        val (bound, effect) = AskComposerDraft.bind(AskComposerDraft.State(), ChatMode.ASK, 3L)
        assertTrue(effect is AskComposerDraft.Effect.Bind)
        val (same, again) = AskComposerDraft.bind(bound, ChatMode.ASK, 9L)
        assertTrue(again is AskComposerDraft.Effect.None)
        assertEquals(3L, same.sessionId)
    }

    @Test fun leaving_a_chat_switches_and_a_minted_id_promotes() {
        val bound = AskComposerDraft.State(bound = true, mode = ChatMode.ASK, sessionId = 3L)
        val (switched, effect) = AskComposerDraft.change(bound, ChatMode.ASK, 8L, promoted = false)
        val move = effect as AskComposerDraft.Effect.Switch
        assertEquals(3L, move.from)
        assertEquals(8L, move.to)
        assertEquals(8L, switched.sessionId)

        val unsaved = bound.copy(sessionId = null)
        val (_, promoted) = AskComposerDraft.change(unsaved, ChatMode.ASK, 11L, promoted = true)
        assertTrue(promoted is AskComposerDraft.Effect.Promote)
    }

    @Test fun roleplay_does_not_swap_chat_drafts() {
        val bound = AskComposerDraft.State(bound = true, mode = ChatMode.ASK, sessionId = 3L)
        val (rp, effect) = AskComposerDraft.change(bound, ChatMode.RP, 9L, promoted = false)
        assertTrue(effect is AskComposerDraft.Effect.None)
        assertEquals(ChatMode.RP, rp.mode)
        val (back, backEffect) = AskComposerDraft.change(rp, ChatMode.ASK, 3L, promoted = false)
        assertTrue(backEffect is AskComposerDraft.Effect.None)
        assertEquals(3L, back.sessionId)
    }

    @Test fun a_mode_snapshot_is_not_this_threads_typing() {
        assertTrue(AskComposerDraft.takeStoredDraft(field = "", userEdited = false, modeSnapshot = "old"))
        assertTrue(AskComposerDraft.takeStoredDraft(field = "old", userEdited = false, modeSnapshot = "old"))
        assertFalse(AskComposerDraft.takeStoredDraft(field = "from the view", userEdited = false, modeSnapshot = "old"))
        assertFalse(AskComposerDraft.takeStoredDraft(field = "old", userEdited = true, modeSnapshot = "old"))
        assertFalse(AskComposerDraft.takeStoredDraft(field = "", userEdited = true, modeSnapshot = ""))
    }

    @Test fun leaving_ask_parks_typed_or_nonempty_text() {
        // Hub Continue / Settings flip after isRpMode is already true; same rule as parkAskDraft.
        assertTrue(AskComposerDraft.shouldParkText(dirty = true, text = ""))
        assertTrue(AskComposerDraft.shouldParkText(dirty = false, text = "still writing"))
        assertTrue(AskComposerDraft.shouldParkText(dirty = true, text = "still writing"))
        assertFalse(AskComposerDraft.shouldParkText(dirty = false, text = ""))
    }

    @Test fun a_relaunch_keeps_the_roleplay_line_and_drops_the_ask_snapshot() {
        assertTrue(AskComposerDraft.dropOnRelaunch(ChatMode.ASK))
        assertFalse(AskComposerDraft.dropOnRelaunch(ChatMode.RP))
    }

    @Test fun a_change_before_bind_is_ignored() {
        val (state, effect) = AskComposerDraft.change(AskComposerDraft.State(), ChatMode.ASK, 4L, promoted = true)
        assertFalse(state.bound)
        assertTrue(effect is AskComposerDraft.Effect.None)
    }
}
