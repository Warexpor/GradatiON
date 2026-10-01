package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSaveSerialTest {

    @Test
    fun aLaterSnapshotOfTheSameChatWins() {
        val serial = ChatSaveSerial()
        val first = serial.claim(sessionId = 10L, epoch = 1)
        val second = serial.claim(sessionId = 10L, epoch = 1)
        assertFalse(serial.isCurrent(first))
        assertTrue(serial.isCurrent(second))
        assertEquals(
            ChatSaveGate.Outcome.Abort,
            ChatSaveGate.persist(serial.isCurrent(first), saveAsNew = false, existingId = 10L, rowExists = true)
        )
        assertEquals(
            ChatSaveGate.Outcome.ProceedExisting,
            ChatSaveGate.persist(serial.isCurrent(second), saveAsNew = false, existingId = 10L, rowExists = true)
        )
    }

    @Test
    fun aDifferentChatDoesNotCancelTheOneBeingLeft() {
        val serial = ChatSaveSerial()
        val leaving = serial.claim(sessionId = 10L, epoch = 1)
        val opened = serial.claim(sessionId = 11L, epoch = 2)
        assertTrue(serial.isCurrent(leaving))
        assertTrue(serial.isCurrent(opened))
    }

    @Test
    fun twoUnsavedChatsBothInsert() {
        val serial = ChatSaveSerial()
        val first = serial.claim(sessionId = null, epoch = 1)
        val second = serial.claim(sessionId = null, epoch = 2)
        assertTrue(serial.isCurrent(first))
        assertTrue(serial.isCurrent(second))
        assertEquals(
            ChatSaveGate.Outcome.ProceedAllocateNew,
            ChatSaveGate.persist(true, saveAsNew = false, existingId = null, rowExists = false)
        )
    }

    @Test
    fun aSecondSaveOfAnUnsavedChatReusesTheMintedId() {
        val serial = ChatSaveSerial()
        val first = serial.claim(sessionId = null, epoch = 3)
        serial.noteMinted(first, 42L)
        val second = serial.claim(sessionId = null, epoch = 3)
        assertFalse(serial.isCurrent(first))
        assertEquals(42L, serial.mintedId(second))
        assertEquals(
            ChatSaveGate.Outcome.ProceedExisting,
            ChatSaveGate.persist(serial.isCurrent(second), saveAsNew = false, existingId = 42L, rowExists = true)
        )
    }

    @Test
    fun aStaleInserterStillPublishesTheRow() {
        val serial = ChatSaveSerial()
        val first = serial.claim(sessionId = null, epoch = 1)
        serial.claim(sessionId = null, epoch = 1)
        serial.noteMinted(first, 7L)
        val third = serial.claim(sessionId = null, epoch = 1)
        assertEquals(7L, serial.mintedId(third))
    }

    @Test
    fun aStaleTicketCannotReplaceAPublishedId() {
        val serial = ChatSaveSerial()
        val first = serial.claim(sessionId = null, epoch = 1)
        val second = serial.claim(sessionId = null, epoch = 1)
        serial.noteMinted(second, 42L)
        serial.noteMinted(first, 7L)
        assertEquals(42L, serial.mintedId(second))
    }

    @Test
    fun aDeletedRowIsNotRecreated() {
        assertEquals(
            ChatSaveGate.Outcome.Abort,
            ChatSaveGate.persist(ticketCurrent = true, saveAsNew = false, existingId = 10L, rowExists = false)
        )
    }

    @Test
    fun anExistingRowStillSyncsTheNotesCapturedWithIt() {
        assertTrue(ChatSaveGate.syncCapturedNotes(snapshotHas = true, storedHas = false))
        assertTrue(ChatSaveGate.syncCapturedNotes(snapshotHas = false, storedHas = true))
        assertFalse(ChatSaveGate.syncCapturedNotes(snapshotHas = false, storedHas = false))
    }

    @Test
    fun aWrittenRowKeepsItsSideDataWhenANewerSaveIsWaiting() {
        // The newer snapshot overwrites these if it runs. The row already written still
        // needs the fork and the facts when the process dies before that.
        assertTrue(ChatSaveGate.recordSideData(rowWritten = true))
        assertFalse(ChatSaveGate.recordSideData(rowWritten = false))
    }

    @Test
    fun leavingStillWritesTheSnapshot() {
        // decide() refuses to attach the row to the chat now on screen. persist() still writes it.
        assertEquals(
            ChatSaveGate.Outcome.Abort,
            ChatSaveGate.decide(
                epochAtSchedule = 1,
                currentEpoch = 2,
                openSessionId = 10L,
                liveSessionId = null,
                rowExists = true,
                saveAsNew = false
            )
        )
        assertEquals(
            ChatSaveGate.Outcome.ProceedExisting,
            ChatSaveGate.persist(ticketCurrent = true, saveAsNew = false, existingId = 10L, rowExists = true)
        )
    }
}
