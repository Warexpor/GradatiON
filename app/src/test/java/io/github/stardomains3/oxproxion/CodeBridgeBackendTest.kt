package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.AcpAdapter
import io.github.stardomains3.oxproxion.code.BridgeBackend
import io.github.stardomains3.oxproxion.code.BrowseEntry
import io.github.stardomains3.oxproxion.code.GitFileStatus
import io.github.stardomains3.oxproxion.code.GitStatusResult
import io.github.stardomains3.oxproxion.code.HarnessInfo
import io.github.stardomains3.oxproxion.code.CodeHost
import io.github.stardomains3.oxproxion.code.CodeSessionSummary
import io.github.stardomains3.oxproxion.code.CodeTransport
import io.github.stardomains3.oxproxion.code.CodeEvent
import io.github.stardomains3.oxproxion.code.CodeUpdate
import io.github.stardomains3.oxproxion.code.NoticeLevel
import io.github.stardomains3.oxproxion.code.SessionStatus
import io.github.stardomains3.oxproxion.code.ConnectionState
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.PermissionMode
import io.github.stardomains3.oxproxion.code.SessionUpdate
import io.github.stardomains3.oxproxion.code.TranscriptReducer
import io.github.stardomains3.oxproxion.code.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure (no socket) coverage of BridgeBackend reconnect resume (`session/load` + afterSeq)
 * and the prompt outbox while disconnected.
 */
class CodeBridgeBackendTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val json = Json { ignoreUnknownKeys = true }

    @After fun tearDown() {
        scope.cancel()
    }

    private class FakeTransport : CodeTransport {
        private val json = Json { ignoreUnknownKeys = true }
        private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val state: StateFlow<ConnectionState> = _state
        private val _incoming = MutableSharedFlow<String>(
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        override val incoming: SharedFlow<String> = _incoming
        override var lastError: String? = null
        val sent = CopyOnWriteArrayList<String>()
        var backgrounded = false
        var keepAlive = false
        /** When true, the next session/prompt send fails once (simulates deliver failure). */
        var failPromptSendOnce = false
        /**
         * When set, a session/prompt send (after [failPromptSendOnce]) counts [promptHoldEntries]
         * and blocks until the latch opens. Used to overlap two outbox flushers.
         */
        var holdPromptSend: CountDownLatch? = null
        var promptHoldEntered: CountDownLatch? = null
        val promptHoldEntries = java.util.concurrent.atomic.AtomicInteger(0)
        /** Prompts already delivered when the failing send happened (-1 = it hasn't happened). */
        @Volatile var promptsSentAtFailure = -1
        /** When true, the next non-prompt send fails once (approval answer / cancel). */
        var failNextNonPromptSend = false
        /** When true, the next session/cancel send fails once (R3 queue-full / drop). */
        var failCancelSendOnce = false
        /**
         * AWAY-02: when set, non-prompt [send] counts down [nonPromptSendEntered] then blocks
         * until [holdNonPromptSend] reaches zero (so a second answer can race the in-flight set).
         */
        var holdNonPromptSend: CountDownLatch? = null
        var nonPromptSendEntered: CountDownLatch? = null
        private val open = AtomicBoolean(false)

        var connectCount = 0

        override fun connect() {
            connectCount++
            open.set(true)
            lastError = null
            _state.value = ConnectionState.CONNECTING
            _state.value = ConnectionState.CONNECTED
        }

        override fun send(frame: String): Boolean {
            if (!open.get() || _state.value != ConnectionState.CONNECTED) return false
            if (failPromptSendOnce && frame.contains("session/prompt")) {
                promptsSentAtFailure = sent.count { it.contains("session/prompt") }
                failPromptSendOnce = false
                // Stay CONNECTED — R4 queue-full / backpressure (not a drop).
                lastError = "Send queue full"
                return false
            }
            if (frame.contains("session/prompt") && holdPromptSend != null) {
                promptHoldEntries.incrementAndGet()
                promptHoldEntered?.countDown()
                holdPromptSend!!.await(5, TimeUnit.SECONDS)
            }
            if (failCancelSendOnce && frame.contains("session/cancel")) {
                failCancelSendOnce = false
                lastError = "Not connected"
                return false
            }
            if (failNextNonPromptSend && !frame.contains("session/prompt")) {
                failNextNonPromptSend = false
                lastError = "Not connected"
                return false
            }
            if (!frame.contains("session/prompt") && holdNonPromptSend != null) {
                nonPromptSendEntered?.countDown()
                holdNonPromptSend!!.await(5, TimeUnit.SECONDS)
            }
            sent += frame
            return true
        }

        override fun close() {
            open.set(false)
            _state.value = ConnectionState.DISCONNECTED
        }

        override fun setAppBackgrounded(backgrounded: Boolean) {
            this.backgrounded = backgrounded
        }

        override fun setKeepAliveForSession(keepAlive: Boolean) {
            this.keepAlive = keepAlive
        }

        fun drop() {
            open.set(false)
            _state.value = ConnectionState.DISCONNECTED
        }

        fun restore() {
            open.set(true)
            lastError = null
            _state.value = ConnectionState.CONNECTED
        }

        suspend fun deliver(frame: String) {
            _incoming.emit(frame)
        }

        fun replyToPending(methodFilter: (String) -> Boolean, resultJson: String = "{}") {
            val frame = sent.lastOrNull { methodFilter(methodOf(it)) } ?: return
            val id = json.parseToJsonElement(frame).jsonObject["id"]!!.jsonPrimitive.longOrNull ?: return
            check(_incoming.tryEmit("""{"jsonrpc":"2.0","id":$id,"result":$resultJson}"""))
        }

        private fun methodOf(frame: String): String =
            json.parseToJsonElement(frame).jsonObject["method"]?.jsonPrimitive?.content ?: ""
    }

    private fun host() = CodeHost(
        id = "h1", name = "Test", url = "ws://x/v1", token = "t",
        transport = TransportKind.BRIDGE
    )

    private fun summary(id: String = "s1") = CodeSessionSummary(
        id = id, hostId = "h1", harness = HarnessKind.OPENCODE, workspace = "/w",
        title = "T", createdAt = 1L, updatedAt = 1L, permissionMode = PermissionMode.ASK
    )

    /** Auto-answers initialize / session/load / session/new / bridge.* so RPC calls complete. */
    private fun autoAnswer(transport: FakeTransport, adapter: AcpAdapter): Job =
        scope.launch {
            val answered = HashSet<Long>()
            while (true) {
                for (frame in transport.sent.toList()) {
                    val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: continue
                    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                    if (id in answered) continue
                    val method = obj["method"]?.jsonPrimitive?.content ?: continue
                    val result = when (method) {
                        "initialize" -> """{"protocolVersion":1}"""
                        "session/load" -> "{}"
                        "session/new" -> """{"sessionId":"s1"}"""
                        "bridge/listSessions" -> """{"sessions":[]}"""
                        "bridge/listWorkspaces" -> """{"workspaces":[]}"""
                        "bridge/listHarnesses" -> """{"harnesses":[]}"""
                        "bridge/browse" -> """{"entries":[]}"""
                        "session/set_mode" -> "{}"
                        "session/prompt" -> continue // left pending on purpose unless test answers
                        else -> "{}"
                    }
                    answered += id
                    transport.deliver("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                }
                delay(5)
            }
        }

    @Test fun outboxSendsPromptAfterReconnect() = runBlocking {
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) { backend.connection.first { it == ConnectionState.CONNECTED } }
            // Wait until initialize completed (ready).
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                // give autoAnswer a tick
                delay(30)
            }
            backend.attach(summary())

            // Drop the socket, then queue a prompt into the outbox.
            transport.drop()
            withTimeout(3_000) { backend.connection.first { it == ConnectionState.DISCONNECTED } }
            delay(20)

            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            backend.prompt("s1", "hello while down")
            assertTrue(collected.any { it.update is CodeUpdate.Upsert })
            val promptsBefore = transport.sent.count { it.contains("session/prompt") }
            assertEquals(0, promptsBefore)

            // Come back: should re-initialize, session/load with afterSeq, then flush outbox.
            transport.restore()
            withTimeout(5_000) {
                while (transport.sent.count { it.contains("session/prompt") } < 1) delay(10)
            }
            assertTrue(transport.sent.any { it.contains("session/load") })
            assertTrue(transport.sent.any { it.contains("session/prompt") && it.contains("hello while down") })
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test fun resumeLoadUsesAfterSeqFromAdapter() = runBlocking {
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        // Seed lastSeq as if we had already seen updates for s1.
        adapter.decode(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":42},
            "update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"x"}}}}"""
        )
        assertEquals(42L, adapter.lastSeq("s1"))

        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())
            val load = transport.sent.last { it.contains("session/load") }
            val meta = json.parseToJsonElement(load).jsonObject["params"]!!.jsonObject["_meta"]!!.jsonObject
            assertEquals(42L, meta["afterSeq"]!!.jsonPrimitive.longOrNull)

            // Disconnect + restore triggers another load with same afterSeq (unless new seqs arrived).
            transport.drop()
            delay(20)
            val loadsBefore = transport.sent.count { it.contains("session/load") }
            transport.restore()
            withTimeout(5_000) {
                while (transport.sent.count { it.contains("session/load") } <= loadsBefore) delay(10)
            }
            val load2 = transport.sent.last { it.contains("session/load") }
            val meta2 = json.parseToJsonElement(load2).jsonObject["params"]!!.jsonObject["_meta"]!!.jsonObject
            assertEquals(42L, meta2["afterSeq"]!!.jsonPrimitive.longOrNull)
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test fun backgroundFlagForwardsToTransport() {
        val transport = FakeTransport()
        val backend = BridgeBackend(host(), transport, AcpAdapter(), scope, Dispatchers.Unconfined)
        backend.setAppBackgrounded(true)
        assertTrue(transport.backgrounded)
        backend.setAppBackgrounded(false)
        assertFalse(transport.backgrounded)
        backend.close()
    }

    @Test fun listHarnessesParsesBridgeResult() = runBlocking {
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = scope.launch {
            val answered = HashSet<Long>()
            while (true) {
                for (frame in transport.sent.toList()) {
                    val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: continue
                    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                    if (id in answered) continue
                    val method = obj["method"]?.jsonPrimitive?.content ?: continue
                    val result = when (method) {
                        "initialize" -> """{"protocolVersion":1}"""
                        "bridge/listHarnesses" -> """{"harnesses":[
                            {"id":"opencode","name":"OpenCode","available":true,"models":["gpt-5"]},
                            {"id":"cursor-cli","name":"Cursor CLI","available":false}
                        ]}"""
                        else -> "{}"
                    }
                    answered += id
                    transport.deliver("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                }
                delay(5)
            }
        }
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            val list = backend.listHarnesses()
            assertEquals(2, list.size)
            assertEquals(HarnessInfo("opencode", "OpenCode", true, listOf("gpt-5")), list[0])
            assertEquals(HarnessKind.OPENCODE, list[0].kind)
            assertEquals(HarnessInfo("cursor-cli", "Cursor CLI", false), list[1])
            assertFalse(list[1].available)
            assertTrue(transport.sent.any { it.contains("bridge/listHarnesses") })
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test fun browseParsesDirectoryEntries() = runBlocking {
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = scope.launch {
            val answered = HashSet<Long>()
            while (true) {
                for (frame in transport.sent.toList()) {
                    val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: continue
                    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                    if (id in answered) continue
                    val method = obj["method"]?.jsonPrimitive?.content ?: continue
                    val result = when (method) {
                        "initialize" -> """{"protocolVersion":1}"""
                        "bridge/browse" -> """{"entries":[
                            {"name":"src","dir":true},
                            {"name":"README.md","dir":false}
                        ]}"""
                        else -> "{}"
                    }
                    answered += id
                    transport.deliver("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                }
                delay(5)
            }
        }
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            val entries = backend.browse("/home/me/code")
            assertEquals(
                listOf(BrowseEntry("src", true), BrowseEntry("README.md", false)),
                entries
            )
            val browseFrame = transport.sent.last { it.contains("bridge/browse") }
            val path = json.parseToJsonElement(browseFrame).jsonObject["params"]!!.jsonObject["path"]!!.jsonPrimitive.content
            assertEquals("/home/me/code", path)
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test fun gitStatusAndDiffParseBridgeResults() = runBlocking {
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = scope.launch {
            val answered = HashSet<Long>()
            while (true) {
                for (frame in transport.sent.toList()) {
                    val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: continue
                    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                    if (id in answered) continue
                    val method = obj["method"]?.jsonPrimitive?.content ?: continue
                    val result = when (method) {
                        "initialize" -> """{"protocolVersion":1}"""
                        "bridge/gitStatus" -> """{"branch":"main","ahead":1,"behind":0,"files":[
                            {"path":"a.kt","status":" M"},
                            {"path":"b.md","status":"??"}
                        ]}"""
                        "bridge/diff" -> """{"unified":"@@ -1 +1 @@\n-old\n+new\n"}"""
                        else -> "{}"
                    }
                    answered += id
                    transport.deliver("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                }
                delay(5)
            }
        }
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            val status = backend.gitStatus("sess-1")
            assertEquals(
                GitStatusResult(
                    "main", 1, 0,
                    listOf(GitFileStatus("a.kt", " M"), GitFileStatus("b.md", "??"))
                ),
                status
            )
            val statusFrame = transport.sent.last { it.contains("bridge/gitStatus") }
            assertEquals(
                "sess-1",
                json.parseToJsonElement(statusFrame).jsonObject["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content
            )
            val diff = backend.diff("sess-1", "a.kt")
            assertEquals("@@ -1 +1 @@\n-old\n+new\n", diff.unified)
            val diffFrame = transport.sent.last { it.contains("bridge/diff") }
            val params = json.parseToJsonElement(diffFrame).jsonObject["params"]!!.jsonObject
            assertEquals("sess-1", params["sessionId"]!!.jsonPrimitive.content)
            assertEquals("a.kt", params["path"]!!.jsonPrimitive.content)
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test fun cancelClearsQueuedOutboxAndEmitsTurnDone() = runBlocking {
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())

            transport.drop()
            withTimeout(3_000) { backend.connection.first { it == ConnectionState.DISCONNECTED } }
            delay(20)

            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            backend.prompt("s1", "should not flush after cancel")
            assertTrue(transport.keepAlive)
            assertEquals(0, transport.sent.count { it.contains("session/prompt") })

            backend.cancel("s1")
            withTimeout(3_000) {
                while (collected.none { it.update is CodeUpdate.TurnDone }) delay(5)
            }
            val done = collected.map { it.update }.filterIsInstance<CodeUpdate.TurnDone>().last()
            assertEquals("cancelled", done.stopReason)
            assertFalse("keepAlive should clear when outbox+running empty", transport.keepAlive)

            val promptsBefore = transport.sent.count { it.contains("session/prompt") }
            transport.restore()
            withTimeout(3_000) {
                while (transport.sent.count { it.contains("session/load") } < 2) delay(10)
                delay(120)
            }
            assertEquals(
                "cancelled outbox prompt must not be delivered on reconnect",
                promptsBefore,
                transport.sent.count { it.contains("session/prompt") }
            )
            assertFalse(transport.sent.any { it.contains("should not flush after cancel") })
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test fun flushOutboxRequeuesWhenPromptSendFails() = runBlocking {
        val transport = FakeTransport()
        transport.failPromptSendOnce = true
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())

            transport.drop()
            withTimeout(3_000) { backend.connection.first { it == ConnectionState.DISCONNECTED } }
            delay(20)
            backend.prompt("s1", "retry me")

            // First reconnect: prompt send fails once (item stays queued), keepAlive stays true.
            transport.restore()
            withTimeout(5_000) {
                while (transport.failPromptSendOnce) delay(10)
            }
            // Checked at the failing send itself: the backend's own retry may deliver right after,
            // so a sleep-then-count here raced it under a loaded run.
            assertEquals("nothing delivered before the failed send", 0, transport.promptsSentAtFailure)
            assertTrue(transport.keepAlive)
            assertEquals(
                "transport stays CONNECTED after queue-full send false",
                ConnectionState.CONNECTED,
                transport.state.value,
            )

            // R4: bounded flush retry while still CONNECTED — no drop required.
            withTimeout(5_000) {
                while (transport.sent.count { it.contains("session/prompt") } < 1) delay(10)
            }
            assertTrue(transport.sent.any { it.contains("session/prompt") && it.contains("retry me") })
            delay(100)
            assertEquals(
                "failed deliver must not consume the outbox item, nor send it twice",
                1,
                transport.sent.count { it.contains("session/prompt") }
            )
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test fun cancelAbortsInFlightDeliverAndDoesNotTreatAsDelivered() = runBlocking {
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())

            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            // Live turn: prompt stays pending (autoAnswer skips session/prompt).
            val promptJob = scope.launch { backend.prompt("s1", "in flight stop me") }
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("session/prompt") && it.contains("in flight stop me") }) {
                    delay(5)
                }
            }
            assertTrue(transport.keepAlive)

            backend.cancel("s1")
            withTimeout(3_000) {
                while (collected.none {
                    val u = it.update
                    u is CodeUpdate.TurnDone && u.stopReason == "cancelled"
                }) delay(5)
            }
            promptJob.join()

            // cancel must have completed the pending prompt deferred (Cancelled).
            assertTrue(
                "session/cancel should be sent",
                transport.sent.any { it.contains("session/cancel") }
            )
            assertFalse(
                "keepAlive clears after cancel aborts in-flight deliver",
                transport.keepAlive
            )

            // Late agent chunk after Stop must not revive keepAlive / running.
            transport.deliver(
                """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":99},
                "update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"late"}}}}"""
            )
            delay(40)
            assertFalse(
                "late agent chunk after Stop must stay suppressed",
                transport.keepAlive
            )
            assertFalse(
                collected.any {
                    val u = it.update
                    u is CodeUpdate.TextChunk && u.chunk.contains("late")
                }
            )

            // Reconnect must not flush a phantom delivered/dequeued item from the aborted turn.
            val promptsBefore = transport.sent.count { it.contains("session/prompt") }
            transport.drop()
            delay(20)
            transport.restore()
            withTimeout(3_000) {
                while (transport.sent.count { it.contains("session/load") } < 2) delay(10)
                delay(120)
            }
            assertEquals(
                "aborted in-flight prompt must not re-deliver on reconnect",
                promptsBefore,
                transport.sent.count { it.contains("session/prompt") }
            )
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test fun cancelDuringFlushBeforeSendDoesNotDequeueAsDelivered() = runBlocking {
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())

            transport.drop()
            withTimeout(3_000) { backend.connection.first { it == ConnectionState.DISCONNECTED } }
            delay(20)
            backend.prompt("s1", "flush then cancel")
            assertEquals(0, transport.sent.count { it.contains("session/prompt") })

            // Block the first prompt send so flush is mid-deliver before accept.
            transport.failPromptSendOnce = true
            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            transport.restore()
            withTimeout(5_000) {
                while (transport.failPromptSendOnce) delay(5)
                delay(30)
            }
            // Send failed → item still queued. Cancel should clear it without marking delivered.
            backend.cancel("s1")
            withTimeout(3_000) {
                while (collected.none {
                    val u = it.update
                    u is CodeUpdate.TurnDone && u.stopReason == "cancelled"
                }) delay(5)
            }

            val promptsBefore = transport.sent.count { it.contains("session/prompt") }
            transport.drop()
            delay(20)
            transport.restore()
            withTimeout(3_000) {
                while (transport.sent.count { it.contains("session/load") } < 3) delay(10)
                delay(150)
            }
            assertEquals(
                "cancel during failed flush must not deliver the prompt later",
                promptsBefore,
                transport.sent.count { it.contains("session/prompt") }
            )
            assertFalse(transport.sent.any { it.contains("flush then cancel") })
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun answerDoesNotEmitWhenSendFails() = runBlocking {
        // H2: failed transport.send must not emit ApprovalAnswered.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(5_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }
            transport.failNextNonPromptSend = true
            val threw = runCatching {
                backend.answer("s1", "42", io.github.stardomains3.oxproxion.code.ApprovalOption(
                    "allow", "Allow", io.github.stardomains3.oxproxion.code.ApprovalOption.Kind.ALLOW_ONCE
                ))
            }.exceptionOrNull()
            assertTrue("expected send failure", threw is IllegalStateException)
            delay(50)
            assertFalse(
                collected.any { it.update is CodeUpdate.ApprovalAnswered }
            )
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun answerRetryAfterSendFailureSucceeds() = runBlocking {
        // H2 follow-up: after a failed send, a later answer can still go out.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(5_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            val opt = io.github.stardomains3.oxproxion.code.ApprovalOption(
                "allow", "Allow", io.github.stardomains3.oxproxion.code.ApprovalOption.Kind.ALLOW_ONCE
            )
            transport.failNextNonPromptSend = true
            assertTrue(runCatching { backend.answer("s1", "77", opt) }.isFailure)
            val before = transport.sent.count { it.contains("\"id\":77") || it.contains("\"id\":\"77\"") }
            assertEquals(0, before)
            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }
            backend.answer("s1", "77", opt)
            delay(50)
            assertTrue(transport.sent.any { it.contains("\"id\":77") || it.contains("\"id\":\"77\"") })
            assertTrue(collected.any { it.update is CodeUpdate.ApprovalAnswered })
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun answerConnectsWhenCold() = runBlocking {
        // AWAY-01: answer() must ensureReady (connect + initialize) when the socket is down.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            assertEquals(ConnectionState.DISCONNECTED, backend.connection.value)
            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }
            val opt = io.github.stardomains3.oxproxion.code.ApprovalOption(
                "allow", "Allow", io.github.stardomains3.oxproxion.code.ApprovalOption.Kind.ALLOW_ONCE
            )
            backend.answer("s1", "42", opt)
            assertTrue(
                "cold answer must initialize first",
                transport.sent.any { it.contains("\"initialize\"") },
            )
            assertTrue(
                "permission reply must leave the device",
                transport.sent.any { it.contains("\"id\":42") || it.contains("\"id\":\"42\"") },
            )
            assertTrue(collected.any { it.update is CodeUpdate.ApprovalAnswered })
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun detachRemovesAttachedSoReconnectSkipsLoad() = runBlocking {
        // B2: forget → detach; reconnect must not session/load the forgotten id.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        adapter.decode(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":7},
            "update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"x"}}}}"""
        )
        assertEquals(7L, adapter.lastSeq("s1"))
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())
            val loadsAfterAttach = transport.sent.count { it.contains("session/load") }
            assertTrue(loadsAfterAttach >= 1)

            backend.detach("s1")
            assertEquals(null, adapter.lastSeq("s1"))

            transport.drop()
            delay(20)
            val loadsBefore = transport.sent.count { it.contains("session/load") }
            val initsBefore = transport.sent.count { it.contains("\"initialize\"") }
            transport.restore()
            // Wait for reconnect initialize to complete (ready + empty attached loop).
            withTimeout(5_000) {
                while (transport.sent.count { it.contains("\"initialize\"") } <= initsBefore) delay(10)
                delay(100)
            }
            assertEquals(
                "forgotten session must not be session/load-ed on reconnect",
                loadsBefore,
                transport.sent.count { it.contains("session/load") },
            )
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun cancelAfterDetachIsWireOnlyNoSuppressSeed() = runBlocking {
        // C1: forget → detach then cancel must not re-seed suppressAgent (load replay stays live).
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())
            backend.detach("s1")

            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            backend.cancel("s1")
            delay(40)
            assertTrue(
                "wire session/cancel still sent after detach",
                transport.sent.any { it.contains("session/cancel") },
            )
            assertFalse(
                "wire-only cancel must not emit local TurnDone",
                collected.any {
                    val u = it.update
                    u is CodeUpdate.TurnDone && u.stopReason == "cancelled"
                },
            )

            // Re-attach and ensure agent chunks are not swallowed by a leaked suppressAgent.
            backend.attach(summary())
            transport.deliver(
                """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":42},
                "update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"after-forget"}}}}"""
            )
            delay(40)
            assertTrue(
                "agent chunk after re-attach must not be suppressed",
                collected.any {
                    val u = it.update
                    u is CodeUpdate.TextChunk && u.chunk.contains("after-forget")
                },
            )
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun cancelAfterDetachMidTurnDoesNotReseedSuppressAgent() = runBlocking {
        // D1 / C1 mid-turn: detach completes in-flight deliver with Detached sync
        // (Unconfined ≈ Hub Main.immediate). Follow-up cancel must stay wire-only —
        // no suppressAgent re-seed — so re-attach agent chunks still pass.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())

            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            val promptJob = scope.launch { backend.prompt("s1", "in flight forget me") }
            withTimeout(3_000) {
                while (transport.sent.none {
                    it.contains("session/prompt") && it.contains("in flight forget me")
                }) delay(5)
            }

            backend.detach("s1")
            backend.cancel("s1")
            promptJob.join()
            delay(40)

            assertTrue(
                "wire session/cancel still sent after mid-turn detach",
                transport.sent.any { it.contains("session/cancel") },
            )
            assertFalse(
                "wire-only cancel must not emit local TurnDone after mid-turn detach",
                collected.any {
                    val u = it.update
                    u is CodeUpdate.TurnDone && u.stopReason == "cancelled"
                },
            )

            backend.attach(summary())
            transport.deliver(
                """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":43},
                "update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"after-mid-forget"}}}}"""
            )
            delay(40)
            assertTrue(
                "agent chunk after re-attach must not be swallowed by suppressAgent",
                collected.any {
                    val u = it.update
                    u is CodeUpdate.TextChunk && u.chunk.contains("after-mid-forget")
                },
            )
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }
    @Test
    fun flushOutboxContinuesAfterAbortedSibling() = runBlocking {
        // E1: cancel session A mid-flush must not orphan session B's queued prompt.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary("s1"))
            backend.attach(summary("s2"))

            transport.drop()
            withTimeout(3_000) { backend.connection.first { it == ConnectionState.DISCONNECTED } }
            delay(20)

            backend.prompt("s1", "prompt-a-abort-me")
            backend.prompt("s2", "prompt-b-must-flush")

            // Reconnect: flush starts s1 (autoAnswer leaves session/prompt pending).
            transport.restore()
            withTimeout(5_000) {
                while (transport.sent.none {
                    it.contains("session/prompt") && it.contains("prompt-a-abort-me")
                }) delay(5)
            }

            backend.cancel("s1")
            withTimeout(5_000) {
                while (transport.sent.none {
                    it.contains("session/prompt") && it.contains("prompt-b-must-flush")
                }) delay(10)
            }
            assertTrue(
                "sibling outbox prompt must still flush after A aborted",
                transport.sent.any {
                    it.contains("session/prompt") && it.contains("prompt-b-must-flush")
                },
            )
        } finally {
            answers.cancel()
            backend.close()
        }
    }


    @Test
    fun flushOutboxKeepsIdenticalRepromptAfterAbort() = runBlocking {
        // G1: after cancel removes peeked head, a re-queued identical prompt must not be
        // dropped by structural == on dequeue — referential === keeps it for flush.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary("s1"))
            transport.drop()
            withTimeout(3_000) { backend.connection.first { it == ConnectionState.DISCONNECTED } }
            delay(20)
            val text = "identical-reprompt-g1"
            backend.prompt("s1", text)
            transport.restore()
            withTimeout(5_000) {
                while (transport.sent.none {
                    it.contains("session/prompt") && it.contains(text)
                }) delay(5)
            }
            val promptsBeforeCancel = transport.sent.count {
                it.contains("session/prompt") && it.contains(text)
            }
            assertTrue(promptsBeforeCancel >= 1)
            backend.cancel("s1")
            // prompt() awaits the full turn — run it in the background like other tests.
            val promptJob = scope.launch { backend.prompt("s1", text) }
            withTimeout(5_000) {
                while (transport.sent.count {
                    it.contains("session/prompt") && it.contains(text)
                } < promptsBeforeCancel + 1) delay(10)
            }
            assertTrue(
                "identical re-prompt after cancel must still flush (G1 ===)",
                transport.sent.count {
                    it.contains("session/prompt") && it.contains(text)
                } >= promptsBeforeCancel + 1,
            )
            transport.replyToPending({ it == "session/prompt" }, """{"stopReason":"end_turn"}""")
            withTimeout(3_000) { promptJob.join() }
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun answerAllowsSameRequestIdAcrossSessions() = runBlocking {
        // AWAY-02: two sessions on one host may share numeric request id "1".
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(5_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            val opt = io.github.stardomains3.oxproxion.code.ApprovalOption(
                "allow", "Allow", io.github.stardomains3.oxproxion.code.ApprovalOption.Kind.ALLOW_ONCE
            )
            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            transport.holdNonPromptSend = release
            transport.nonPromptSendEntered = entered

            val first = async(Dispatchers.IO) { backend.answer("sA", "1", opt) }
            assertTrue("first answer must reach transport.send", entered.await(3, TimeUnit.SECONDS))
            // Bare requestId key would reject this; composite (sessionId, requestId) allows it.
            val second = async(Dispatchers.IO) { backend.answer("sB", "1", opt) }
            delay(80)
            release.countDown()
            first.await()
            second.await()
            delay(50)

            val answered = collected.filter { it.update is CodeUpdate.ApprovalAnswered }
            assertEquals("both sessions must emit ApprovalAnswered", 2, answered.size)
            assertTrue(answered.any { it.sessionId == "sA" })
            assertTrue(answered.any { it.sessionId == "sB" })
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun answerDedupesSameSessionRequestWhileInFlight() = runBlocking {
        // AWAY-02 / M3: same session+request still collapses while the first send is held.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(5_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            val opt = io.github.stardomains3.oxproxion.code.ApprovalOption(
                "allow", "Allow", io.github.stardomains3.oxproxion.code.ApprovalOption.Kind.ALLOW_ONCE
            )
            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            transport.holdNonPromptSend = release
            transport.nonPromptSendEntered = entered

            val first = async(Dispatchers.IO) { backend.answer("s1", "99", opt) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            backend.answer("s1", "99", opt) // must no-op while first is in flight
            release.countDown()
            first.await()
            delay(50)

            val replies = transport.sent.filter {
                it.contains("\"outcome\"") && (it.contains("\"id\":99") || it.contains("\"id\":\"99\""))
            }
            assertEquals(1, replies.size)
            assertEquals(
                1,
                collected.count { it.update is CodeUpdate.ApprovalAnswered },
            )
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun handshakeFailureClearsReadyAndReconnects() = runBlocking {
        // R2: initialize JSON-RPC error must not leave CONNECTED + !ready forever.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        var initCount = 0
        val answers = scope.launch {
            val answered = HashSet<Long>()
            while (true) {
                for (frame in transport.sent.toList()) {
                    val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: continue
                    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                    if (id in answered) continue
                    val method = obj["method"]?.jsonPrimitive?.content ?: continue
                    when (method) {
                        "initialize" -> {
                            initCount++
                            answered += id
                            if (initCount == 1) {
                                transport.deliver(
                                    """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"init blew up"}}"""
                                )
                            } else {
                                transport.deliver(
                                    """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":1}}"""
                                )
                            }
                        }
                        "session/load" -> {
                            answered += id
                            transport.deliver("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
                        }
                        "bridge/listSessions" -> {
                            answered += id
                            transport.deliver("""{"jsonrpc":"2.0","id":$id,"result":{"sessions":[]}}""")
                        }
                        else -> {
                            answered += id
                            transport.deliver("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
                        }
                    }
                }
                delay(5)
            }
        }
        try {
            backend.connect()
            withTimeout(5_000) {
                while (backend.lastError != "init blew up") delay(10)
            }
            assertEquals("init blew up", backend.lastError)
            // Dropped out of CONNECTED (close) and scheduled reconnect.
            withTimeout(5_000) {
                while (transport.connectCount < 2) delay(10)
            }
            assertTrue("must reconnect after handshake failure", transport.connectCount >= 2)
            // Second initialize succeeds → ready; listSessions should work.
            withTimeout(5_000) {
                backend.listSessions()
            }
            assertNull("handshake error cleared after successful ready", backend.lastError)
            assertTrue(
                transport.sent.count { it.contains("\"initialize\"") } >= 2,
            )
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun cancelSendFailureKeepsPendingWithoutFinalizing() = runBlocking {
        // R3: session/cancel send false must not emit TurnDone/suppress as accepted.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())

            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            val promptJob = scope.launch { backend.prompt("s1", "live turn cancel fail") }
            withTimeout(3_000) {
                while (transport.sent.none {
                    it.contains("session/prompt") && it.contains("live turn cancel fail")
                }) delay(5)
            }

            transport.failCancelSendOnce = true
            val cancelsBefore = transport.sent.count { it.contains("session/cancel") }
            backend.cancel("s1")
            promptJob.join()
            delay(40)

            assertEquals(
                "failed cancel must not queue session/cancel",
                cancelsBefore,
                transport.sent.count { it.contains("session/cancel") },
            )
            assertFalse(
                "must not finalize TurnDone(cancelled) when wire cancel was not accepted",
                collected.any {
                    val u = it.update
                    u is CodeUpdate.TurnDone && u.stopReason == "cancelled"
                },
            )
            assertTrue(
                "warning notice surfaces queue failure",
                collected.any {
                    val u = it.update
                    u is CodeUpdate.Upsert &&
                        u.event is io.github.stardomains3.oxproxion.code.CodeEvent.Notice &&
                        (u.event as io.github.stardomains3.oxproxion.code.CodeEvent.Notice)
                            .text.contains("did not reach host")
                },
            )
            assertTrue(
                "cancel-pending keeps keepAlive until wire cancel lands",
                transport.keepAlive,
            )

            // Late agent activity must still pass (suppress not finalized).
            transport.deliver(
                """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":50},
                "update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"still-running"}}}}"""
            )
            delay(40)
            assertTrue(
                "agent chunk must not be swallowed before cancel is accepted",
                collected.any {
                    val u = it.update
                    u is CodeUpdate.TextChunk && u.chunk.contains("still-running")
                },
            )
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun cancelPendingResendsAfterReconnectAndFinalizes() = runBlocking {
        // R3: after failed cancel, reconnect flush resends session/cancel then TurnDone.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())

            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            val promptJob = scope.launch { backend.prompt("s1", "resend cancel after drop") }
            withTimeout(3_000) {
                while (transport.sent.none {
                    it.contains("session/prompt") && it.contains("resend cancel after drop")
                }) delay(5)
            }

            transport.failCancelSendOnce = true
            backend.cancel("s1")
            promptJob.join()
            delay(30)
            assertFalse(
                collected.any {
                    val u = it.update
                    u is CodeUpdate.TurnDone && u.stopReason == "cancelled"
                },
            )

            val cancelsBefore = transport.sent.count { it.contains("session/cancel") }
            transport.drop()
            delay(20)
            transport.restore()
            withTimeout(5_000) {
                while (transport.sent.count { it.contains("session/cancel") } <= cancelsBefore) {
                    delay(10)
                }
                while (collected.none {
                    val u = it.update
                    u is CodeUpdate.TurnDone && u.stopReason == "cancelled"
                }) delay(10)
            }
            assertTrue(
                "session/cancel resent after reconnect",
                transport.sent.count { it.contains("session/cancel") } > cancelsBefore,
            )
            assertTrue(
                collected.any {
                    val u = it.update
                    u is CodeUpdate.TurnDone && u.stopReason == "cancelled"
                },
            )
            assertFalse(
                "keepAlive clears after cancel pending finalized",
                transport.keepAlive,
            )

            // Suppress now active — late chunk dropped.
            transport.deliver(
                """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":77},
                "update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"after-final"}}}}"""
            )
            delay(40)
            assertFalse(
                collected.any {
                    val u = it.update
                    u is CodeUpdate.TextChunk && u.chunk.contains("after-final")
                },
            )
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun cancelPendingRetriesWhileStillReady() = runBlocking {
        // R3: queue-full while CONNECTED — scheduled flush retries without needing drop.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())

            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            val promptJob = scope.launch { backend.prompt("s1", "retry cancel while ready") }
            withTimeout(3_000) {
                while (transport.sent.none {
                    it.contains("session/prompt") && it.contains("retry cancel while ready")
                }) delay(5)
            }

            transport.failCancelSendOnce = true
            val cancelsBefore = transport.sent.count { it.contains("session/cancel") }
            backend.cancel("s1")
            promptJob.join()

            withTimeout(5_000) {
                while (transport.sent.count { it.contains("session/cancel") } <= cancelsBefore) {
                    delay(20)
                }
                while (collected.none {
                    val u = it.update
                    u is CodeUpdate.TurnDone && u.stopReason == "cancelled"
                }) delay(10)
            }
            assertTrue(transport.sent.count { it.contains("session/cancel") } > cancelsBefore)
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }




    @Test
    fun outboxRetriesFlushWhenSendFailsWhileConnected() = runBlocking {
        // R4: live prompt path — send false while CONNECTED must keep item queued and
        // schedule flush retry (no reconnect event).
        val transport = FakeTransport()
        transport.failPromptSendOnce = true
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())

            backend.prompt("s1", "queue full stuck")
            withTimeout(3_000) {
                while (transport.failPromptSendOnce) delay(5)
                delay(20)
            }
            assertEquals(ConnectionState.CONNECTED, transport.state.value)
            assertEquals(
                "failed live send must not accept the prompt yet",
                0,
                transport.sent.count { it.contains("session/prompt") },
            )
            assertTrue(transport.keepAlive)

            withTimeout(5_000) {
                while (transport.sent.none {
                    it.contains("session/prompt") && it.contains("queue full stuck")
                }) delay(10)
            }
            assertEquals(ConnectionState.CONNECTED, transport.state.value)
        } finally {
            answers.cancel()
            backend.close()
        }
    }


    @Test
    fun failedSessionLoadRetriesAndSurfacesError() = runBlocking {
        // R5: after reconnect, session/load error must not be swallowed — surface error,
        // retry with backoff, and do not treat that session as fully resumed meanwhile.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        var loadCount = 0
        val answers = scope.launch {
            val answered = HashSet<Long>()
            while (true) {
                for (frame in transport.sent.toList()) {
                    val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: continue
                    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                    if (id in answered) continue
                    val method = obj["method"]?.jsonPrimitive?.content ?: continue
                    when (method) {
                        "initialize" -> {
                            answered += id
                            transport.deliver(
                                """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":1}}"""
                            )
                        }
                        "session/load" -> {
                            loadCount++
                            answered += id
                            if (loadCount == 1) {
                                // First attach load succeeds.
                                transport.deliver("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
                            } else if (loadCount == 2) {
                                // First reconnect load fails.
                                transport.deliver(
                                    """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"load blew up"}}"""
                                )
                            } else {
                                transport.deliver("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
                            }
                        }
                        "bridge/listSessions" -> {
                            answered += id
                            transport.deliver("""{"jsonrpc":"2.0","id":$id,"result":{"sessions":[]}}""")
                        }
                        else -> {
                            answered += id
                            transport.deliver("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
                        }
                    }
                }
                delay(5)
            }
        }
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())
            assertEquals(1, loadCount)

            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            transport.drop()
            delay(30)
            transport.restore()

            // Failed resume surfaces OFFLINE + Notice; global ready can still come up.
            withTimeout(5_000) {
                while (collected.none {
                    val u = it.update
                    u is CodeUpdate.SessionInfo && u.status == SessionStatus.OFFLINE
                }) delay(10)
            }
            assertTrue(
                "load failure notice must reach the session UI",
                collected.any {
                    val u = it.update
                    u is CodeUpdate.Upsert &&
                        u.event is CodeEvent.Notice &&
                        (u.event as CodeEvent.Notice).text.contains("load blew up")
                },
            )

            // Handshake still completed — listSessions works (ready true) despite lost resume.
            withTimeout(5_000) { backend.listSessions() }

            // Backoff retry eventually re-issues session/load and succeeds.
            withTimeout(8_000) {
                while (loadCount < 3) delay(10)
            }
            assertTrue("failed load must be retried", loadCount >= 3)

            // After successful retry, a prompt for the session should be deliverable.
            backend.prompt("s1", "after resume ok")
            withTimeout(5_000) {
                while (transport.sent.none {
                    it.contains("session/prompt") && it.contains("after resume ok")
                }) delay(10)
            }
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }



    @Test
    fun malformedFrameDoesNotKillReader() = runBlocking {
        // R6: a malformed inbound frame must not permanently stop the reader while
        // the socket stays CONNECTED — subsequent good frames must still decode.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())

            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            // Malformed: permission with object id (historically threw in decodePermission).
            transport.deliver(
                """{"jsonrpc":"2.0","id":{"bad":true},"method":"session/request_permission","params":{"sessionId":"s1","options":[]}}"""
            )
            delay(40)

            // Good follow-up frame must still be handled.
            transport.deliver(
                """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":99},
                "update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"still-alive"}}}}"""
            )
            withTimeout(3_000) {
                while (collected.none {
                    val u = it.update
                    u is CodeUpdate.TextChunk && u.chunk.contains("still-alive")
                }) delay(10)
            }
            assertTrue(
                collected.any {
                    val u = it.update
                    u is CodeUpdate.TextChunk && u.chunk.contains("still-alive")
                },
            )
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }



    @Test
    fun optimisticUserPromptDedupesBridgeEcho() = runBlocking {
        // R7: optimistic user:<timestamp> + bridge user_message_chunk user:<seq> must
        // collapse to one UserPrompt after reduce (especially visible after outbox replay).
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())

            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            // prompt() awaits the full turn — run it in the background like other tests.
            val promptJob = scope.launch { backend.prompt("s1", "hello dedupe") }
            withTimeout(3_000) {
                while (collected.none {
                    val u = it.update
                    u is CodeUpdate.Upsert &&
                        u.event is CodeEvent.UserPrompt &&
                        (u.event as CodeEvent.UserPrompt).text == "hello dedupe"
                }) delay(5)
            }

            // Bridge echo with a different key (seq-based).
            transport.deliver(
                """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":42},
                "update":{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"hello dedupe"}}}}"""
            )
            delay(50)

            var events = emptyList<CodeEvent>()
            for (su in collected.filter { it.sessionId == "s1" }) {
                events = TranscriptReducer.apply(events, su.update)
            }
            val users = events.filterIsInstance<CodeEvent.UserPrompt>()
            assertEquals(
                "optimistic + echo must leave a single user bubble",
                1,
                users.size,
            )
            assertEquals("hello dedupe", users.single().text)

            // Complete the in-flight prompt so the job can finish.
            transport.replyToPending({ it == "session/prompt" }, """{"stopReason":"end_turn"}""")
            withTimeout(3_000) { promptJob.join() }
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun optimisticUserPromptDedupesTrimmedEcho() = runBlocking {
        // The composer text is trimmed on the wire. The echo must still collapse onto the
        // optimistic bubble, not leave a second one.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())

            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }
            val promptJob = scope.launch { backend.prompt("s1", "  hello dedupe  ") }
            withTimeout(3_000) {
                while (collected.none {
                    val ev = ((it.update as? CodeUpdate.Upsert)?.event as? CodeEvent.UserPrompt)
                    ev?.text == "  hello dedupe  "
                }) delay(5)
            }
            transport.deliver(
                """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":42},
                "update":{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"hello dedupe"}}}}"""
            )
            delay(50)
            var events = emptyList<CodeEvent>()
            for (su in collected.filter { it.sessionId == "s1" }) {
                events = TranscriptReducer.apply(events, su.update)
            }
            val users = events.filterIsInstance<CodeEvent.UserPrompt>()
            assertEquals(1, users.size)
            assertEquals("hello dedupe", users.single().text)
            transport.replyToPending({ it == "session/prompt" }, """{"stopReason":"end_turn"}""")
            withTimeout(3_000) { promptJob.join() }
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun overlappingResumeFlushSendsPromptOnce() = runBlocking {
        // onSocketReady flushes after each resumed session, and a failed send also starts
        // the connected-queue retry. Both used to peek the same outbox head.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        val release = CountDownLatch(1)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary("s1"))
            backend.attach(summary("s2"))
            transport.drop()
            withTimeout(3_000) { backend.connection.first { it == ConnectionState.DISCONNECTED } }
            delay(20)
            backend.prompt("s1", "flush-once")

            transport.failPromptSendOnce = true
            transport.holdPromptSend = release
            transport.promptHoldEntered = CountDownLatch(1)
            transport.restore()

            assertTrue(
                "the live flush must reach send",
                transport.promptHoldEntered!!.await(5, TimeUnit.SECONDS),
            )
            // Retry backoff is at most 500 ms. A second flusher would enter the hold in that window.
            delay(900)
            assertEquals(
                "queued prompt must be sent by one flusher",
                1,
                transport.promptHoldEntries.get(),
            )
        } finally {
            release.countDown()
            answers.cancel()
            backend.close()
        }
    }

    @Test
    fun outboxReplayUserPromptDedupesBridgeEcho() = runBlocking {
        // R7: offline optimistic bubble + reconnect outbox deliver + bridge echo → one bubble.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope, Dispatchers.Unconfined)
        val answers = autoAnswer(transport, adapter)
        try {
            backend.connect()
            withTimeout(3_000) {
                while (transport.sent.none { it.contains("\"initialize\"") }) delay(5)
                delay(30)
            }
            backend.attach(summary())

            val collected = CopyOnWriteArrayList<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }

            transport.drop()
            val promptJob = scope.launch { backend.prompt("s1", "queued echo dedupe") }
            withTimeout(3_000) {
                while (collected.none {
                    val u = it.update
                    u is CodeUpdate.Upsert &&
                        u.event is CodeEvent.UserPrompt &&
                        (u.event as CodeEvent.UserPrompt).text == "queued echo dedupe"
                }) delay(5)
            }

            transport.restore()
            withTimeout(5_000) {
                while (transport.sent.none {
                    it.contains("session/prompt") && it.contains("queued echo dedupe")
                }) delay(10)
            }

            transport.deliver(
                """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":77},
                "update":{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"queued echo dedupe"}}}}"""
            )
            delay(50)

            var events = emptyList<CodeEvent>()
            for (su in collected.filter { it.sessionId == "s1" }) {
                events = TranscriptReducer.apply(events, su.update)
            }
            val users = events.filterIsInstance<CodeEvent.UserPrompt>()
            assertEquals(1, users.size)
            assertEquals("queued echo dedupe", users.single().text)

            transport.replyToPending({ it == "session/prompt" }, """{"stopReason":"end_turn"}""")
            withTimeout(3_000) { promptJob.join() }
            collectJob.cancel()
        } finally {
            answers.cancel()
            backend.close()
        }
    }


}
