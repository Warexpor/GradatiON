package io.github.stardomains3.oxproxion.code

import android.content.Context
import android.util.Log
import io.github.stardomains3.oxproxion.AppDatabase
import io.github.stardomains3.oxproxion.R
import io.github.stardomains3.oxproxion.code.store.CodeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Session as the UI sees it: summary plus live state. */
data class CodeSessionState(
    val summary: CodeSessionSummary,
    val events: List<CodeEvent> = emptyList(),
    val running: Boolean = false,
    val attached: Boolean = false,
    /** Slash commands from ACP `available_commands_update` (session-scoped, not persisted). */
    val availableCommands: List<AvailableCommand> = emptyList(),
    /** An attach (session/load) is in flight: the screen shows "loading" until history lands. */
    val attaching: Boolean = false,
    /** Why the last attach failed; null when it has not. Empty when the failure had no message. */
    val attachError: String? = null,
) {
    // Scans the transcript, and the home list asks on every emission, so do it once per state.
    val status: SessionStatus by lazy(LazyThreadSafetyMode.NONE) { TranscriptReducer.statusOf(events, running) }
}

/**
 * App-wide owner of Code mode state: hosts, one backend per host, sessions and their live
 * transcripts. Outlives fragments so a running agent keeps streaming while the user reads chat.
 * Main-thread confined (all mutation happens on [scope], which runs on Main).
 *
 * Session index (summaries + lastSeq) is persisted in Room via [CodeSessionDao]; full transcript
 * blobs stay in memory for now.
 */
class CodeHub internal constructor(context: Context) {

    val store = CodeStore(context)
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backends = HashMap<String, CodeBackend>()
    /** Sessions cancelled locally until their queued TurnDone is folded (blocks chunk revive). */
    private val suppressRunningFromChunks = HashSet<String>()
    /**
     * Sessions whose local cancel was superseded by a newer [prompt] (B1/H1).
     * Only those ids ignore a queued TurnDone("cancelled"); natural ACP cancelled clears running.
     */
    private val ignoreStaleCancelTurnDone = HashSet<String>()
    /** In-flight approval answers (M3 / AWAY-02); key = sessionId + requestId. */
    private val answeringRequests = HashSet<String>()
    /** Sessions currently running backend.attach (E3); coalesce overlapping opens. */
    private val attachingSessions = HashSet<String>()
    /** Opens that arrived while an attach was in flight (F3); re-launch after failure/finally. */
    private val needsAttach = HashSet<String>()
    // Opening the encrypted database costs real time; first use happens on the IO loader below.
    private val sessionDao: CodeSessionDao by lazy { AppDatabase.getDatabase(appContext).codeSessionDao() }
    /** Latest session-index snapshot waiting for Room; null when idle. */
    private val pendingPersist = AtomicReference<List<CodeSessionEntity>?>(null)
    /** Single-flight flag so only one replaceAll runs at a time. */
    private val persistRunning = AtomicBoolean(false)

    /** Coalesce backend SessionUpdates to ~one _sessions write per frame (see SessionUpdatePump). */
    private val updatePump = SessionUpdatePump(scope, ::drainSessionUpdates)

    /** Sessions the user has open on screen. They re-attach when their machine comes back. */
    private val viewing = HashSet<String>()

    /** Session ids the user renamed. Bridge titles must not replace those. */
    private val pinnedTitles = HashSet(store.pinnedSessionTitles())

    /** Unsent composer lines, keyed by session. Lost with the process, same as the transcript. */
    private val composerDrafts = HashMap<String, CodeComposerDrafts.Draft>()

    /** Local away notifications (§5.6); no sticky FGS. */
    val awayNotifier = CodeAwayNotifier(appContext, store) { hostId ->
        // Real CONNECTED only (demo included once its backend is up). No isDemo bypass —
        // historical attach TurnDone is gated via sessionWasRunning in onUpdate (A4).
        connectionOf(hostId) == ConnectionState.CONNECTED
    }.also { it.setBackgrounded(appBackgrounded) }

    private val _hosts = MutableStateFlow(store.hosts)
    val hosts: StateFlow<List<CodeHost>> = _hosts

    private val _activeHost = MutableStateFlow(resolveActive())
    val activeHost: StateFlow<CodeHost?> = _activeHost

    private val _sessions = MutableStateFlow<Map<String, CodeSessionState>>(emptyMap())
    /** All known sessions by id. Fills in shortly after the hub exists; see [sessionsLoaded]. */
    val sessions: StateFlow<Map<String, CodeSessionState>> = _sessions

    private val _sessionsLoaded = MutableStateFlow(false)
    /** True once the saved session index has been read, so an empty list can be told from "not yet". */
    val sessionsLoaded: StateFlow<Boolean> = _sessionsLoaded
    /** A persist asked for before the index loaded; writing then would wipe rows not read yet. */
    @Volatile private var persistWaitsForLoad = false

    private val sessionsLoad: Job = scope.launch(Dispatchers.IO) {
        val loaded = try {
            loadSessionsFromRoom()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // No saved index is better than no Code tab; the list just starts empty.
            Log.w(TAG, "Session index failed to load", t)
            emptyMap()
        }
        // In-memory entries (an early startSession, say) win over what was saved.
        _sessions.update { cur -> loaded + cur }
        _sessionsLoaded.value = true
        if (persistWaitsForLoad) {
            persistWaitsForLoad = false
            queuePersist(_sessions.value)
        }
    }

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = _connection

    private val _connections = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    /** Connection state per host id (multiple hosts can be up at once). */
    val connections: StateFlow<Map<String, ConnectionState>> = _connections

    private fun resolveActive(): CodeHost? {
        val all = store.hosts
        return all.find { it.id == store.activeHostId } ?: all.firstOrNull()
    }

    /** Load Room index; one-shot merge of any leftover prefs session list. Runs on IO. */
    private suspend fun loadSessionsFromRoom(): Map<String, CodeSessionState> {
        if (!store.sessionsMigratedToRoom) {
            try {
                val legacy = store.peekLegacySessions().orEmpty()
                if (legacy.isNotEmpty()) {
                    val existing = sessionDao.getAll()
                    val toUpsert = mergeLegacySessionRows(existing, legacy)
                    if (toUpsert.isNotEmpty()) sessionDao.upsertAll(toUpsert)
                }
                // Mark migrated only after merge/upsert succeeds (or nothing to import).
                // A corrupt list throws and stays in prefs so the next launch retries.
                store.markSessionsMigrated()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Prefs→Room session migration deferred", t)
            }
        }
        return sessionDao.getAll().associate { it.id to CodeSessionState(it.toSummary()) }
    }

    /**
     * Blocks until the saved session index has loaded. Only for a path that needs the list this
     * instant and cannot wait for [sessionsLoaded] (an away-notification tap on a cold start, and
     * tests). Normal screens observe [sessions] and [sessionsLoaded] instead.
     */
    fun awaitSessionsLoaded() = runBlocking { sessionsLoad.join() }

    // ── hosts ─────────────────────────────────────────────────────────────────────────────

    fun saveHost(host: CodeHost) {
        val list = _hosts.value.toMutableList()
        val i = list.indexOfFirst { it.id == host.id }
        if (i >= 0) list[i] = host else list += host
        store.hosts = list
        _hosts.value = list
        // E4: closing the backend aborts in-flight turns without a Hub TurnDone — clear
        // Stop chrome so Send is not stuck after a host edit mid-turn.
        // F1: also clear Hub attached / in-flight attach so open sessions re-session/load
        // on the rebuilt backend (sticky attached would skip hub.attach).
        var clearedRunning = false
        val toReattach = ArrayList<String>()
        _sessions.value = _sessions.value.mapValues { (id, st) ->
            if (st.summary.hostId != host.id) st
            else {
                suppressRunningFromChunks.remove(id)
                ignoreStaleCancelTurnDone.remove(id)
                val wasAttaching = id in attachingSessions || id in needsAttach
                attachingSessions.remove(id)
                needsAttach.remove(id)
                if (st.attached || wasAttaching) toReattach += id
                if (st.running) clearedRunning = true
                if (st.running || st.attached) st.copy(running = false, attached = false)
                else st
            }
        }
        if (clearedRunning) persistSessions()
        backends.remove(host.id)?.close()
        if (_activeHost.value == null || _activeHost.value?.id == host.id) selectHost(host.id)
        toReattach.forEach { attach(it) }
    }

    fun removeHost(id: String) {
        // A5: clear shade entries before dropping sessions for this host.
        sessionsFor(id).forEach { awayNotifier.cancelSession(it.summary.id) }
        backends.remove(id)?.close()
        _connections.value = _connections.value - id
        val list = _hosts.value.filterNot { it.id == id }
        store.hosts = list
        _hosts.value = list
        _sessions.value = _sessions.value.filterValues { it.summary.hostId != id }
        persistSessions()
        if (_activeHost.value?.id == id) selectHost(list.firstOrNull()?.id)
    }

    fun selectHost(id: String?) {
        store.activeHostId = id
        _activeHost.value = _hosts.value.find { it.id == id }
        _activeHost.value?.let { connect(it) } ?: run { _connection.value = ConnectionState.DISCONNECTED }
    }

    /** Adds (or reuses) the built-in demo machine and makes it active. */
    fun addDemoHost(): CodeHost {
        val existing = _hosts.value.find { it.isDemo }
        val host = existing ?: CodeHost(
            id = "demo", name = "Demo machine", transport = TransportKind.DEMO,
            defaultHarness = HarnessKind.CLAUDE_CODE, defaultWorkspace = "~/code/GradatiON"
        )
        if (existing == null) saveHost(host) else selectHost(host.id)
        return host
    }

    fun newHostId(): String = UUID.randomUUID().toString()

    private fun backendFor(host: CodeHost): CodeBackend = backends.getOrPut(host.id) {
        val b = when (host.transport) {
            TransportKind.DEMO -> DemoBackend(host, scope)
            TransportKind.BRIDGE -> BridgeBackend(host, WebSocketTransport(host.url, host.token, host.fingerprint), AcpAdapter(), scope)
        }
        // Resume cursors are not seeded from Room: transcripts are memory-only, so a cursor with
        // no history behind it would skip everything before it. attach() decides per session.
        if (appBackgrounded) b.setAppBackgrounded(true)
        scope.launch { b.updates.collect { updatePump.offer(it) } }
        scope.launch {
            b.connection.collect { st ->
                _connections.value = _connections.value + (host.id to st)
                if (_activeHost.value?.id == host.id) _connection.value = st
                // A screen that was waiting on this machine (failed or never-started attach) retries.
                if (st == ConnectionState.CONNECTED) reattachViewing(host.id)
            }
        }
        b
    }

    private fun reattachViewing(hostId: String) {
        for (id in viewing.toList()) {
            val s = _sessions.value[id] ?: continue
            if (s.summary.hostId == hostId && !s.attached && !s.attaching) attach(id)
        }
    }

    fun connect(target: CodeHost? = null) {
        val host = target ?: _activeHost.value ?: return
        val b = backendFor(host)
        _connection.value = b.connection.value
        b.connect()
        refreshSessions()
    }

    /** Raw last error of the active host (codes included); for classifying, not for showing. */
    fun lastError(): String? = _activeHost.value?.let { backends[it.id]?.lastError }

    /** Turns the codes the transport leaves in `lastError` into localized text; wire messages pass through. */
    fun describeError(raw: String?): String? = when (raw) {
        CodeErrors.INVALID_ADDRESS -> appContext.getString(R.string.code_error_invalid_address)
        CodeErrors.TOKEN_REJECTED -> appContext.getString(R.string.code_error_token_rejected)
        CodeErrors.HANDSHAKE_FAILED -> appContext.getString(R.string.code_error_handshake)
        CodeErrors.PROTOCOL_VERSION -> appContext.getString(R.string.code_error_protocol)
        CodeErrors.AUTH_TERMINAL -> appContext.getString(R.string.code_error_auth_terminal)
        else -> raw
    }

    /** Connection state for [hostId] (falls back to DISCONNECTED when unknown). */
    fun connectionOf(hostId: String): ConnectionState =
        _connections.value[hostId] ?: ConnectionState.DISCONNECTED

    /** Last transport error for [hostId], if any. */
    fun lastErrorOf(hostId: String): String? = describeError(backends[hostId]?.lastError)

    /**
     * Bridge / server version from the last successful initialize on [hostId]'s backend.
     * Null when demo, never connected, or the handshake omitted version.
     */
    fun bridgeVersionOf(hostId: String): String? = backends[hostId]?.peekBridgeVersion()

    /** Pause bridge reconnect while backgrounded unless a session turn is in flight. */
    fun setAppBackgrounded(backgrounded: Boolean) {
        appBackgrounded = backgrounded
        awayNotifier.setBackgrounded(backgrounded)
        backends.values.forEach { it.setAppBackgrounded(backgrounded) }
    }

    fun refreshSessions() {
        val host = _activeHost.value ?: return
        val b = backendFor(host)
        scope.launch {
            val remote = runCatching { b.listSessions() }.getOrNull() ?: return@launch
            val map = _sessions.value.toMutableMap()
            remote.forEach { s ->
                val prev = map[s.id]
                val merged = if (prev == null) s else mergeListSessionsSummary(
                    s,
                    prev.summary,
                    keepLocalTitle = s.id in pinnedTitles,
                )
                map[s.id] = prev?.copy(summary = merged) ?: CodeSessionState(merged)
            }
            _sessions.value = map
            persistSessions()
        }
    }

    suspend fun workspaces(harness: HarnessKind): List<String> {
        val host = _activeHost.value ?: return emptyList()
        val remote = runCatching { backendFor(host).listWorkspaces(harness) }.getOrDefault(emptyList())
        return (host.recentWorkspaces + remote).distinct()
    }

    /** Harnesses the active host's bridge reports (`bridge/listHarnesses`). Empty if offline. */
    suspend fun harnesses(): List<HarnessInfo> {
        val host = _activeHost.value ?: return emptyList()
        return harnessesFor(host)
    }

    /**
     * Live harness list for [host] when a backend is already available and connected
     * (or the host is the in-process demo). Empty when offline / unpaired so callers can
     * fall back to the static [HarnessKind] catalog.
     */
    suspend fun harnessesFor(host: CodeHost): List<HarnessInfo> {
        val backend = backends[host.id] ?: if (host.isDemo) backendFor(host) else return emptyList()
        if (!host.isDemo && backend.connection.value != ConnectionState.CONNECTED) return emptyList()
        return runCatching { backend.listHarnesses() }.getOrDefault(emptyList())
    }

    /** Folder listing for the picker (`bridge/browse`), preserving failures for user feedback. */
    suspend fun browseResult(path: String): Result<List<BrowseEntry>> {
        val host = _activeHost.value
            ?: return Result.failure(IllegalStateException("No machine selected"))
        return runCatching { backendFor(host).browse(path) }
    }

    /** Working-tree changes for [sessionId] (`bridge/gitStatus`). */
    suspend fun gitStatusResult(sessionId: String): Result<GitStatusResult> {
        val s = _sessions.value[sessionId]
            ?: return Result.failure(IllegalStateException("Unknown session"))
        val host = _hosts.value.find { it.id == s.summary.hostId }
            ?: return Result.failure(IllegalStateException("No machine for session"))
        return runCatching { backendFor(host).gitStatus(sessionId) }
    }

    /** Unified diff for one path in [sessionId]'s workspace (`bridge/diff`). */
    suspend fun diffResult(sessionId: String, path: String): Result<GitDiffResult> {
        val s = _sessions.value[sessionId]
            ?: return Result.failure(IllegalStateException("Unknown session"))
        val host = _hosts.value.find { it.id == s.summary.hostId }
            ?: return Result.failure(IllegalStateException("No machine for session"))
        return runCatching { backendFor(host).diff(sessionId, path) }
    }

    // ── sessions ──────────────────────────────────────────────────────────────────────────

    fun sessionsFor(hostId: String?): List<CodeSessionState> =
        _sessions.value.values.filter { it.summary.hostId == hostId }.sortedByDescending { it.summary.updatedAt }

    suspend fun startSession(request: NewSessionRequest): Result<String> {
        val host = _hosts.value.find { it.id == request.hostId } ?: return Result.failure(IllegalStateException("No machine"))
        return runCatching {
            val summary = backendFor(host).startSession(request)
            // Remember the workspace for next time.
            saveHostQuietly(host.copy(recentWorkspaces = (listOf(request.workspace) + host.recentWorkspaces).distinct().take(8)))
            _sessions.value = _sessions.value + (summary.id to CodeSessionState(summary, running = true, attached = true))
            persistSessions()
            summary.id
        }
    }

    /** The user opened this session's screen: load its history from the machine and keep it live. */
    fun attach(sessionId: String) {
        val s = _sessions.value[sessionId] ?: return
        viewing += sessionId
        if (s.attached) return
        // F3: coalesce overlapping opens onto one in-flight attempt; remember
        // a concurrent open so failure/finally can re-launch (retry during slow ensureReady).
        if (!attachingSessions.add(sessionId)) {
            needsAttach.add(sessionId)
            return
        }
        needsAttach.remove(sessionId)
        val host = _hosts.value.find { it.id == s.summary.hostId }
        if (host == null) {
            attachingSessions.remove(sessionId)
            return
        }
        val backend = backendFor(host)
        // Transcripts live in memory only. With nothing on screen (a fresh launch), resume from
        // zero: the saved cursor would skip the very history this screen is about to show.
        val fresh = s.events.isEmpty()
        val summary = if (fresh) s.summary.copy(lastSeq = null) else s.summary
        if (fresh) backend.forgetLastSeq(sessionId)
        else summary.lastSeq?.let { backend.rememberLastSeq(sessionId, it) }
        update(sessionId) { it.copy(attaching = true, attachError = null) }
        // E3: only mark attached after a successful backend attach so a failed
        // ensureReady / session/load can retry on the next open.
        scope.launch {
            try {
                val failure = runCatching { backend.attach(summary) }.exceptionOrNull()
                update(sessionId) {
                    if (failure == null) it.copy(attached = true, attaching = false, attachError = null)
                    else it.copy(attaching = false, attachError = describeError(failure.message).orEmpty())
                }
                // The screen closed while this was in flight: release() had nothing to detach yet.
                if (sessionId !in viewing && _sessions.value[sessionId]?.running != true) detachIdle(sessionId)
            } finally {
                attachingSessions.remove(sessionId)
                val retry = sessionId in needsAttach &&
                    _sessions.value[sessionId]?.attached != true
                needsAttach.remove(sessionId)
                if (retry) attach(sessionId)
            }
        }
    }

    /**
     * The session screen closed. An idle session is detached so the backend stops tracking it
     * (reconnects only replay sessions that are open or working); reopening resumes from its
     * cursor. A running one stays attached until its turn ends.
     */
    fun release(sessionId: String) {
        viewing -= sessionId
        val s = _sessions.value[sessionId] ?: return
        if (s.running || s.status == SessionStatus.NEEDS_APPROVAL) return
        detachIdle(sessionId)
    }

    private fun detachIdle(sessionId: String) {
        val s = _sessions.value[sessionId] ?: return
        backends[s.summary.hostId]?.detach(sessionId)
        attachingSessions.remove(sessionId)
        needsAttach.remove(sessionId)
        if (s.attached || s.attaching) update(sessionId) { it.copy(attached = false, attaching = false) }
    }

    /** Starts a prompt when the session is idle; returns false for an overlapping prompt. */
    fun prompt(
        sessionId: String,
        text: String,
        attachments: List<PromptAttachment> = emptyList(),
    ): Boolean {
        // Reject a second overlapping prompt for the same session; BridgeBackend also
        // serializes deliver via a per-session mutex (queue-or-reject: we reject here).
        var accepted = false
        update(sessionId) { cur ->
            if (cur.running) cur
            else {
                accepted = true
                cur.copy(running = true)
            }
        }
        if (!accepted) return false
        // B1/H1: if a local cancel is still pending, mark its TurnDone stale so Stop→Send
        // keeps running; natural cancelled (no stamp) still clears.
        if (sessionId in suppressRunningFromChunks) {
            ignoreStaleCancelTurnDone += sessionId
        }
        suppressRunningFromChunks.remove(sessionId)
        withBackend(sessionId) { b -> b.prompt(sessionId, text, attachments) }
        return true
    }


    /** AWAY-02: approval in-flight keys must not collide across sessions. */
    private fun answeringKey(sessionId: String, requestId: String) = "$sessionId\u0000$requestId"

    /**
     * Answers an approval. [onFailed] runs (on Main) when the answer did not go out, or there was
     * nothing left to answer, so the card can re-enable its buttons and say why.
     */
    fun answer(sessionId: String, requestId: String, option: ApprovalOption?, onFailed: () -> Unit = {}) {
        // M3 / AWAY-02: drop a second tap for the same session+request before fold.
        val key = answeringKey(sessionId, requestId)
        if (!answeringRequests.add(key)) return
        val s = _sessions.value[sessionId]
        val open = s?.events?.any {
            it is CodeEvent.Approval && it.requestId == requestId && it.pending
        } == true
        val host = s?.let { st -> _hosts.value.find { it.id == st.summary.hostId } }
        if (!open || host == null) {
            answeringRequests.remove(key)
            onFailed()
            return
        }
        scope.launch {
            try {
                backendFor(host).answer(sessionId, requestId, option)
            } catch (e: kotlinx.coroutines.CancellationException) {
                answeringRequests.remove(key)
                // Waiting for the machine timed out; that is a failed answer, not a cancelled screen.
                if (e is kotlinx.coroutines.TimeoutCancellationException) onFailed() else throw e
            } catch (_: Throwable) {
                // H2: send failed — allow retry.
                answeringRequests.remove(key)
                onFailed()
            }
        }
    }

    /**
     * Away-notification Allow/Deny (A2 / AWAY-01): run the answer on the hub scope and invoke
     * [onDone] when finished (or after timeout / missing session). Connects and awaits ACP
     * readiness inside [AWAY_ANSWER_TIMEOUT_MS] when the transport is cold. Caller cancels the
     * shade only on success (send accepted).
     */
    fun answerFromAway(
        sessionId: String,
        requestId: String,
        option: ApprovalOption?,
        onDone: (Boolean) -> Unit,
    ) {
        // A notification button can start a cold process; the session list is still loading then,
        // and this is a short, user-initiated path, so it may wait for it.
        if (!_sessionsLoaded.value) awaitSessionsLoaded()
        val s = _sessions.value[sessionId]
        if (s == null) {
            onDone(false)
            return
        }
        val host = _hosts.value.find { it.id == s.summary.hostId }
        if (host == null) {
            onDone(false)
            return
        }
        // B3: already-chosen → dismiss shade. Missing local row (cold start: Room index
        // has no transcript) still attempts the wire — PendingIntent is app-private (AWAY-01).
        val approval = s.events.filterIsInstance<CodeEvent.Approval>()
            .find { it.requestId == requestId }
        if (approval != null && !approval.pending) {
            onDone(true) // already answered or expired — cancel lingering shade
            return
        }
        val key = answeringKey(sessionId, requestId)
        if (!answeringRequests.add(key)) {
            onDone(false)
            return
        }
        scope.launch {
            // AWAY-01: BridgeBackend.answer ensureReady() connects + awaits ACP readiness
            // inside this timeout; only report success after send is accepted.
            val ok = try {
                withTimeout(AWAY_ANSWER_TIMEOUT_MS) {
                    backendFor(host).answer(sessionId, requestId, option)
                    true
                }
            } catch (_: Throwable) {
                answeringRequests.remove(key)
                false
            }
            onDone(ok)
        }
    }

    fun cancel(sessionId: String) {
        // Eager clear so Stop→Send is not rejected while pump still holds TurnDone.
        // Keep stale queued chunks from reviving running until that turn's done arrives.
        if (_sessions.value.containsKey(sessionId)) {
            suppressRunningFromChunks += sessionId
            // A fresh cancel invalidates any prior Stop→Send ignore stamp (B1).
            ignoreStaleCancelTurnDone.remove(sessionId)
        }
        update(sessionId) { it.copy(running = false) }
        withBackend(sessionId) { it.cancel(sessionId) }
    }

    fun setPermissionMode(sessionId: String, mode: PermissionMode) {
        val prev = _sessions.value[sessionId]?.summary?.permissionMode ?: return
        if (prev == mode) return
        update(sessionId) { it.copy(summary = it.summary.copy(permissionMode = mode)) }
        persistSessions()
        // E5: revert local mode when the wire set_mode fails so UI matches the bridge.
        // F2: CAS — only revert if Hub still shows this optimistic mode (do not clobber
        // a newer successful toggle whose RPC already completed).
        withBackend(sessionId) {
            val ok = runCatching { it.setPermissionMode(sessionId, mode) }.isSuccess
            if (!ok) {
                var reverted = false
                update(sessionId) { cur ->
                    val next = revertPermissionModeIfCurrent(cur.summary.permissionMode, mode, prev)
                    if (next != null) {
                        reverted = true
                        cur.copy(summary = cur.summary.copy(permissionMode = next))
                    } else cur
                }
                if (reverted) persistSessions()
            }
        }
    }

    fun forget(sessionId: String) {
        pinnedTitles.remove(sessionId)
        composerDrafts.remove(sessionId)
        store.unpinSessionTitle(sessionId)
        awayNotifier.cancelSession(sessionId) // A5
        // M2: stop an in-flight turn so keepalive / outbox do not outlive the row.
        // B2: detach so reconnect does not session/load a forgotten id.
        viewing -= sessionId
        val s = _sessions.value[sessionId]
        if (s != null) {
            // C1: do not leave cancel-suppress for a dropped id (wire-only cancel emits no TurnDone).
            suppressRunningFromChunks.remove(sessionId)
            ignoreStaleCancelTurnDone.remove(sessionId)
            attachingSessions.remove(sessionId)
            needsAttach.remove(sessionId)
            val host = _hosts.value.find { it.id == s.summary.hostId }
            if (host != null) {
                val backend = backendFor(host)
                backend.detach(sessionId)
                scope.launch { runCatching { backend.cancel(sessionId) } }
            }
        }
        _sessions.value = _sessions.value - sessionId
        persistSessions()
    }

    /** The line (and pictures) left in this session's composer, if the screen was closed on them. */
    fun sessionDraft(sessionId: String): CodeComposerDrafts.Draft? = composerDrafts[sessionId]

    fun parkSessionDraft(sessionId: String, text: String, attachments: List<PromptAttachment>) {
        CodeComposerDrafts.park(composerDrafts, sessionId, text, attachments)
    }

    fun clearSessionDraft(sessionId: String) {
        composerDrafts.remove(sessionId)
    }

    /** Phone-local title edit; persists via the session index in Room. The bridge cannot replace it. */
    fun rename(sessionId: String, title: String) {
        val t = title.trim()
        if (t.isEmpty() || _sessions.value[sessionId] == null) return
        pinnedTitles += sessionId
        store.pinSessionTitle(sessionId)
        update(sessionId) {
            it.copy(summary = it.summary.copy(title = t, updatedAt = System.currentTimeMillis()))
        }
        persistSessions()
    }

    private fun withBackend(sessionId: String, block: suspend (CodeBackend) -> Unit) {
        val s = _sessions.value[sessionId] ?: return
        val host = _hosts.value.find { it.id == s.summary.hostId } ?: return
        scope.launch { block(backendFor(host)) }
    }

    /**
     * Drain a coalesced batch: fold every pending update in order offline, then one
     * [_sessions] assignment covering all touched sessionIds. Persist once if any
     * TurnDone/SessionInfo landed in the batch.
     */
    private fun drainSessionUpdates(batch: List<SessionUpdate>) {
        // Snapshot pre-fold running + cancel-suppress so TurnDone eligibility is correct (A4).
        val before = _sessions.value
        val suppressSnapshot = suppressRunningFromChunks.toSet()
        val result = foldSessionUpdates(
            before,
            batch,
            liveSeqOf = { state, sid -> backends[state.summary.hostId]?.peekLastSeq(sid) },
            suppressRunningFromChunks = suppressRunningFromChunks,
            ignoreStaleCancelTurnDone = ignoreStaleCancelTurnDone,
            pinnedTitles = pinnedTitles,
        )
        if (result.sessions != null) _sessions.value = result.sessions
        // Guards are only for the cancelled turn; a later prompt starts normally.
        batch.forEach {
            if (it.update is CodeUpdate.TurnDone) {
                suppressRunningFromChunks.remove(it.sessionId)
                ignoreStaleCancelTurnDone.remove(it.sessionId)
            }
        }
        // Local away notifs (§5.6): approval / turn finished while backgrounded + connected.
        for (su in batch) {
            if (su.update is CodeUpdate.ApprovalAnswered) {
                answeringRequests.remove(answeringKey(su.sessionId, su.update.requestId))
            }
            if (su.update is CodeUpdate.TurnDone) {
                // Its unanswered approvals just expired; drop their in-flight marks too.
                answeringRequests.removeAll { it.startsWith(su.sessionId + "\u0000") }
            }
            val state = _sessions.value[su.sessionId] ?: continue
            val wasRunning = before[su.sessionId]?.running == true ||
                su.sessionId in suppressSnapshot
            awayNotifier.onUpdate(
                su.sessionId,
                state.summary.hostId,
                state.summary.title,
                su.update,
                sessionWasRunning = wasRunning,
            )
        }
        if (result.needsPersist) persistSessions()
        // A turn that ended on its own while nobody has the screen open leaves nothing to track.
        for (su in batch) {
            val done = su.update as? CodeUpdate.TurnDone ?: continue
            if (done.stopReason != "cancelled" && su.sessionId !in viewing) detachIdle(su.sessionId)
        }
    }

    private inline fun update(sessionId: String, f: (CodeSessionState) -> CodeSessionState) {
        val cur = _sessions.value[sessionId] ?: return
        _sessions.value = _sessions.value + (sessionId to f(cur))
    }

    private fun saveHostQuietly(host: CodeHost) {
        val list = _hosts.value.map { if (it.id == host.id) host else it }
        store.hosts = list
        _hosts.value = list
        if (_activeHost.value?.id == host.id) _activeHost.value = host
    }

    /**
     * Persist the session index to Room. Conflates overlapping calls: snapshots on Main,
     * a single IO worker always writes the latest pending list; no concurrent replaceAll.
     */
    private fun persistSessions() {
        if (!_sessionsLoaded.value) {
            // The saved index is still loading; replaceAll now would erase rows not read yet.
            // Flag first, then look again: the loader sets "loaded" before it reads the flag, so
            // one of the two sees the other.
            persistWaitsForLoad = true
            if (!_sessionsLoaded.value) return
        }
        // Refresh lastSeq from live adapters before writing.
        val enriched = _sessions.value.mapValues { (id, state) ->
            val seq = backends[state.summary.hostId]?.peekLastSeq(id) ?: state.summary.lastSeq
            if (seq != state.summary.lastSeq) state.copy(summary = state.summary.copy(lastSeq = seq)) else state
        }
        if (enriched != _sessions.value) _sessions.value = enriched
        queuePersist(enriched)
    }

    private fun queuePersist(sessions: Map<String, CodeSessionState>) {
        val entities = sessions.values
            .map { CodeSessionEntity.from(it.summary) }
            .sortedByDescending { it.updatedAt }
            .take(200)
        pendingPersist.set(entities)
        schedulePersist()
    }

    /** Drain [pendingPersist] with one in-flight replaceAll; re-arm if a newer snapshot arrives. */
    private fun schedulePersist() {
        if (!persistRunning.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val batch = pendingPersist.getAndSet(null) ?: break
                    sessionDao.replaceAll(batch)
                }
            } finally {
                persistRunning.set(false)
                // Race window: a newer snapshot may have landed after our last getAndSet.
                if (pendingPersist.get() != null) schedulePersist()
            }
        }
    }

    internal fun releaseForTesting() {
        sessionsLoad.cancel()
        updatePump.cancel()
        backends.values.forEach { it.close() }
    }

    companion object {
        private const val TAG = "CodeHub"
        /** Max wait for away Allow/Deny to reach the bridge before releasing goAsync (A2). */
        private const val AWAY_ANSWER_TIMEOUT_MS = 8_000L

        private val byApp = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<Context, CodeHub>()
        )

        /** When set, [get] asks this instead of the per-application map. Tests install a fresh hub. */
        @Volatile
        private var installed: ((Context) -> CodeHub)? = null

        /** Tests only: drop every hub so the next [get] starts clean for this process. */
        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            installed = null
            val hubs = byApp.values.toList()
            byApp.clear()
            hubs.forEach { it.releaseForTesting() }
        }

        /**
         * Tests only: the next [get] calls [factory] instead of the per-application map.
         * [resetForTesting] clears it.
         */
        @androidx.annotation.VisibleForTesting
        fun installForTesting(factory: (Context) -> CodeHub) {
            resetForTesting()
            installed = factory
        }

        /**
         * Whether the app is backgrounded, kept here so a hub created later (an away-notification
         * tap, say) starts with the right policy instead of reconnecting in the background.
         */
        @Volatile
        private var appBackgrounded = false

        /**
         * The hub if one exists, without creating it. Creating reads encrypted hosts and opens the
         * database, so app start and lifecycle callbacks must not do it for people who never use Code.
         */
        fun peek(context: Context): CodeHub? {
            if (installed != null) return null
            return byApp[context.applicationContext]
        }

        /** App lifecycle hook: records the state and forwards it to the hub only if one exists. */
        fun noteAppBackgrounded(context: Context, backgrounded: Boolean) {
            appBackgrounded = backgrounded
            peek(context)?.setAppBackgrounded(backgrounded)
        }

        /** Tests only: [get] plus waiting for the saved session index. */
        @androidx.annotation.VisibleForTesting
        fun getLoaded(context: Context): CodeHub = get(context).also { it.awaitSessionsLoaded() }

        fun get(context: Context): CodeHub {
            installed?.let { return it(context) }
            val app = context.applicationContext
            return byApp[app] ?: synchronized(byApp) {
                byApp[app] ?: CodeHub(app).also { byApp[app] = it }
            }
        }
    }
}

/**
 * Soft-merge a remote `bridge/listSessions` row with a previously known local summary.
 * Prefer non-null / non-blank remote fields; keep local [CodeSessionSummary.model],
 * [CodeSessionSummary.lastSeq], preview, and non-ASK permission when the bridge omits them
 * (listSessions often lacks model until it echoes start `_meta.model`).
 * [keepLocalTitle] is set after a phone rename: the bridge's title must not replace it.
 * Otherwise a non-blank remote title wins, so an agent rename shows up on the next refresh (G2).
 */
internal fun mergeListSessionsSummary(
    remote: CodeSessionSummary,
    local: CodeSessionSummary,
    keepLocalTitle: Boolean = true,
): CodeSessionSummary = remote.copy(
    lastSeq = remote.lastSeq ?: local.lastSeq,
    permissionMode = if (remote.permissionMode != PermissionMode.ASK ||
        local.permissionMode == PermissionMode.ASK
    ) remote.permissionMode else local.permissionMode,
    preview = remote.preview.ifBlank { local.preview },
    // G2: a pinned rename survives refresh. An unpinned row takes the bridge title when
    // it sent one, so the agent's name replaces the first line of the prompt.
    title = when {
        keepLocalTitle -> local.title.ifBlank { remote.title }
        remote.title.isBlank() -> local.title
        else -> remote.title
    },
    model = remote.model ?: local.model,
)

/**
 * F2: on wire set_mode failure, revert only when [current] still equals the optimistic
 * [attempted] mode. Returns [previous] to write back, or null when a newer toggle won.
 */
internal fun revertPermissionModeIfCurrent(
    current: PermissionMode,
    attempted: PermissionMode,
    previous: PermissionMode,
): PermissionMode? = if (current == attempted) previous else null
