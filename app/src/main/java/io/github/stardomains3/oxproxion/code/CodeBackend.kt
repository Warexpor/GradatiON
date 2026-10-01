package io.github.stardomains3.oxproxion.code

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import io.github.stardomains3.oxproxion.R
import kotlin.random.Random
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
    /**
     * Drop local attach bookkeeping for [sessionId] (B2). Reconnect must not `session/load`
     * a forgotten session. Default no-op (demo).
     */
    fun detach(sessionId: String) {}
    fun close()

    /** Pause transport reconnect while the app is backgrounded (no-op unless a bridge). */
    fun setAppBackgrounded(backgrounded: Boolean) {}

    /** Highest bridge `_meta.seq` seen for [sessionId], or null. Used when persisting resume cursors. */
    fun peekLastSeq(sessionId: String): Long? = null

    /** Seed a resume cursor after process death / Room load (no-op for demo). */
    fun rememberLastSeq(sessionId: String, seq: Long) {}

    /**
     * Drop the resume cursor so the next [attach] replays the whole history. For a session whose
     * transcript is not on the phone (transcripts are memory-only, so after a restart it is not).
     */
    fun forgetLastSeq(sessionId: String) {}

    /**
     * Bridge / server version from the last successful ACP `initialize` handshake
     * (`_meta.bridge.version` or `serverInfo.version`). Null when unknown / demo / never connected.
     */
    fun peekBridgeVersion(): String? = null
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
    private val scope: CoroutineScope,
    /**
     * Inbound frames are parsed (and diffs computed) here, one at a time and in order, so a big
     * replay never runs on the hub's Main scope. Tests pass Unconfined to stay synchronous.
     */
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
) : CodeBackend {

    override val connection: StateFlow<ConnectionState> get() = transport.state
    /** Handshake failure message when transport stays up briefly then is dropped (R2). */
    @Volatile private var handshakeError: String? = null
    private var handshakeFailCount = 0
    private var handshakeRetry: Job? = null
    /** Mirrors the transport's policy: no reconnect while backgrounded unless a turn needs the link. */
    @Volatile private var appBackgrounded = false
    /** A handshake retry came due while backgrounded; foregrounding picks it up. */
    @Volatile private var handshakeRetryDeferred = false
    /** R4: bounded flush retry when send fails while still CONNECTED (queue full). */
    private var outboxFlushRetry: Job? = null
    private var outboxFlushFailCount = 0
    override val lastError: String? get() = handshakeError ?: transport.lastError
    private val _updates = MutableSharedFlow<SessionUpdate>(extraBufferCapacity = 256)
    override val updates: SharedFlow<SessionUpdate> = _updates

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement?>>()
    private val attached = ConcurrentHashMap<String, CodeSessionSummary>()
    /**
     * Attached sessions whose reconnect `session/load` has not finished. [ready] no longer waits
     * for them (each can take [DEFAULT_TIMEOUT_MS]); prompts for these ids queue until the replay
     * is done so a turn never overtakes its own history.
     */
    private val resuming = ConcurrentHashMap.newKeySet<String>()
    /** Seq-gap reloads per session on this connection; capped so a bridge with odd seqs can't loop. */
    private val gapReloads = ConcurrentHashMap<String, Int>()
    private val runningSessions = ConcurrentHashMap.newKeySet<String>()
    private val outbox = ArrayDeque<OutboxPrompt>()
    private val outboxLock = Any()
    /**
     * One flusher at a time. [onSocketReady] and the connected-queue retry both call
     * [flushOutbox]; without this they can peek the same head and deliver it twice.
     */
    private val outboxFlushMutex = Mutex()
    private val ready = MutableStateFlow(false)
    private var reader: Job? = null
    private var lifecycle: Job? = null
    private var initialized = false
    private var socketGeneration = 0
    /** From initialize `_meta.bridge.version` / `serverInfo.version`; kept across reconnect until close. */
    @Volatile private var bridgeVersion: String? = null
    /** Bumped on cancel so in-flight deliverPrompt/flush abort and do not dequeue. */
    private val deliverGeneration = ConcurrentHashMap<String, AtomicLong>()
    /** RPC id of the in-flight session/prompt, so cancel can complete it. */
    private val inFlightPromptId = ConcurrentHashMap<String, Long>()
    /** After Stop, drop late agent activity until the next intentional deliver. */
    private val suppressAgent = ConcurrentHashMap.newKeySet<String>()
    /** Serialize prompt + flush deliver per session so turns never overlap. */
    private val promptMutexes = ConcurrentHashMap<String, Mutex>()
    /** In-flight permission answers (M3 / AWAY-02); key = sessionId + requestId. */
    private val answering = ConcurrentHashMap.newKeySet<String>()
    /**
     * R3: sessions whose session/cancel failed to queue (socket dropping / OkHttp full).
     * Value = whether a successful resend should finalize suppress/TurnDone.
     * Resent after ready/reconnect (and short ready retry); do not finalize until send succeeds.
     */
    private val cancelPending = ConcurrentHashMap<String, Boolean>()
    private var cancelFlushJob: Job? = null
    /**
     * R5: attached sessions whose reconnect `session/load` failed. Global [ready] may still
     * be true (handshake ok); these sessions stay not-fully-resumed until load succeeds.
     * Value = last error message for UI.
     */
    private val loadFailed = ConcurrentHashMap<String, String>()
    private var sessionLoadRetry: Job? = null
    private var sessionLoadFailCount = 0
    /**
     * R7: optimistic local UserPrompt keys awaiting a bridge `user_message_chunk` echo.
     * Remap the echo onto the local key so TranscriptReducer upserts one bubble.
     */
    private data class PendingUserPrompt(
        val key: String,
        val text: String,
        val attachmentCount: Int,
    )
    private val pendingUserPrompts = ConcurrentHashMap<String, ArrayDeque<PendingUserPrompt>>()
    private val pendingUserLock = Any()

    private data class OutboxPrompt(
        val sessionId: String,
        val text: String,
        val attachments: List<PromptAttachment> = emptyList(),
    )

    /** Outcome of [deliverPrompt]: aborts must not outbox-requeue. */
    private enum class DeliverResult {
        /** Turn finished, terminal error, or accepted on the wire (incl. disconnect after send). */
        Done,
        /** Failed before accept — caller may outbox-requeue for reconnect flush. */
        Retry,
        /** Cancelled / Detached / deliver-stale — do not requeue. */
        Aborted,
    }

    private fun promptMutex(sessionId: String): Mutex =
        promptMutexes.getOrPut(sessionId) { Mutex() }

    private fun deliverGen(sessionId: String): Long =
        deliverGeneration.getOrPut(sessionId) { AtomicLong(0L) }.get()

    private fun bumpDeliverGen(sessionId: String): Long =
        deliverGeneration.getOrPut(sessionId) { AtomicLong(0L) }.incrementAndGet()

    private fun isDeliverStale(sessionId: String, gen: Long): Boolean =
        deliverGen(sessionId) != gen

    private fun isSuppressedAgentActivity(update: CodeUpdate): Boolean = when (update) {
        is CodeUpdate.TextChunk, is CodeUpdate.ImageChunk, is CodeUpdate.ToolPatch, is CodeUpdate.TurnDone -> true
        is CodeUpdate.SessionInfo -> update.status == SessionStatus.RUNNING ||
            update.status == SessionStatus.NEEDS_APPROVAL
        is CodeUpdate.Upsert -> update.event is CodeEvent.Approval ||
            update.event is CodeEvent.ToolCall
        else -> false
    }

    private fun rememberPendingUser(sessionId: String, key: String, text: String, attachmentCount: Int) {
        synchronized(pendingUserLock) {
            pendingUserPrompts.getOrPut(sessionId) { ArrayDeque() }
                .addLast(PendingUserPrompt(key, text.trim(), attachmentCount))
        }
    }

    private fun takePendingUser(sessionId: String, text: String): PendingUserPrompt? {
        // The wire prompt is trimmed (AcpAdapter.prompt). Match that, so a leading or
        // trailing space on the composer does not leave a second bubble when the echo arrives.
        val wanted = text.trim()
        synchronized(pendingUserLock) {
            val q = pendingUserPrompts[sessionId] ?: return null
            val it = q.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.text == wanted) {
                    it.remove()
                    if (q.isEmpty()) pendingUserPrompts.remove(sessionId)
                    return p
                }
            }
            return null
        }
    }

    private fun clearPendingUser(sessionId: String) {
        synchronized(pendingUserLock) { pendingUserPrompts.remove(sessionId) }
    }

    /**
     * R7: bridge `user_message_chunk` uses `user:<seq>` while the optimistic local bubble is
     * `user:<timestamp>`. Reuse the local key (and attachment count) so the reducer upserts
     * one message instead of leaving duplicates after outbox replay.
     */
    private fun remapUserPromptEcho(out: AdapterOutput.Update): AdapterOutput.Update {
        val upsert = out.update as? CodeUpdate.Upsert ?: return out
        val ev = upsert.event as? CodeEvent.UserPrompt ?: return out
        val pending = takePendingUser(out.sessionId, ev.text) ?: return out
        val merged = ev.copy(
            key = pending.key,
            attachmentCount = maxOf(pending.attachmentCount, ev.attachmentCount),
        )
        return out.copy(update = CodeUpdate.Upsert(merged))
    }

    override fun peekLastSeq(sessionId: String): Long? =
        (adapter as? AcpAdapter)?.lastSeq(sessionId)

    override fun rememberLastSeq(sessionId: String, seq: Long) {
        (adapter as? AcpAdapter)?.seedLastSeq(sessionId, seq)
    }

    override fun forgetLastSeq(sessionId: String) {
        (adapter as? AcpAdapter)?.clearLastSeq(sessionId)
    }

    override fun setAppBackgrounded(backgrounded: Boolean) {
        appBackgrounded = backgrounded
        transport.setAppBackgrounded(backgrounded)
        if (!backgrounded && handshakeRetryDeferred) {
            handshakeRetryDeferred = false
            if (connection.value == ConnectionState.DISCONNECTED || connection.value == ConnectionState.FAILED) {
                transport.connect()
            }
        }
    }

    /** Same rule as the transport: a backgrounded app keeps the link only while a turn needs it. */
    private fun reconnectAllowed(): Boolean =
        !appBackgrounded || runningSessions.isNotEmpty() || cancelPending.isNotEmpty() ||
            synchronized(outboxLock) { outbox.isNotEmpty() }

    override fun connect() {
        // A deliberate connect (screen open, tap on the banner) starts the handshake budget over.
        handshakeFailCount = 0
        handshakeRetryDeferred = false
        ensureReader()
        ensureLifecycle()
        transport.connect()
    }

    private fun ensureReader() {
        if (reader != null) return
        reader = scope.launch(decodeDispatcher) {
            // R6: supervise the collector — one bad frame (or unexpected decode throw)
            // must not leave a CONNECTED dead pipe with no inbound handling.
            while (true) {
                try {
                    transport.incoming.collect { frame ->
                        val decoded = runCatching { adapter.decode(frame) }.getOrElse {
                            return@collect
                        }
                        for (out in decoded) when (out) {
                            is AdapterOutput.Gap -> reloadAfterGap(out.sessionId, out.afterSeq)
                            is AdapterOutput.Update -> {
                                val remapped = remapUserPromptEcho(out)
                                if (remapped.sessionId in suppressAgent &&
                                    isSuppressedAgentActivity(remapped.update)
                                ) {
                                    // Stop already ended the turn locally; ignore late bridge activity.
                                    continue
                                }
                                if (remapped.seq != null) noteRunningFromUpdate(remapped)
                                _updates.emit(SessionUpdate(remapped.sessionId, remapped.update))
                            }
                            is AdapterOutput.Result -> pending.remove(out.id)?.let { d ->
                                if (out.error != null) d.completeExceptionally(IllegalStateException(out.error))
                                else d.complete(out.result)
                            }
                            is AdapterOutput.Ignored -> Unit
                        }
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    delay(50)
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
                            launch {
                                try {
                                    onSocketReady()
                                } catch (t: TimeoutCancellationException) {
                                    // initialize/load timed out — not a job cancel.
                                    failHandshake(t)
                                } catch (t: CancellationException) {
                                    throw t
                                } catch (t: Throwable) {
                                    failHandshake(t)
                                }
                            }
                        }
                    }
                    ConnectionState.DISCONNECTED, ConnectionState.FAILED -> {
                        if (wasUp) {
                            wasUp = false
                            ready.value = false
                            initialized = false
                            socketGeneration++
                            outboxFlushRetry?.cancel()
                            outboxFlushRetry = null
                            sessionLoadRetry?.cancel()
                            sessionLoadRetry = null
                            loadFailed.clear()
                            resuming.clear()
                            gapReloads.clear()
                            failPending("Disconnected")
                        }
                    }
                    ConnectionState.CONNECTING -> Unit
                }
            }
        }
    }

    /**
     * R2: initialize/handshake failure must not leave the transport CONNECTED while
     * [ready] stays false (operations would wait up to 30s). Clear readiness, surface
     * the error, drop the socket, and schedule a backoff reconnect.
     */
    private fun failHandshake(cause: Throwable) {
        ready.value = false
        initialized = false
        socketGeneration++
        val msg = cause.message?.takeIf { it.isNotBlank() } ?: CodeErrors.HANDSHAKE_FAILED
        handshakeError = msg
        failPending(msg)
        handshakeRetry?.cancel()
        transport.close()
        val attempt = handshakeFailCount++
        // Give up after a few tries; a tap on the banner (or opening the screen) connects again.
        if (attempt >= MAX_HANDSHAKE_RETRIES) return
        handshakeRetry = scope.launch {
            delay(ReconnectBackoff.delayMs(attempt.coerceAtMost(8), Random.nextDouble()))
            if (lifecycle == null) return@launch
            if (!reconnectAllowed()) {
                // Backgrounded with nothing running: stay quiet until the app is back.
                handshakeRetryDeferred = true
                return@launch
            }
            if (connection.value == ConnectionState.DISCONNECTED ||
                connection.value == ConnectionState.FAILED
            ) {
                transport.connect()
            }
        }
    }

    override fun peekBridgeVersion(): String? = bridgeVersion

    private suspend fun onSocketReady() {
        val gen = socketGeneration
        val initResult = rawCall({ adapter.initialize(it) }, DEFAULT_TIMEOUT_MS) as? JsonObject
        if (gen != socketGeneration) return
        CodeMachineDetail.parseBridgeVersion(initResult)?.let { bridgeVersion = it }
        initialized = true
        handshakeFailCount = 0
        handshakeError = null
        val toResume = attached.values.toList()
        // Flag the replays before ready flips, so a prompt that sees ready also sees them pending.
        toResume.forEach { resuming.add(it.id) }
        ready.value = true
        outboxFlushFailCount = 0
        sessionLoadFailCount = 0
        flushCancelPending()
        flushOutbox()
        // History replays run behind ready (each can take a while); running sessions go first.
        for (session in toResume.sortedByDescending { it.id in runningSessions }) {
            if (gen != socketGeneration) return
            if (attached.containsKey(session.id)) loadAttachedSession(session)
            resuming.remove(session.id)
            flushOutbox()
        }
        if (gen != socketGeneration) return
        if (loadFailed.isNotEmpty()) scheduleSessionLoadRetry()
    }

    /**
     * A seq jump means the transport dropped frames for [sessionId]. Replay from [afterSeq]
     * (the adapter drops repeats). Capped per connection: a bridge whose seqs are not
     * consecutive must not turn this into a reload loop.
     */
    private fun reloadAfterGap(sessionId: String, afterSeq: Long) {
        val session = attached[sessionId]
        val tries = gapReloads.merge(sessionId, 1) { a, b -> a + b } ?: 1 // merge never returns null here
        if (session == null || tries > MAX_GAP_RELOADS || !ready.value) {
            (adapter as? AcpAdapter)?.endGap(sessionId)
            return
        }
        scope.launch {
            try {
                rawCall({ adapter.loadSession(it, session.id, session.workspace, afterSeq) }, DEFAULT_TIMEOUT_MS)
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
            } catch (_: Exception) {
                // The next gap (or the next reconnect) tries again; nothing to show.
            } finally {
                (adapter as? AcpAdapter)?.endGap(sessionId)
            }
        }
    }

    /**
     * R5: `session/load` for one attached session. Success clears [loadFailed]; failure
     * records the error, surfaces OFFLINE + Notice for the session UI, and returns false
     * so the caller can schedule a backoff retry. Does not throw (reconnect must not
     * treat one bad resume as a full handshake failure).
     */
    private suspend fun loadAttachedSession(session: CodeSessionSummary): Boolean {
        session.lastSeq?.let { rememberLastSeq(session.id, it) }
        val after = peekLastSeq(session.id) ?: session.lastSeq
        return try {
            rawCall(
                { adapter.loadSession(it, session.id, session.workspace, after) },
                DEFAULT_TIMEOUT_MS
            )
            loadFailed.remove(session.id)
            true
        } catch (e: Exception) {
            // A timed-out load is a failed resume, not a cancelled job; real cancels (socket drop,
            // close) still unwind.
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            val msg = e.message?.takeIf { it.isNotBlank() } ?: "session/load failed"
            loadFailed[session.id] = msg
            _updates.emit(
                SessionUpdate(session.id, CodeUpdate.SessionInfo(status = SessionStatus.OFFLINE))
            )
            _updates.emit(
                SessionUpdate(
                    session.id,
                    CodeUpdate.Upsert(
                        CodeEvent.Notice(
                            "load-fail:${session.id}",
                            System.currentTimeMillis(),
                            "Resume failed, retrying: $msg",
                            NoticeLevel.ERROR,
                            textRes = R.string.code_notice_resume_failed,
                            args = listOf(msg),
                        )
                    )
                )
            )
            false
        }
    }

    /**
     * R5: retry failed reconnect loads with backoff while still CONNECTED/ready.
     * On full recovery, flush outbox so deferred prompts for those sessions can leave.
     */
    private fun scheduleSessionLoadRetry() {
        if (sessionLoadRetry?.isActive == true) return
        if (loadFailed.isEmpty()) return
        sessionLoadRetry = scope.launch {
            val self = coroutineContext[Job]
            try {
                while (lifecycle != null &&
                    loadFailed.isNotEmpty() &&
                    ready.value &&
                    connection.value == ConnectionState.CONNECTED
                ) {
                    if (sessionLoadFailCount > MAX_LOAD_RETRIES) {
                        giveUpResume()
                        break
                    }
                    val attempt = sessionLoadFailCount++
                    val wait = ReconnectBackoff.delayMs(attempt.coerceAtMost(6), Random.nextDouble())
                        .coerceAtLeast(50L)
                    delay(wait)
                    if (lifecycle == null) break
                    if (!ready.value || connection.value != ConnectionState.CONNECTED) break
                    val gen = socketGeneration
                    val toRetry = loadFailed.keys.mapNotNull { id -> attached[id] }
                    if (toRetry.isEmpty()) {
                        loadFailed.clear()
                        break
                    }
                    for (session in toRetry) {
                        if (gen != socketGeneration) return@launch
                        if (!attached.containsKey(session.id)) {
                            loadFailed.remove(session.id)
                            continue
                        }
                        loadAttachedSession(session)
                    }
                    if (loadFailed.isEmpty()) {
                        sessionLoadFailCount = 0
                        flushOutbox()
                    }
                }
            } finally {
                if (sessionLoadRetry === self) sessionLoadRetry = null
            }
        }
    }

    /**
     * Retries are spent: tell each session it could not be resumed and fail what was waiting on
     * it, instead of leaving prompts queued behind a load that will never finish.
     */
    private suspend fun giveUpResume() {
        val ids = loadFailed.keys.toList()
        loadFailed.clear()
        for (id in ids) {
            _updates.emit(
                SessionUpdate(
                    id,
                    CodeUpdate.Upsert(
                        CodeEvent.Notice(
                            "load-giveup:$id", System.currentTimeMillis(),
                            "Couldn't resume this session. Close and reopen it to try again.",
                            NoticeLevel.ERROR,
                            textRes = R.string.code_notice_resume_gave_up,
                        )
                    )
                )
            )
            failQueuedPrompts(id)
        }
    }

    /**
     * Drops the prompts still queued for [sessionId] and ends its turn with an error, so the
     * composer is not stuck on Stop for a message that will never be sent.
     */
    private suspend fun failQueuedPrompts(sessionId: String) {
        val dropped = synchronized(outboxLock) { outbox.removeAll { it.sessionId == sessionId } }
        if (!dropped) return
        runningSessions.remove(sessionId)
        clearPendingUser(sessionId)
        _updates.emit(
            SessionUpdate(
                sessionId,
                CodeUpdate.Upsert(
                    CodeEvent.Notice(
                        "send-fail:${System.currentTimeMillis()}", System.currentTimeMillis(),
                        "Couldn't send your message. Check the connection and try again.",
                        NoticeLevel.ERROR,
                        textRes = R.string.code_notice_send_gave_up,
                    )
                )
            )
        )
        _updates.emit(SessionUpdate(sessionId, CodeUpdate.TurnDone("error")))
        refreshKeepAlive()
    }

    private fun refreshKeepAlive() {
        val pendingOutbox = synchronized(outboxLock) { outbox.isNotEmpty() }
        transport.setKeepAliveForSession(
            runningSessions.isNotEmpty() || pendingOutbox || cancelPending.isNotEmpty()
        )
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
                model = s("model"),
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
        scope.launch {
            try {
                prompt(sid, request.prompt, request.attachments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The session exists but its first prompt never left: end the turn visibly.
                _updates.emit(SessionUpdate(sid, errorNotice(e.message)))
                _updates.emit(SessionUpdate(sid, CodeUpdate.TurnDone("error")))
                runningSessions.remove(sid)
                refreshKeepAlive()
            }
        }
        return summary
    }

    private fun errorNotice(message: String?) = CodeUpdate.Upsert(
        CodeEvent.Notice(
            "err:${System.currentTimeMillis()}", System.currentTimeMillis(),
            message ?: "Turn failed", NoticeLevel.ERROR,
            textRes = if (message == null) R.string.code_notice_turn_failed else 0,
        )
    )

    override suspend fun attach(session: CodeSessionSummary) {
        attached[session.id] = session
        session.lastSeq?.let { rememberLastSeq(session.id, it) }
        ensureReady()
        if (session.id in resuming) {
            // The reconnect replay is already loading this session; a second load would repeat it.
            withTimeoutOrNull(DEFAULT_TIMEOUT_MS * 2) { while (session.id in resuming) delay(25) }
            loadFailed[session.id]?.let { throw IllegalStateException(it) }
            return
        }
        val after = peekLastSeq(session.id) ?: session.lastSeq
        rawCall({ adapter.loadSession(it, session.id, session.workspace, after) }, DEFAULT_TIMEOUT_MS)
    }

    override suspend fun prompt(sessionId: String, text: String, attachments: List<PromptAttachment>) {
        val now = System.currentTimeMillis()
        val atts = attachments.take(CodePromptImages.MAX_COUNT)
        val localKey = "user:$now"
        rememberPendingUser(sessionId, localKey, text, atts.size)
        _updates.emit(
            SessionUpdate(
                sessionId,
                CodeUpdate.Upsert(
                    CodeEvent.UserPrompt(localKey, now, text, attachmentCount = atts.size)
                )
            )
        )
        promptMutex(sessionId).withLock {
            // A session still replaying its history waits in the outbox; the resume flushes it.
            if (!ready.value || connection.value != ConnectionState.CONNECTED || sessionId in resuming) {
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
            // Cancelled / Detached / deliver-stale → Aborted: never outbox-requeue.
            if (deliverPrompt(sessionId, text, atts) == DeliverResult.Retry) {
                // Send failed before the bridge accepted — queue for reconnect flush.
                synchronized(outboxLock) { outbox.addLast(OutboxPrompt(sessionId, text, atts)) }
                runningSessions.add(sessionId)
                refreshKeepAlive()
                if (connection.value != ConnectionState.CONNECTING &&
                    connection.value != ConnectionState.CONNECTED
                ) {
                    connect()
                } else {
                    // R4: still CONNECTED (e.g. OkHttp queue full) — no reconnect event;
                    // schedule a bounded flush so the item is not stuck forever.
                    scheduleOutboxFlushRetry()
                }
            }
        }
    }

    private suspend fun flushOutbox() {
        // Suspending lock: the connected-queue retry can wait here while a resume flush
        // is inside deliverPrompt, then see the head already removed.
        outboxFlushMutex.withLock { flushOutboxOnce() }
    }

    private suspend fun flushOutboxOnce() {
        while (true) {
            val next = synchronized(outboxLock) {
                if (outbox.isEmpty()) null
                else {
                    // R5: rotate past sessions whose resume load has not completed so a
                    // lost resume does not block sibling outbox items forever.
                    var skipped = 0
                    while (outbox.isNotEmpty() && skipped < outbox.size) {
                        val head = outbox.first()
                        if (!loadFailed.containsKey(head.sessionId) && head.sessionId !in resuming) return@synchronized head
                        outbox.removeFirst()
                        outbox.addLast(head)
                        skipped++
                    }
                    null
                }
            } ?: break
            refreshKeepAlive()
            val result = runCatching {
                promptMutex(next.sessionId).withLock {
                    deliverPrompt(next.sessionId, next.text, next.attachments)
                }
            }.getOrDefault(DeliverResult.Retry)
            when (result) {
                // E1: Done / Aborted — drop this head if still present and keep flushing
                // siblings. Only Retry (pre-accept failure) waits for reconnect / R4 flush.
                DeliverResult.Done, DeliverResult.Aborted -> {
                    // G1: referential match only — structural == would drop a re-queued
                    // identical prompt if cancel cleared the peeked head mid-deliver.
                    synchronized(outboxLock) {
                        if (outbox.isNotEmpty() && outbox.first() === next) outbox.removeFirst()
                    }
                    outboxFlushFailCount = 0
                }
                DeliverResult.Retry -> {
                    // R4: send failed while transport may still be CONNECTED — schedule a
                    // bounded flush retry instead of waiting forever for a drop.
                    scheduleOutboxFlushRetry()
                    break
                }
            }
        }
        refreshKeepAlive()
    }

    /**
     * R4: when [transport.send] returns false while still [ConnectionState.CONNECTED]
     * (queue full), [flushOutbox] breaks on Retry and no reconnect fires. One worker
     * loops with backoff so a Retry inside [flushOutbox] (same job still active) still
     * gets another attempt. Keep the item queued until success or user cancel.
     */
    private fun scheduleOutboxFlushRetry() {
        if (connection.value != ConnectionState.CONNECTED || !ready.value) return
        if (synchronized(outboxLock) { outbox.isEmpty() }) return
        if (outboxFlushRetry?.isActive == true) return
        outboxFlushRetry = scope.launch {
            val self = coroutineContext[Job]
            try {
                while (lifecycle != null &&
                    ready.value &&
                    connection.value == ConnectionState.CONNECTED &&
                    synchronized(outboxLock) { outbox.isNotEmpty() }
                ) {
                    if (outboxFlushFailCount > MAX_OUTBOX_RETRIES) {
                        // The link says CONNECTED but will not take the prompt: say so, don't wait forever.
                        val stuck = synchronized(outboxLock) { outbox.map { it.sessionId }.distinct() }
                        stuck.forEach { failQueuedPrompts(it) }
                        outboxFlushFailCount = 0
                        break
                    }
                    val attempt = outboxFlushFailCount++
                    val wait = ReconnectBackoff.delayMs(attempt.coerceAtMost(6), Random.nextDouble())
                        .coerceAtLeast(50L)
                    delay(wait)
                    if (lifecycle == null) break
                    if (!ready.value || connection.value != ConnectionState.CONNECTED) break
                    if (synchronized(outboxLock) { outbox.isEmpty() }) break
                    flushOutbox()
                }
            } finally {
                if (outboxFlushRetry === self) outboxFlushRetry = null
            }
        }
    }

    /**
     * @return [DeliverResult.Done] if accepted (turn finished, terminal error, or on-wire after
     * send); [DeliverResult.Retry] if deliver failed before accept (outbox-requeue);
     * [DeliverResult.Aborted] for Cancelled / Detached / deliver-stale (never requeue).
     */
    private suspend fun deliverPrompt(
        sessionId: String,
        text: String,
        attachments: List<PromptAttachment> = emptyList(),
    ): DeliverResult {
        val gen = deliverGen(sessionId)
        cancelPending.remove(sessionId)
        suppressAgent.remove(sessionId)
        runningSessions.add(sessionId)
        refreshKeepAlive()
        if (isDeliverStale(sessionId, gen)) {
            runningSessions.remove(sessionId)
            refreshKeepAlive()
            return DeliverResult.Aborted
        }
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        inFlightPromptId[sessionId] = id
        var sendCompleted = false
        try {
            if (isDeliverStale(sessionId, gen)) return DeliverResult.Aborted
            // Frame build embeds multi-MB base64; keep it (and send) off Hub Main.immediate.
            val sendOk = withContext(Dispatchers.IO) {
                if (isDeliverStale(sessionId, gen)) return@withContext null
                val frame = adapter.prompt(id, sessionId, text, attachments)
                if (!transport.send(frame)) {
                    throw IllegalStateException(transport.lastError ?: "Not connected")
                }
                true
            }
            if (sendOk == null) {
                // Cancel won before send; do not treat as delivered / requeued.
                refreshKeepAlive()
                return DeliverResult.Aborted
            }
            sendCompleted = true
            // Prompt has no timeout: the turn can run for a long time.
            val result = deferred.await() as? JsonObject
            if (isDeliverStale(sessionId, gen)) {
                // Stop won the race after send; cancel already emitted TurnDone.
                refreshKeepAlive()
                return DeliverResult.Aborted
            }
            val stop = (result?.get("stopReason") as? JsonPrimitive)?.contentOrNull ?: "end_turn"
            (adapter as? AcpAdapter)?.endTurn(sessionId)
            _updates.emit(SessionUpdate(sessionId, CodeUpdate.TurnDone(stop)))
            runningSessions.remove(sessionId)
            refreshKeepAlive()
            return DeliverResult.Done
        } catch (e: CancellationException) {
            // User Stop / forget-detach: never dequeue as delivered (especially if send never completed).
            // C3: detach completes with "Detached" — same abort semantics as "Cancelled".
            if (isDeliverStale(sessionId, gen) ||
                e.message == "Cancelled" ||
                e.message == "Detached"
            ) {
                refreshKeepAlive()
                return DeliverResult.Aborted
            }
            // Socket dropped mid-turn; keep running so reconnect stays alive. session/load resumes.
            // Treat as accepted for outbox only when the frame was already on the wire.
            refreshKeepAlive()
            return if (sendCompleted) DeliverResult.Done else DeliverResult.Retry
        } catch (e: Exception) {
            val notAccepted = !sendCompleted || connection.value != ConnectionState.CONNECTED ||
                (e is IllegalStateException && (
                    e.message == "Not connected" ||
                        e.message?.startsWith("Can't reach") == true
                    ))
            if (isDeliverStale(sessionId, gen)) {
                refreshKeepAlive()
                return DeliverResult.Aborted
            }
            if (notAccepted) {
                refreshKeepAlive()
                return DeliverResult.Retry
            }
            _updates.emit(SessionUpdate(sessionId, errorNotice(e.message)))
            _updates.emit(SessionUpdate(sessionId, CodeUpdate.TurnDone("error")))
            runningSessions.remove(sessionId)
            refreshKeepAlive()
            return DeliverResult.Done
        } finally {
            pending.remove(id)
            inFlightPromptId.remove(sessionId, id)
        }
    }

    override suspend fun answer(sessionId: String, requestId: String, option: ApprovalOption?) {
        // M3 / AWAY-02: ignore a second Allow/Deny for the same session+request while in flight.
        val key = answeringKey(sessionId, requestId)
        if (!answering.add(key)) return
        try {
            // AWAY-01: cold-start / disconnected hosts must connect + await ACP ready
            // before the permission reply; otherwise transport.send returns false.
            ensureReady()
            // H2: do not mark answered / cancel away shade when the frame never left the device.
            if (!transport.send(adapter.answerApproval(requestId, option?.id))) {
                throw IllegalStateException(transport.lastError ?: "Not connected")
            }
            _updates.emit(
                SessionUpdate(
                    sessionId,
                    CodeUpdate.ApprovalAnswered(requestId, option?.kind ?: ApprovalOption.Kind.REJECT_ONCE)
                )
            )
        } finally {
            answering.remove(key)
        }
    }

    /** AWAY-02: composite in-flight key so two sessions may share a numeric request id. */
    private fun answeringKey(sessionId: String, requestId: String) = "$sessionId\u0000$requestId"

    override suspend fun cancel(sessionId: String) {
        // C1 / D1: after forget→detach, never re-seed suppressAgent / deliverGeneration
        // (would swallow a later session/load). Abort leftovers without suppress stamps.
        if (!attached.containsKey(sessionId)) {
            cancelPending.remove(sessionId)
            inFlightPromptId.remove(sessionId)?.let { rpcId ->
                pending.remove(rpcId)?.completeExceptionally(CancellationException("Cancelled"))
            }
            synchronized(outboxLock) { outbox.removeAll { it.sessionId == sessionId } }
            runningSessions.remove(sessionId)
            refreshKeepAlive()
            runCatching { transport.send(adapter.cancel(sessionId)) }
            return
        }
        val hadWireTurn =
            inFlightPromptId.containsKey(sessionId) || sessionId in runningSessions
        val locallyActive =
            hadWireTurn ||
            synchronized(outboxLock) { outbox.any { it.sessionId == sessionId } }
        if (!locallyActive) {
            // Attached idle: best-effort wire cancel; retain pending if send fails (no TurnDone).
            if (!sendCancelOrPending(sessionId, finalizeOnSuccess = false)) {
                scheduleCancelFlush()
            }
            return
        }
        // Abort in-flight deliverPrompt (flush or live turn) and drop queued prompts.
        bumpDeliverGen(sessionId)
        inFlightPromptId.remove(sessionId)?.let { rpcId ->
            pending.remove(rpcId)?.completeExceptionally(CancellationException("Cancelled"))
        }
        synchronized(outboxLock) { outbox.removeAll { it.sessionId == sessionId } }
        runningSessions.remove(sessionId)
        refreshKeepAlive()
        // R3: only finalize suppress/TurnDone when session/cancel is on the wire.
        // Outbox-only (never left the device) may finalize locally even if send fails.
        val sent = sendCancelOrPending(sessionId, finalizeOnSuccess = true)
        if (sent) return
        if (!hadWireTurn) {
            cancelPending.remove(sessionId)
            finalizeAcceptedCancel(sessionId)
            return
        }
        // Live/in-flight turn: keep cancel-pending, do not suppress/TurnDone as accepted.
        _updates.emit(
            SessionUpdate(
                sessionId,
                CodeUpdate.Upsert(
                    CodeEvent.Notice(
                        "cancel-pending:${System.currentTimeMillis()}",
                        System.currentTimeMillis(),
                        "Stop did not reach host, retrying",
                        NoticeLevel.WARNING,
                        textRes = R.string.code_notice_stop_pending,
                    )
                )
            )
        )
        scheduleCancelFlush()
    }

    /**
     * Attempt session/cancel. On success remove pending and optionally finalize local Stop.
     * On failure retain [cancelPending] with [finalizeOnSuccess] intent. Returns whether queued.
     */
    private suspend fun sendCancelOrPending(sessionId: String, finalizeOnSuccess: Boolean): Boolean {
        val ok = runCatching { transport.send(adapter.cancel(sessionId)) }.getOrDefault(false)
        if (ok) {
            cancelPending.remove(sessionId)
            if (finalizeOnSuccess) finalizeAcceptedCancel(sessionId)
            refreshKeepAlive()
            return true
        }
        // Sticky true: once a live-turn cancel needs finalize, later idle retries keep it.
        cancelPending.merge(sessionId, finalizeOnSuccess) { prev, next -> prev || next }
        refreshKeepAlive()
        return false
    }

    /** Local Stop accepted only after wire cancel queued (or outbox-only local abort). */
    private suspend fun finalizeAcceptedCancel(sessionId: String) {
        suppressAgent.add(sessionId)
        // The "Stopped" row comes from the TurnEnd this produces; a Notice too would say it twice.
        _updates.emit(SessionUpdate(sessionId, CodeUpdate.TurnDone("cancelled")))
    }

    /** Resend any cancel-pending intents; finalize each that queues successfully. */
    private suspend fun flushCancelPending() {
        val snap = cancelPending.entries.map { it.key to it.value }
        if (snap.isEmpty()) return
        for ((sessionId, finalize) in snap) {
            if (!attached.containsKey(sessionId)) {
                cancelPending.remove(sessionId)
                continue
            }
            sendCancelOrPending(sessionId, finalizeOnSuccess = finalize)
        }
        refreshKeepAlive()
    }

    /** Bounded retry while still CONNECTED/ready (queue-full) in addition to reconnect flush. */
    private fun scheduleCancelFlush() {
        if (cancelFlushJob?.isActive == true) return
        cancelFlushJob = scope.launch {
            var attempt = 0
            while (cancelPending.isNotEmpty() && attempt < 6) {
                delay(150L * (1 shl attempt.coerceAtMost(3)))
                if (lifecycle == null) return@launch
                if (!ready.value) return@launch
                flushCancelPending()
                attempt++
            }
        }
    }

    override suspend fun setPermissionMode(sessionId: String, mode: PermissionMode) {
        call({ adapter.setMode(it, sessionId, mode) })
    }

    override fun detach(sessionId: String) {
        attached.remove(sessionId)
        runningSessions.remove(sessionId)
        suppressAgent.remove(sessionId)
        cancelPending.remove(sessionId)
        loadFailed.remove(sessionId)
        resuming.remove(sessionId)
        gapReloads.remove(sessionId)
        clearPendingUser(sessionId)
        deliverGeneration.remove(sessionId)
        inFlightPromptId.remove(sessionId)?.let { rpcId ->
            pending.remove(rpcId)?.completeExceptionally(CancellationException("Detached"))
        }
        // D1: on Main.immediate / Unconfined, Detached resume runs inside completeExceptionally.
        // prompt() must not leave runningSessions/outbox populated for a forgotten id —
        // re-clear after the sync abort (defense even if prompt skips requeue).
        runningSessions.remove(sessionId)
        synchronized(outboxLock) { outbox.removeAll { it.sessionId == sessionId } }
        promptMutexes.remove(sessionId)
        (adapter as? AcpAdapter)?.clearLastSeq(sessionId)
        refreshKeepAlive()
    }

    override fun close() {
        handshakeRetry?.cancel()
        handshakeRetry = null
        outboxFlushRetry?.cancel()
        outboxFlushRetry = null
        outboxFlushFailCount = 0
        sessionLoadRetry?.cancel()
        sessionLoadRetry = null
        sessionLoadFailCount = 0
        cancelFlushJob?.cancel()
        cancelFlushJob = null
        lifecycle?.cancel()
        lifecycle = null
        reader?.cancel()
        reader = null
        ready.value = false
        initialized = false
        handshakeError = null
        handshakeFailCount = 0
        bridgeVersion = null
        attached.clear()
        runningSessions.clear()
        deliverGeneration.clear()
        inFlightPromptId.clear()
        suppressAgent.clear()
        cancelPending.clear()
        loadFailed.clear()
        resuming.clear()
        gapReloads.clear()
        handshakeRetryDeferred = false
        synchronized(pendingUserLock) { pendingUserPrompts.clear() }
        answering.clear()
        promptMutexes.clear()
        synchronized(outboxLock) { outbox.clear() }
        failPending("Closed")
        transport.close()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        /** Handshake attempts after a failed initialize before waiting for a deliberate connect. */
        const val MAX_HANDSHAKE_RETRIES = 6
        const val MAX_LOAD_RETRIES = 16
        const val MAX_OUTBOX_RETRIES = 16
        const val MAX_GAP_RELOADS = 3
    }
}
