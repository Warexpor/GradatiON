package io.github.stardomains3.oxproxion.code

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A transcript change for one session, as the hub receives it from any backend. */
data class SessionUpdate(val sessionId: String, val update: CodeUpdate)

/**
 * One connected host. The hub keeps one backend per host and routes everything through this
 * interface, so the UI never knows whether it is talking to a real bridge or the demo.
 */
interface CodeBackend {
    val host: CodeHost
    val connection: StateFlow<ConnectionState>
    val updates: SharedFlow<SessionUpdate>
    val lastError: String?

    fun connect()
    suspend fun listSessions(): List<CodeSessionSummary>
    suspend fun listWorkspaces(harness: HarnessKind): List<String>
    /** Creates the session and sends its first prompt. Returns once the session exists. */
    suspend fun startSession(request: NewSessionRequest): CodeSessionSummary
    /** Re-attaches to an existing session; its history arrives as [updates] (ACP session/load replay). */
    suspend fun attach(session: CodeSessionSummary)
    suspend fun prompt(sessionId: String, text: String)
    suspend fun answer(sessionId: String, requestId: String, option: ApprovalOption?)
    suspend fun cancel(sessionId: String)
    suspend fun setPermissionMode(sessionId: String, mode: PermissionMode)
    fun close()
}

/**
 * Talks to a GradatiON bridge through a [CodeTransport] and a [HarnessAdapter]: JSON-RPC ids,
 * request/response matching, and fan-out of session updates. Skeleton quality: the happy path is
 * wired; reconnect/resume, request timeouts per method and offline queueing are in the plan.
 */
class BridgeBackend(
    override val host: CodeHost,
    private val transport: CodeTransport,
    private val adapter: HarnessAdapter,
    private val scope: CoroutineScope
) : CodeBackend {

    override val connection: StateFlow<ConnectionState> get() = transport.state
    override val lastError: String? get() = transport.lastError
    private val _updates = MutableSharedFlow<SessionUpdate>(extraBufferCapacity = 256)
    override val updates: SharedFlow<SessionUpdate> = _updates

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement?>>()
    private var reader: Job? = null
    private var initialized = false

    override fun connect() {
        if (reader == null) {
            reader = scope.launch {
                transport.incoming.collect { frame ->
                    for (out in adapter.decode(frame)) when (out) {
                        is AdapterOutput.Update -> _updates.emit(SessionUpdate(out.sessionId, out.update))
                        is AdapterOutput.Result -> pending.remove(out.id)?.let { d ->
                            if (out.error != null) d.completeExceptionally(IllegalStateException(out.error))
                            else d.complete(out.result)
                        }
                        is AdapterOutput.Ignored -> Unit
                    }
                }
            }
        }
        transport.connect()
    }

    private suspend fun call(build: (Long) -> String, timeoutMs: Long = 15_000): JsonElement? {
        ensureReady()
        return rawCall(build, timeoutMs)
    }

    private suspend fun rawCall(build: (Long) -> String, timeoutMs: Long): JsonElement? {
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<JsonElement?>()
        pending[id] = d
        if (!transport.send(build(id))) {
            pending.remove(id)
            throw IllegalStateException(transport.lastError ?: "Not connected")
        }
        return try {
            withTimeout(timeoutMs) { d.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun ensureReady() {
        if (connection.value != ConnectionState.CONNECTED) {
            connect()
            withTimeout(10_000) { transport.state.first { it == ConnectionState.CONNECTED || it == ConnectionState.FAILED } }
            if (connection.value != ConnectionState.CONNECTED) throw IllegalStateException(lastError ?: "Can't reach ${host.name}")
        }
        if (!initialized) {
            rawCall({ adapter.initialize(it) }, 10_000)
            initialized = true
        }
    }

    override suspend fun listSessions(): List<CodeSessionSummary> {
        val result = call({ adapter.listSessions(it) }) as? JsonObject ?: return emptyList()
        val arr = result["sessions"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
            CodeSessionSummary(
                id = s("sessionId") ?: return@mapNotNull null,
                hostId = host.id,
                harness = HarnessKind.fromId(s("harness")),
                workspace = s("cwd") ?: "",
                title = s("title") ?: "Session",
                createdAt = l("createdAt"),
                updatedAt = l("updatedAt"),
                preview = s("preview") ?: "",
                branch = s("branch")
            )
        }
    }

    override suspend fun listWorkspaces(harness: HarnessKind): List<String> {
        val result = call({ adapter.listWorkspaces(it, harness) }) as? JsonObject ?: return emptyList()
        return (result["workspaces"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }

    override suspend fun startSession(request: NewSessionRequest): CodeSessionSummary {
        val result = call({ adapter.newSession(it, request) }) as? JsonObject
        val sid = (result?.get("sessionId") as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalStateException("The bridge did not return a session")
        val now = System.currentTimeMillis()
        val summary = CodeSessionSummary(
            id = sid, hostId = host.id, harness = request.harness, workspace = request.workspace,
            title = request.prompt.lineSequence().first().take(60), createdAt = now, updatedAt = now,
            permissionMode = request.permissionMode, model = request.model
        )
        scope.launch { runCatching { prompt(sid, request.prompt) } }
        return summary
    }

    override suspend fun attach(session: CodeSessionSummary) {
        call({ adapter.loadSession(it, session.id, session.workspace) }, timeoutMs = 60_000)
    }

    override suspend fun prompt(sessionId: String, text: String) {
        val now = System.currentTimeMillis()
        _updates.emit(SessionUpdate(sessionId, CodeUpdate.Upsert(CodeEvent.UserPrompt("user:$now", now, text))))
        try {
            // A prompt's response arrives when the whole turn ends, which can take many minutes.
            val result = call({ adapter.prompt(it, sessionId, text) }, timeoutMs = 6 * 60 * 60 * 1000L) as? JsonObject
            val stop = (result?.get("stopReason") as? JsonPrimitive)?.contentOrNull ?: "end_turn"
            (adapter as? AcpAdapter)?.endTurn(sessionId)
            _updates.emit(SessionUpdate(sessionId, CodeUpdate.TurnDone(stop)))
        } catch (e: Exception) {
            _updates.emit(SessionUpdate(sessionId, CodeUpdate.Upsert(
                CodeEvent.Notice("err:${System.currentTimeMillis()}", System.currentTimeMillis(), e.message ?: "Turn failed", NoticeLevel.ERROR)
            )))
            _updates.emit(SessionUpdate(sessionId, CodeUpdate.TurnDone("error")))
        }
    }

    override suspend fun answer(sessionId: String, requestId: String, option: ApprovalOption?) {
        transport.send(adapter.answerApproval(requestId, option?.id))
        _updates.emit(SessionUpdate(sessionId, CodeUpdate.ApprovalAnswered(requestId, option?.kind ?: ApprovalOption.Kind.REJECT_ONCE)))
    }

    override suspend fun cancel(sessionId: String) {
        transport.send(adapter.cancel(sessionId))
    }

    override suspend fun setPermissionMode(sessionId: String, mode: PermissionMode) {
        call({ adapter.setMode(it, sessionId, mode) })
    }

    override fun close() {
        reader?.cancel()
        reader = null
        initialized = false
        pending.values.forEach { it.cancel() }
        pending.clear()
        transport.close()
    }
}
