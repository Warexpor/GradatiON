package io.github.stardomains3.oxproxion.code

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.ArrayDeque
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
    /** Installed / configured harnesses on this host (`bridge/listHarnesses`). */
    suspend fun listHarnesses(): List<HarnessInfo>
    /** Directory listing inside allowed roots (`bridge/browse`). */
    suspend fun browse(path: String): List<BrowseEntry>
    /** Working-tree status for a session workspace (`bridge/gitStatus`). */
    suspend fun gitStatus(sessionId: String): GitStatusResult
    /** Unified diff for one path in a session workspace (`bridge/diff`). */
    suspend fun diff(sessionId: String, path: String): GitDiffResult
    /** Creates the session and sends its first prompt. Returns once the session exists. */
    suspend fun startSession(request: NewSessionRequest): CodeSessionSummary
    /** Re-attaches to an existing session; its history arrives as [updates] (ACP session/load replay). */
    suspend fun attach(session: CodeSessionSummary)
    suspend fun prompt(sessionId: String, text: String, attachments: List<PromptAttachment> = emptyList())
    suspend fun answer(sessionId: String, requestId: String, option: ApprovalOption?)
    suspend fun cancel(sessionId: String)
    suspend fun setPermissionMode(sessionId: String, mode: PermissionMode)
    fun close()

    /** Pause transport reconnect while the app is backgrounded (no-op unless a bridge). */
    fun setAppBackgrounded(backgrounded: Boolean) {}

    /** Highest bridge `_meta.seq` seen for [sessionId], or null. Used when persisting resume cursors. */
    fun peekLastSeq(sessionId: String): Long? = null

    /** Seed a resume cursor after process death / Room load (no-op for demo). */
    fun rememberLastSeq(sessionId: String, seq: Long) {}
}

/**
 * Talks to a GradatiON bridge through a [CodeTransport] and a [HarnessAdapter]: JSON-RPC ids,
 * request/response matching, fan-out of session updates, reconnect resume via `session/load`
 * + `_meta.afterSeq`, per-method timeouts, and an outbox for prompts typed while offline.
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
    private val attached = ConcurrentHashMap<String, CodeSessionSummary>()
    private val runningSessions = ConcurrentHashMap.newKeySet<String>()
    private val outbox = ArrayDeque<OutboxPrompt>()
    private val outboxLock = Any()
    private val ready = MutableStateFlow(false)
    private var reader: Job? = null
    private var lifecycle: Job? = null
    private var initialized = false
    private var socketGeneration = 0
    /** Bumped on cancel so in-flight deliverPrompt/flush abort and do not dequeue. */
    private val deliverGeneration = ConcurrentHashMap<String, AtomicLong>()
    /** RPC id of the in-flight session/prompt, so cancel can complete it. */
    private val inFlightPromptId = ConcurrentHashMap<String, Long>()
    /** After Stop, drop late agent activity until the next intentional deliver. */
    private val suppressAgent = ConcurrentHashMap.newKeySet<String>()
    /** Serialize prompt + flush deliver per session so turns never overlap. */
    private val promptMutexes = ConcurrentHashMap<String, Mutex>()

    private data class OutboxPrompt(
        val sessionId: String,
        val text: String,
        val attachments: List<PromptAttachment> = emptyList(),
    )

    private fun promptMutex(sessionId: String): Mutex =
        promptMutexes.getOrPut(sessionId) { Mutex() }

    private fun deliverGen(sessionId: String): Long =
        deliverGeneration.getOrPut(sessionId) { AtomicLong(0L) }.get()

    private fun bumpDeliverGen(sessionId: String): Long =
        deliverGeneration.getOrPut(sessionId) { AtomicLong(0L) }.incrementAndGet()

    private fun isDeliverStale(sessionId: String, gen: Long): Boolean =
        deliverGen(sessionId) != gen

    private fun isSuppressedAgentActivity(update: CodeUpdate): Boolean = when (update) {
        is CodeUpdate.TextChunk, is CodeUpdate.ToolPatch, is CodeUpdate.TurnDone -> true
        is CodeUpdate.SessionInfo -> update.status == SessionStatus.RUNNING ||
            update.status == SessionStatus.NEEDS_APPROVAL
        is CodeUpdate.Upsert -> update.event is CodeEvent.Approval ||
            update.event is CodeEvent.ToolCall
        else -> false
    }

    override fun peekLastSeq(sessionId: String): Long? =
        (adapter as? AcpAdapter)?.lastSeq(sessionId)

    override fun rememberLastSeq(sessionId: String, seq: Long) {
        (adapter as? AcpAdapter)?.seedLastSeq(sessionId, seq)
    }

    override fun setAppBackgrounded(backgrounded: Boolean) {
        transport.setAppBackgrounded(backgrounded)
    }

    override fun connect() {
        ensureReader()
        ensureLifecycle()
        transport.connect()
    }

    private fun ensureReader() {
        if (reader != null) return
        reader = scope.launch {
            transport.incoming.collect { frame ->
                for (out in adapter.decode(frame)) when (out) {
                    is AdapterOutput.Update -> {
                        if (out.sessionId in suppressAgent && isSuppressedAgentActivity(out.update)) {
                            // Stop already ended the turn locally; ignore late bridge activity.
                            continue
                        }
                        if (out.seq != null) noteRunningFromUpdate(out)
                        _updates.emit(SessionUpdate(out.sessionId, out.update))
                    }
                    is AdapterOutput.Result -> pending.remove(out.id)?.let { d ->
                        if (out.error != null) d.completeExceptionally(IllegalStateException(out.error))
                        else d.complete(out.result)
                    }
                    is AdapterOutput.Ignored -> Unit
                }
            }
        }
    }

    private fun noteRunningFromUpdate(out: AdapterOutput.Update) {
        when (val u = out.update) {
            is CodeUpdate.TurnDone -> {
                runningSessions.remove(out.sessionId)
                refreshKeepAlive()
            }
            is CodeUpdate.SessionInfo -> when (u.status) {
                SessionStatus.RUNNING, SessionStatus.NEEDS_APPROVAL -> {
                    runningSessions.add(out.sessionId)
                    refreshKeepAlive()
                }
                SessionStatus.IDLE, SessionStatus.ERROR -> {
                    runningSessions.remove(out.sessionId)
                    refreshKeepAlive()
                }
                else -> Unit
            }
            else -> Unit
        }
    }

    private fun ensureLifecycle() {
        if (lifecycle != null) return
        lifecycle = scope.launch {
            var wasUp = false
            transport.state.collect { st ->
                when (st) {
                    ConnectionState.CONNECTED -> {
                        if (!wasUp) {
                            wasUp = true
                            launch { runCatching { onSocketReady() } }
                        }
                    }
                    ConnectionState.DISCONNECTED, ConnectionState.FAILED -> {
                        if (wasUp) {
                            wasUp = false
                            ready.value = false
                            initialized = false
                            socketGeneration++
                            failPending("Disconnected")
                        }
                    }
                    ConnectionState.CONNECTING -> Unit
                }
            }
        }
    }

    private suspend fun onSocketReady() {
        val gen = socketGeneration
        rawCall({ adapter.initialize(it) }, DEFAULT_TIMEOUT_MS)
        if (gen != socketGeneration) return
        initialized = true
        for (session in attached.values.toList()) {
            if (gen != socketGeneration) return
            session.lastSeq?.let { rememberLastSeq(session.id, it) }
            val after = peekLastSeq(session.id) ?: session.lastSeq
            runCatching {
                rawCall(
                    { adapter.loadSession(it, session.id, session.workspace, after) },
                    DEFAULT_TIMEOUT_MS
                )
            }
        }
        if (gen != socketGeneration) return
        ready.value = true
        flushOutbox()
    }

    private fun refreshKeepAlive() {
        val pendingOutbox = synchronized(outboxLock) { outbox.isNotEmpty() }
        transport.setKeepAliveForSession(runningSessions.isNotEmpty() || pendingOutbox)
    }

    private fun failPending(msg: String) {
        val snap = pending.values.toList()
        pending.clear()
        snap.forEach { it.completeExceptionally(CancellationException(msg)) }
    }

    private suspend fun call(build: (Long) -> String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): JsonElement? {
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
            if (timeoutMs <= 0L) d.await()
            else withTimeout(timeoutMs) { d.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun ensureReady() {
        if (connection.value != ConnectionState.CONNECTED) {
            connect()
        }
        withTimeout(30_000) { ready.first { it } }
        if (!ready.value) throw IllegalStateException(lastError ?: "Can't reach ${host.name}")
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
                permissionMode = PermissionMode.fromId(s("permissionMode") ?: s("mode")),
                preview = s("preview") ?: "",
                branch = s("branch"),
                lastSeq = (o["lastSeq"] as? JsonPrimitive)?.longOrNull
            )
        }
    }

    override suspend fun listWorkspaces(harness: HarnessKind): List<String> {
        val result = call({ adapter.listWorkspaces(it, harness) }) as? JsonObject ?: return emptyList()
        return (result["workspaces"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }

    override suspend fun listHarnesses(): List<HarnessInfo> {
        val result = call({ adapter.listHarnesses(it) }) as? JsonObject ?: return emptyList()
        val arr = result["harnesses"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: id
            val available = (o["available"] as? JsonPrimitive)?.booleanOrNull ?: false
            val models = (o["models"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }.orEmpty()
            HarnessInfo(id = id, name = name, available = available, models = models)
        }
    }

    override suspend fun browse(path: String): List<BrowseEntry> {
        val result = call({ adapter.browse(it, path) }) as? JsonObject ?: return emptyList()
        val arr = result["entries"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val dir = (o["dir"] as? JsonPrimitive)?.booleanOrNull ?: false
            BrowseEntry(name = name, dir = dir)
        }
    }

    override suspend fun gitStatus(sessionId: String): GitStatusResult {
        val result = call({ adapter.gitStatus(it, sessionId) })
        return GitBridgeJson.parseStatus(result)
    }

    override suspend fun diff(sessionId: String, path: String): GitDiffResult {
        val result = call({ adapter.diff(it, sessionId, path) })
        return GitBridgeJson.parseDiff(result)
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
        attached[sid] = summary
        scope.launch { runCatching { prompt(sid, request.prompt, request.attachments) } }
        return summary
    }

    override suspend fun attach(session: CodeSessionSummary) {
        attached[session.id] = session
        session.lastSeq?.let { rememberLastSeq(session.id, it) }
        ensureReady()
        val after = peekLastSeq(session.id) ?: session.lastSeq
        rawCall({ adapter.loadSession(it, session.id, session.workspace, after) }, DEFAULT_TIMEOUT_MS)
    }

    override suspend fun prompt(sessionId: String, text: String, attachments: List<PromptAttachment>) {
        val now = System.currentTimeMillis()
        val atts = attachments.take(CodePromptImages.MAX_COUNT)
        _updates.emit(
            SessionUpdate(
                sessionId,
                CodeUpdate.Upsert(
                    CodeEvent.UserPrompt("user:$now", now, text, attachmentCount = atts.size)
                )
            )
        )
        promptMutex(sessionId).withLock {
            if (!ready.value || connection.value != ConnectionState.CONNECTED) {
                synchronized(outboxLock) { outbox.addLast(OutboxPrompt(sessionId, text, atts)) }
                runningSessions.add(sessionId)
                refreshKeepAlive()
                if (connection.value != ConnectionState.CONNECTING &&
                    connection.value != ConnectionState.CONNECTED
                ) {
                    connect()
                }
                return
            }
            if (!deliverPrompt(sessionId, text, atts)) {
                // Send failed before the bridge accepted — queue for reconnect flush.
                synchronized(outboxLock) { outbox.addLast(OutboxPrompt(sessionId, text, atts)) }
                runningSessions.add(sessionId)
                refreshKeepAlive()
                if (connection.value != ConnectionState.CONNECTING &&
                    connection.value != ConnectionState.CONNECTED
                ) {
                    connect()
                }
            }
        }
    }

    private suspend fun flushOutbox() {
        while (true) {
            val next = synchronized(outboxLock) {
                if (outbox.isEmpty()) null else outbox.first()
            } ?: break
            refreshKeepAlive()
            val delivered = runCatching {
                promptMutex(next.sessionId).withLock {
                    deliverPrompt(next.sessionId, next.text, next.attachments)
                }
            }.getOrDefault(false)
            if (!delivered) break
            synchronized(outboxLock) {
                if (outbox.isNotEmpty() && outbox.first() == next) outbox.removeFirst()
            }
        }
        refreshKeepAlive()
    }

    /**
     * @return true if the prompt was accepted (turn finished, terminal error, or in-flight after
     * send); false if deliver failed before accept and the caller should keep/requeue it.
     * User [cancel] bumps the deliver generation and completes the pending prompt deferred so
     * this returns false without treating a never-sent prompt as delivered.
     */
    private suspend fun deliverPrompt(
        sessionId: String,
        text: String,
        attachments: List<PromptAttachment> = emptyList(),
    ): Boolean {
        val gen = deliverGen(sessionId)
        suppressAgent.remove(sessionId)
        runningSessions.add(sessionId)
        refreshKeepAlive()
        if (isDeliverStale(sessionId, gen)) {
            runningSessions.remove(sessionId)
            refreshKeepAlive()
            return false
        }
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        inFlightPromptId[sessionId] = id
        var sendCompleted = false
        try {
            if (isDeliverStale(sessionId, gen)) return false
            val frame = adapter.prompt(id, sessionId, text, attachments)
            if (!transport.send(frame)) {
                throw IllegalStateException(transport.lastError ?: "Not connected")
            }
            sendCompleted = true
            // Prompt has no timeout: the turn can run for a long time.
            val result = deferred.await() as? JsonObject
            if (isDeliverStale(sessionId, gen)) {
                // Stop won the race after send; cancel already emitted TurnDone.
                refreshKeepAlive()
                return false
            }
            val stop = (result?.get("stopReason") as? JsonPrimitive)?.contentOrNull ?: "end_turn"
            (adapter as? AcpAdapter)?.endTurn(sessionId)
            _updates.emit(SessionUpdate(sessionId, CodeUpdate.TurnDone(stop)))
            runningSessions.remove(sessionId)
            refreshKeepAlive()
            return true
        } catch (e: CancellationException) {
            // User Stop: never dequeue as delivered (especially if send never completed).
            if (isDeliverStale(sessionId, gen) || e.message == "Cancelled") {
                refreshKeepAlive()
                return false
            }
            // Socket dropped mid-turn; keep running so reconnect stays alive. session/load resumes.
            // Treat as accepted for outbox only when the frame was already on the wire.
            refreshKeepAlive()
            return sendCompleted
        } catch (e: Exception) {
            val notAccepted = !sendCompleted || connection.value != ConnectionState.CONNECTED ||
                (e is IllegalStateException && (
                    e.message == "Not connected" ||
                        e.message?.startsWith("Can't reach") == true
                    ))
            if (notAccepted || isDeliverStale(sessionId, gen)) {
                refreshKeepAlive()
                return false
            }
            _updates.emit(
                SessionUpdate(
                    sessionId,
                    CodeUpdate.Upsert(
                        CodeEvent.Notice(
                            "err:${System.currentTimeMillis()}",
                            System.currentTimeMillis(),
                            e.message ?: "Turn failed",
                            NoticeLevel.ERROR
                        )
                    )
                )
            )
            _updates.emit(SessionUpdate(sessionId, CodeUpdate.TurnDone("error")))
            runningSessions.remove(sessionId)
            refreshKeepAlive()
            return true
        } finally {
            pending.remove(id)
            inFlightPromptId.remove(sessionId, id)
        }
    }

    override suspend fun answer(sessionId: String, requestId: String, option: ApprovalOption?) {
        transport.send(adapter.answerApproval(requestId, option?.id))
        _updates.emit(
            SessionUpdate(
                sessionId,
                CodeUpdate.ApprovalAnswered(requestId, option?.kind ?: ApprovalOption.Kind.REJECT_ONCE)
            )
        )
    }

    override suspend fun cancel(sessionId: String) {
        // Abort in-flight deliverPrompt (flush or live turn) and drop queued prompts.
        bumpDeliverGen(sessionId)
        suppressAgent.add(sessionId)
        inFlightPromptId.remove(sessionId)?.let { rpcId ->
            pending.remove(rpcId)?.completeExceptionally(CancellationException("Cancelled"))
        }
        synchronized(outboxLock) { outbox.removeAll { it.sessionId == sessionId } }
        runningSessions.remove(sessionId)
        refreshKeepAlive()
        runCatching { transport.send(adapter.cancel(sessionId)) }
        _updates.emit(
            SessionUpdate(
                sessionId,
                CodeUpdate.Upsert(
                    CodeEvent.Notice(
                        "cancel:${System.currentTimeMillis()}",
                        System.currentTimeMillis(),
                        "Stopped",
                        NoticeLevel.WARNING
                    )
                )
            )
        )
        _updates.emit(SessionUpdate(sessionId, CodeUpdate.TurnDone("cancelled")))
    }

    override suspend fun setPermissionMode(sessionId: String, mode: PermissionMode) {
        call({ adapter.setMode(it, sessionId, mode) })
    }

    override fun close() {
        lifecycle?.cancel()
        lifecycle = null
        reader?.cancel()
        reader = null
        ready.value = false
        initialized = false
        attached.clear()
        runningSessions.clear()
        deliverGeneration.clear()
        inFlightPromptId.clear()
        suppressAgent.clear()
        promptMutexes.clear()
        synchronized(outboxLock) { outbox.clear() }
        failPending("Closed")
        transport.close()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
