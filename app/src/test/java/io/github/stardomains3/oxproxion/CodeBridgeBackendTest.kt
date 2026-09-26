package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.AcpAdapter
import io.github.stardomains3.oxproxion.code.BridgeBackend
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
        private val open = AtomicBoolean(false)

        override fun connect() {
            open.set(true)
            lastError = null
            _state.value = ConnectionState.CONNECTING
            _state.value = ConnectionState.CONNECTED
        }

        override fun send(frame: String): Boolean {
            if (!open.get() || _state.value != ConnectionState.CONNECTED) return false
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
}
