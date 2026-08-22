package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSaveGateTest {

    @Test
    fun epochMismatchAborts() {
        assertEquals(
            ChatSaveGate.Outcome.Abort,
            ChatSaveGate.decide(
                epochAtSchedule = 1,
                currentEpoch = 2,
                openSessionId = 10L,
                liveSessionId = 10L,
                rowExists = true,
                saveAsNew = false
            )
        )
    }

    @Test
    fun deletedRowDoesNotMintZombie() {
        assertEquals(
            ChatSaveGate.Outcome.Abort,
            ChatSaveGate.decide(
                epochAtSchedule = 1,
                currentEpoch = 1,
                openSessionId = 10L,
                liveSessionId = 10L,
                rowExists = false,
                saveAsNew = false
            )
        )
    }

    @Test
    fun clearedLiveSessionAbortsOverwrite() {
        assertEquals(
            ChatSaveGate.Outcome.Abort,
            ChatSaveGate.decide(
                epochAtSchedule = 1,
                currentEpoch = 1,
                openSessionId = 10L,
                liveSessionId = null,
                rowExists = true,
                saveAsNew = false
            )
        )
    }

    @Test
    fun switchedSessionAborts() {
        assertEquals(
            ChatSaveGate.Outcome.Abort,
            ChatSaveGate.decide(
                epochAtSchedule = 1,
                currentEpoch = 1,
                openSessionId = 10L,
                liveSessionId = 11L,
                rowExists = true,
                saveAsNew = false
            )
        )
    }

    @Test
    fun existingRowProceeds() {
        assertEquals(
            ChatSaveGate.Outcome.ProceedExisting,
            ChatSaveGate.decide(
                epochAtSchedule = 3,
                currentEpoch = 3,
                openSessionId = 10L,
                liveSessionId = 10L,
                rowExists = true,
                saveAsNew = false
            )
        )
    }

    @Test
    fun firstSaveAllocates() {
        assertEquals(
            ChatSaveGate.Outcome.ProceedAllocateNew,
            ChatSaveGate.decide(
                epochAtSchedule = 1,
                currentEpoch = 1,
                openSessionId = null,
                liveSessionId = null,
                rowExists = false,
                saveAsNew = false
            )
        )
    }

    @Test
    fun autoSaveSkipsNeverSavedEmpty() {
        assertEquals(
            ChatSaveGate.AutoSaveKind.Skip,
            ChatSaveGate.autoSaveKind(sessionId = null, hasAssistant = false, messagesEmpty = true)
        )
    }

    @Test
    fun autoSaveReusesExistingEvenWhenEmptied() {
        assertEquals(
            ChatSaveGate.AutoSaveKind.ReuseExisting,
            ChatSaveGate.autoSaveKind(sessionId = 5L, hasAssistant = false, messagesEmpty = true)
        )
    }

    @Test
    fun autoSaveFirstNeedsAssistant() {
        assertEquals(
            ChatSaveGate.AutoSaveKind.FirstSaveNeedsAssistant,
            ChatSaveGate.autoSaveKind(sessionId = null, hasAssistant = true, messagesEmpty = false)
        )
    }
}
