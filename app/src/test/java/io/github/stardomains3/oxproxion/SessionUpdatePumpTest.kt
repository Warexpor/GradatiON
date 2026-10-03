package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.AvailableCommand
import io.github.stardomains3.oxproxion.code.CodeEvent
import io.github.stardomains3.oxproxion.code.CodeSessionEntity
import io.github.stardomains3.oxproxion.code.CodeSessionFolder
import io.github.stardomains3.oxproxion.code.CodeSessionState
import io.github.stardomains3.oxproxion.code.CodeSessionSummary
import io.github.stardomains3.oxproxion.code.CodeUpdate
import io.github.stardomains3.oxproxion.code.SessionStatus
import io.github.stardomains3.oxproxion.code.ToolKind
import io.github.stardomains3.oxproxion.code.ToolStatus
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
    fun folderStoresAvailableCommandsWithoutTouchingTranscript() {
        var state = CodeSessionState(summary(), running = false)
        val cmds = listOf(AvailableCommand("compact", "Compact context"))
        state = CodeSessionFolder.apply(state, CodeUpdate.AvailableCommands(cmds), now = 5L)
        assertEquals(cmds, state.availableCommands)
        assertTrue(state.events.isEmpty())
        assertFalse(state.running)
        assertEquals(2L, state.summary.updatedAt) // summary untouched
        assertFalse(CodeSessionFolder.needsPersist(CodeUpdate.AvailableCommands(cmds)))
    }

    @Test
    fun listPreviewKeepsSnakeCaseAndFollowsTheLiveTool() {
        var state = CodeSessionState(summary(), running = true)
        state = CodeSessionFolder.apply(
            state,
            CodeUpdate.TextChunk("k", "use `snake_case` and **bold** and _note_"),
            now = 10L,
        )
        assertEquals("use snake_case and bold and note", state.summary.preview)
        state = CodeSessionFolder.apply(
            state,
            CodeUpdate.Upsert(
                CodeEvent.ToolCall(
                    "tool:1", 11L, "1", ToolKind.EXECUTE, "Bash", "npm test", ToolStatus.RUNNING,
                ),
            ),
            now = 11L,
        )
        assertEquals("Bash · npm test", state.summary.preview)
        state = CodeSessionFolder.apply(state, CodeUpdate.ToolPatch("1", ToolStatus.COMPLETED), now = 12L)
        assertEquals("use snake_case and bold and note", state.summary.preview)
        state = CodeSessionFolder.apply(
            state,
            CodeUpdate.SessionInfo(preview = "from the bridge"),
            now = 13L,
        )
        assertEquals("from the bridge", state.summary.preview)
    }

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
    fun cancelledTextChunksDoNotReviveRunningBeforeTurnDone() {
        val sessions = mapOf("s1" to CodeSessionState(summary(), running = false))
        val result = foldSessionUpdates(
            sessions,
            listOf(SessionUpdate("s1", CodeUpdate.TextChunk("k", "late"))),
            now = 99L,
            suppressRunningFromChunks = setOf("s1"),
        )
        assertFalse(result.sessions!!["s1"]!!.running)

        val done = foldSessionUpdates(
            result.sessions,
            listOf(SessionUpdate("s1", CodeUpdate.TurnDone("cancelled"))),
            now = 100L,
        )
        assertFalse(done.sessions!!["s1"]!!.running)
    }

    @Test
    fun staleCancelTurnDoneAfterNewPromptKeepsRunning() {
        // H1/B1: prompt after cancel stamps ignoreStaleCancel → queued cancel TurnDone keeps running.
        val sessions = mapOf("s1" to CodeSessionState(summary(), running = true))
        val result = foldSessionUpdates(
            sessions,
            listOf(SessionUpdate("s1", CodeUpdate.TurnDone("cancelled"))),
            now = 101L,
            suppressRunningFromChunks = emptySet(),
            ignoreStaleCancelTurnDone = setOf("s1"),
        )
        assertTrue(result.sessions!!["s1"]!!.running)
    }

    @Test
    fun turnDoneSettlesToolsLeftRunning() {
        val live = CodeEvent.ToolCall("tool:1", 10L, "1", ToolKind.EDIT, "Edit", "a.kt", ToolStatus.PENDING)
        val done = CodeEvent.ToolCall("tool:2", 11L, "2", ToolKind.READ, "Read", "b.kt", ToolStatus.COMPLETED)
        val start = CodeSessionState(summary(), events = listOf(done, live), running = true)
        val ended = CodeSessionFolder.apply(start, CodeUpdate.TurnDone("cancelled"), now = 12L)
        val tools = ended.events.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals("a tool cut off by the turn's end stops spinning", ToolStatus.CANCELLED, tools["1"]!!.status)
        assertEquals(ToolStatus.COMPLETED, tools["2"]!!.status)
        // A stale cancel lands after the next prompt began: its tools are still live.
        val stale = CodeSessionFolder.apply(start, CodeUpdate.TurnDone("cancelled"), now = 12L, ignoreStaleCancelTurnDone = true)
        assertEquals(ToolStatus.PENDING, stale.events.filterIsInstance<CodeEvent.ToolCall>().first { it.callId == "1" }.status)
    }

    @Test
    fun cancelTurnDoneWithSuppressClearsRunning() {
        // Stop-only: suppress set, no ignore stamp → cancelled clears running.
        val sessions = mapOf("s1" to CodeSessionState(summary(), running = true))
        val result = foldSessionUpdates(
            sessions,
            listOf(SessionUpdate("s1", CodeUpdate.TurnDone("cancelled"))),
            now = 102L,
            suppressRunningFromChunks = setOf("s1"),
        )
        assertFalse(result.sessions!!["s1"]!!.running)
    }

    @Test
    fun naturalCancelledTurnDoneClearsRunningWithoutIgnoreStamp() {
        // B1: ACP session/prompt stopReason=="cancelled" with no local cancel stamp must clear.
        val sessions = mapOf("s1" to CodeSessionState(summary(), running = true))
        val result = foldSessionUpdates(
            sessions,
            listOf(SessionUpdate("s1", CodeUpdate.TurnDone("cancelled"))),
            now = 103L,
            suppressRunningFromChunks = emptySet(),
            ignoreStaleCancelTurnDone = emptySet(),
        )
        assertFalse(result.sessions!!["s1"]!!.running)
    }

    @Test
    fun historicalUserPromptDoesNotForceRunning() {
        // E2: session/load replays user_message_chunk as UserPrompt; must not flip idle → Stop.
        var state = CodeSessionState(summary(), running = false)
        state = CodeSessionFolder.apply(
            state,
            CodeUpdate.Upsert(CodeEvent.UserPrompt("user:1", 1L, "past prompt")),
            now = 10L,
        )
        assertFalse(state.running)
        assertTrue(state.events.any { it is CodeEvent.UserPrompt })
        // G4: history TextChunk must not force Stop either (Hub.prompt / SessionInfo drive live).
        state = CodeSessionFolder.apply(state, CodeUpdate.TextChunk("k", "hi"), now = 11L)
        assertFalse(state.running)
        assertEquals("hi", state.events.filterIsInstance<CodeEvent.AgentText>().single().text)
        // Live turn: Hub.prompt already set running — chunks / UserPrompt keep it.
        state = state.copy(running = true)
        state = CodeSessionFolder.apply(state, CodeUpdate.TextChunk("k", " more"), now = 12L)
        assertTrue(state.running)
        state = CodeSessionFolder.apply(
            state,
            CodeUpdate.Upsert(CodeEvent.UserPrompt("user:2", 2L, "live")),
            now = 13L,
        )
        assertTrue(state.running)
    }

    @Test
    fun historicalTextChunkDoesNotForceRunning() {
        // G4: session/load agent_message_chunk alone must leave idle sessions idle.
        var state = CodeSessionState(summary(), running = false)
        state = CodeSessionFolder.apply(state, CodeUpdate.TextChunk("k", "past"), now = 10L)
        assertFalse(state.running)
        state = CodeSessionFolder.apply(
            state,
            CodeUpdate.SessionInfo(status = SessionStatus.RUNNING),
            now = 11L,
        )
        assertTrue(state.running)
        state = CodeSessionFolder.apply(
            state,
            CodeUpdate.SessionInfo(status = SessionStatus.IDLE),
            now = 12L,
        )
        assertFalse(state.running)
    }

    @Test
    fun currentModeUpdateChangesThePillWithoutClearingTitle() {
        var state = CodeSessionState(
            summary().copy(permissionMode = PermissionMode.ASK, title = "Keep me"),
            running = true,
        )
        state = CodeSessionFolder.apply(
            state,
            CodeUpdate.SessionInfo(permissionMode = PermissionMode.PLAN),
            now = 20L,
        )
        assertEquals(PermissionMode.PLAN, state.summary.permissionMode)
        assertEquals("Keep me", state.summary.title)
        assertTrue(state.running)
        assertTrue(CodeSessionFolder.needsPersist(CodeUpdate.SessionInfo(permissionMode = PermissionMode.PLAN)))
    }

    @Test
    fun sessionInfoTitleAppliesUnlessTheUserRenamed() {
        var state = CodeSessionState(summary().copy(title = "First line"))
        state = CodeSessionFolder.apply(state, CodeUpdate.SessionInfo(title = "Agent name"), now = 3L)
        assertEquals("Agent name", state.summary.title)
        state = CodeSessionFolder.apply(
            state,
            CodeUpdate.SessionInfo(title = "Other"),
            now = 4L,
            keepLocalTitle = true,
        )
        assertEquals("Agent name", state.summary.title)
        state = CodeSessionFolder.apply(state, CodeUpdate.SessionInfo(title = "   "), now = 5L)
        assertEquals("Agent name", state.summary.title)
        val folded = foldSessionUpdates(
            mapOf("s1" to state),
            listOf(SessionUpdate("s1", CodeUpdate.SessionInfo(title = "From the wire"))),
            now = 6L,
            pinnedTitles = setOf("s1"),
        )
        assertEquals("Agent name", folded.sessions!!["s1"]!!.summary.title)
    }

    @Test
    fun naturalTurnDoneClearsRunningEvenWithoutSuppress() {
        val sessions = mapOf("s1" to CodeSessionState(summary(), running = true))
        val result = foldSessionUpdates(
            sessions,
            listOf(SessionUpdate("s1", CodeUpdate.TurnDone("end_turn"))),
            now = 104L,
            suppressRunningFromChunks = emptySet(),
        )
        assertFalse(result.sessions!!["s1"]!!.running)
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

    @Test
    fun foldedTranscriptIsCappedAtTheNewestEvents() {
        var state = CodeSessionState(summary(), running = true)
        repeat(CodeSessionFolder.MAX_EVENTS + 50) { i ->
            state = CodeSessionFolder.apply(
                state,
                CodeUpdate.Upsert(CodeEvent.Notice("n$i", i.toLong(), "note $i")),
                now = 1L,
            )
        }
        assertTrue(state.events.size <= CodeSessionFolder.MAX_EVENTS)
        assertEquals("n${CodeSessionFolder.MAX_EVENTS + 49}", state.events.last().key)
        assertFalse("oldest events are the ones dropped", state.events.any { it.key == "n0" })
    }

    @Test
    fun onlyTheNewestInlineImagesKeepTheirBase64() {
        var state = CodeSessionState(summary(), running = true)
        val total = CodeSessionFolder.MAX_LIVE_IMAGES + 3
        repeat(total) { i ->
            state = CodeSessionFolder.apply(
                state,
                CodeUpdate.ImageChunk("img$i", "image/png", "DATA$i"),
                now = 1L,
            )
        }
        val images = state.events.filterIsInstance<CodeEvent.AgentText>().flatMap { it.images }
        assertEquals(total, images.size)
        assertEquals(total - CodeSessionFolder.MAX_LIVE_IMAGES, images.count { it.data.isEmpty() })
        assertTrue("the newest image is intact", images.last().data.isNotEmpty())
        assertTrue("the oldest image was shed", images.first().data.isEmpty())
    }
}
