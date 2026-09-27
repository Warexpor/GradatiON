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
import io.github.stardomains3.oxproxion.code.CodeUpdate
import io.github.stardomains3.oxproxion.code.ConnectionState
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.PermissionMode
import io.github.stardomains3.oxproxion.code.SessionUpdate
import io.github.stardomains3.oxproxion.code.TransportKind
import kotlinx.coroutines.CoroutineScope
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
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
        /** When true, the next non-prompt send fails once (approval answer / cancel). */
        var failNextNonPromptSend = false
        private val open = AtomicBoolean(false)

        override fun connect() {
            open.set(true)
            lastError = null
            _state.value = ConnectionState.CONNECTING
            _state.value = ConnectionState.CONNECTED
        }

        override fun send(frame: String): Boolean {
            if (!open.get() || _state.value != ConnectionState.CONNECTED) return false
            if (failPromptSendOnce && frame.contains("session/prompt")) {
                failPromptSendOnce = false
                lastError = "Not connected"
                return false
            }
            if (failNextNonPromptSend && !frame.contains("session/prompt")) {
                failNextNonPromptSend = false
                lastError = "Not connected"
                return false
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
        val backend = BridgeBackend(host(), transport, adapter, scope)
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

        val backend = BridgeBackend(host(), transport, adapter, scope)
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
        val backend = BridgeBackend(host(), transport, AcpAdapter(), scope)
        backend.setAppBackgrounded(true)
        assertTrue(transport.backgrounded)
        backend.setAppBackgrounded(false)
        assertFalse(transport.backgrounded)
        backend.close()
    }

    @Test fun listHarnessesParsesBridgeResult() = runBlocking {
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope)
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
        val backend = BridgeBackend(host(), transport, adapter, scope)
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
        val backend = BridgeBackend(host(), transport, adapter, scope)
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
        val backend = BridgeBackend(host(), transport, adapter, scope)
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
        val backend = BridgeBackend(host(), transport, adapter, scope)
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
                delay(60)
            }
            assertEquals(
                "failed deliver must not consume the outbox item",
                0,
                transport.sent.count { it.contains("session/prompt") }
            )
            assertTrue(transport.keepAlive)

            // Second reconnect (or continued ready): send succeeds and flushes.
            // ready may still be true; force another flush via drop+restore.
            transport.drop()
            delay(20)
            transport.restore()
            withTimeout(5_000) {
                while (transport.sent.count { it.contains("session/prompt") } < 1) delay(10)
            }
            assertTrue(transport.sent.any { it.contains("session/prompt") && it.contains("retry me") })
        } finally {
            answers.cancel()
            backend.close()
        }
    }

    @Test fun cancelAbortsInFlightDeliverAndDoesNotTreatAsDelivered() = runBlocking {
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        val backend = BridgeBackend(host(), transport, adapter, scope)
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
        val backend = BridgeBackend(host(), transport, adapter, scope)
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
        val backend = BridgeBackend(host(), transport, adapter, scope)
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
        val backend = BridgeBackend(host(), transport, adapter, scope)
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
    fun detachRemovesAttachedSoReconnectSkipsLoad() = runBlocking {
        // B2: forget → detach; reconnect must not session/load the forgotten id.
        val transport = FakeTransport()
        val adapter = AcpAdapter()
        adapter.decode(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":7},
            "update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"x"}}}}"""
        )
        assertEquals(7L, adapter.lastSeq("s1"))
        val backend = BridgeBackend(host(), transport, adapter, scope)
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
}
