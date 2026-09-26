package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.CodeEvent
import io.github.stardomains3.oxproxion.code.CodeSessionEntity
import io.github.stardomains3.oxproxion.code.CodeSessionFolder
import io.github.stardomains3.oxproxion.code.CodeSessionState
import io.github.stardomains3.oxproxion.code.CodeSessionSummary
import io.github.stardomains3.oxproxion.code.CodeUpdate
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.PermissionMode
import io.github.stardomains3.oxproxion.code.SessionUpdate
import io.github.stardomains3.oxproxion.code.SessionUpdatePump
import io.github.stardomains3.oxproxion.code.foldSessionUpdates
import io.github.stardomains3.oxproxion.code.mergeLegacySessionRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure fold + frame-coalesce pump for CodeHub stream UI (§5.3). */
class SessionUpdatePumpTest {

    private fun summary(id: String = "s1", hostId: String = "h1", lastSeq: Long? = null) =
        CodeSessionSummary(
            id = id,
            hostId = hostId,
            harness = HarnessKind.CLAUDE_CODE,
            workspace = "/w",
            title = id,
            createdAt = 1L,
            updatedAt = 2L,
            permissionMode = PermissionMode.ASK,
            lastSeq = lastSeq,
        )

    @Test
    fun folderMergesTextChunksAndTurnDone() {
        var state = CodeSessionState(summary(), running = true)
        state = CodeSessionFolder.apply(state, CodeUpdate.TextChunk("k", "Hello"), now = 10L)
        state = CodeSessionFolder.apply(state, CodeUpdate.TextChunk("k", " world"), now = 11L)
        state = CodeSessionFolder.apply(state, CodeUpdate.TurnDone("end_turn", "done"), now = 12L)
        assertEquals("Hello world", (state.events[0] as CodeEvent.AgentText).text)
        assertFalse((state.events[0] as CodeEvent.AgentText).streaming)
        assertTrue(state.events.last() is CodeEvent.TurnEnd)
        assertFalse(state.running)
        assertTrue(CodeSessionFolder.needsPersist(CodeUpdate.TurnDone("end_turn")))
        assertTrue(CodeSessionFolder.needsPersist(CodeUpdate.SessionInfo(title = "t")))
        assertFalse(CodeSessionFolder.needsPersist(CodeUpdate.TextChunk("k", "x")))
    }

    @Test
    fun foldBatchSingleMapWriteCoversMultiSession() {
        val sessions = mapOf(
            "a" to CodeSessionState(summary("a"), running = true),
            "b" to CodeSessionState(summary("b"), running = true),
        )
        val batch = listOf(
            SessionUpdate("a", CodeUpdate.TextChunk("k", "A")),
            SessionUpdate("b", CodeUpdate.TextChunk("k", "B")),
            SessionUpdate("a", CodeUpdate.TextChunk("k", "a")),
            SessionUpdate("a", CodeUpdate.TurnDone("end_turn")),
            SessionUpdate("missing", CodeUpdate.TextChunk("k", "nope")),
        )
        val result = foldSessionUpdates(sessions, batch, now = 99L)
        assertTrue(result.needsPersist)
        val next = result.sessions!!
        assertEquals("Aa", (next["a"]!!.events[0] as CodeEvent.AgentText).text)
        assertFalse(next["a"]!!.running)
        assertEquals("B", (next["b"]!!.events[0] as CodeEvent.AgentText).text)
        assertTrue(next["b"]!!.running)
        assertNull(next["missing"])
        assertEquals(99L, next["a"]!!.summary.updatedAt)
    }

    @Test
    fun pumpDrainsAllPendingInOneBatchPerFrame() = runBlocking {
        val drained = mutableListOf<List<SessionUpdate>>()
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pump = SessionUpdatePump(
            scope = scope,
            onDrain = { drained.add(it) },
            delayMs = { gate.receive() },
            dispatcher = Dispatchers.Unconfined,
        )
        try {
            // Prime one drain then park on the frame gate (Unconfined may resume yield on
            // another thread, so we do not rely on a same-thread offer burst here).
            pump.offer(SessionUpdate("s1", CodeUpdate.TextChunk("k", "a")))
            yield()
            assertEquals(1, drained.size)
            assertEquals(1, drained[0].size)
            // Offers while the consumer is inside delayMs coalesce into the next drain.
            pump.offer(SessionUpdate("s1", CodeUpdate.TextChunk("k", "b")))
            pump.offer(SessionUpdate("s1", CodeUpdate.TextChunk("k", "c")))
            pump.offer(SessionUpdate("s1", CodeUpdate.TurnDone("end_turn")))
            gate.send(Unit)
            yield()
            assertEquals(2, drained.size)
            assertEquals(3, drained[1].size)
            gate.send(Unit)
        } finally {
            pump.cancel()
            scope.cancel()
        }
    }

    @Test
    fun pumpCapsBatchAndSkipsDelayWhileBacklogRemains() = runBlocking {
        val drained = mutableListOf<List<SessionUpdate>>()
        val delays = mutableListOf<Long>()
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pump = SessionUpdatePump(
            scope = scope,
            onDrain = { drained.add(it) },
            maxBatch = 2,
            delayMs = {
                delays.add(it)
                gate.receive()
            },
            dispatcher = Dispatchers.Unconfined,
        )
        try {
            pump.offer(SessionUpdate("s1", CodeUpdate.TextChunk("k", "a")))
            yield()
            assertEquals(1, drained.size)
            assertEquals(1, delays.size) // channel empty → paced
            // Five offers while parked: next release should drain 2+2+1 without mid-backlog delays.
            repeat(5) { i ->
                pump.offer(SessionUpdate("s1", CodeUpdate.TextChunk("k", "$i")))
            }
            gate.send(Unit)
            assertEquals(listOf(1, 2, 2, 1), drained.map { it.size })
            assertEquals(2, delays.size) // only after catch-up (channel empty again)
            // Order preserved across capped drains.
            assertEquals(
                listOf("0", "1", "2", "3", "4"),
                drained.drop(1).flatten().map { (it.update as CodeUpdate.TextChunk).chunk },
            )
            gate.send(Unit)
        } finally {
            pump.cancel()
            scope.cancel()
        }
    }

    @Test
    fun mergeLegacyFillsMissingAndPrefersNewerLastSeq() {
        val room = listOf(
            CodeSessionEntity.from(summary("keep", lastSeq = 10L).copy(title = "Room")),
            CodeSessionEntity.from(summary("bump", lastSeq = 5L).copy(title = "RoomBump")),
            CodeSessionEntity.from(summary("nullSeq", lastSeq = null).copy(title = "Null")),
        )
        val legacy = listOf(
            summary("keep", lastSeq = 3L).copy(title = "PrefsOld"),
            summary("bump", lastSeq = 9L).copy(title = "PrefsNewerSeq"),
            summary("nullSeq", lastSeq = 2L).copy(title = "PrefsFillSeq"),
            summary("fresh", lastSeq = 1L).copy(title = "OnlyInPrefs"),
        )
        val upserts = mergeLegacySessionRows(room, legacy)
        val byId = upserts.associateBy { it.id }
        assertNull(byId["keep"]) // Room already has higher lastSeq
        assertEquals(9L, byId["bump"]!!.lastSeq)
        assertEquals("RoomBump", byId["bump"]!!.title) // keep Room fields
        assertEquals(2L, byId["nullSeq"]!!.lastSeq)
        assertEquals("Null", byId["nullSeq"]!!.title)
        assertEquals("OnlyInPrefs", byId["fresh"]!!.title)
        assertEquals(1L, byId["fresh"]!!.lastSeq)
    }

    @Test
    fun mergeLegacyEmptyRoomImportsAll() {
        val legacy = listOf(summary("a", lastSeq = 1L), summary("b", lastSeq = 2L))
        val upserts = mergeLegacySessionRows(emptyList(), legacy)
        assertEquals(listOf("a", "b"), upserts.map { it.id })
    }
}
