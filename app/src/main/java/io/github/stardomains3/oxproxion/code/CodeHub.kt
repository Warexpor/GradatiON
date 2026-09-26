package io.github.stardomains3.oxproxion.code

import android.content.Context
import io.github.stardomains3.oxproxion.code.store.CodeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Session as the UI sees it: summary plus live state. */
data class CodeSessionState(
    val summary: CodeSessionSummary,
    val events: List<CodeEvent> = emptyList(),
    val running: Boolean = false,
    val attached: Boolean = false
) {
    val status: SessionStatus get() = TranscriptReducer.statusOf(events, running)
}

/**
 * App-wide owner of Code mode state: hosts, one backend per host, sessions and their live
 * transcripts. Outlives fragments so a running agent keeps streaming while the user reads chat.
 * Main-thread confined (all mutation happens on [scope], which runs on Main).
 */
class CodeHub private constructor(context: Context) {

    val store = CodeStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backends = HashMap<String, CodeBackend>()

    private val _hosts = MutableStateFlow(store.hosts)
    val hosts: StateFlow<List<CodeHost>> = _hosts

    private val _activeHost = MutableStateFlow(resolveActive())
    val activeHost: StateFlow<CodeHost?> = _activeHost

    private val _sessions = MutableStateFlow(store.sessions.associate { it.id to CodeSessionState(it) })
    /** All known sessions by id. */
    val sessions: StateFlow<Map<String, CodeSessionState>> = _sessions

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = _connection

    private val _connections = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    /** Connection state per host id (multiple hosts can be up at once). */
    val connections: StateFlow<Map<String, ConnectionState>> = _connections

    private fun resolveActive(): CodeHost? {
        val all = store.hosts
        return all.find { it.id == store.activeHostId } ?: all.firstOrNull()
    }

    // ── hosts ─────────────────────────────────────────────────────────────────────────────

    fun saveHost(host: CodeHost) {
        val list = _hosts.value.toMutableList()
        val i = list.indexOfFirst { it.id == host.id }
        if (i >= 0) list[i] = host else list += host
        store.hosts = list
        _hosts.value = list
        backends.remove(host.id)?.close()
        if (_activeHost.value == null || _activeHost.value?.id == host.id) selectHost(host.id)
    }

    fun removeHost(id: String) {
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
            TransportKind.BRIDGE -> BridgeBackend(host, WebSocketTransport(host.url, host.token), AcpAdapter(), scope)
        }
        scope.launch { b.updates.collect { apply(it) } }
        scope.launch {
            b.connection.collect { st ->
                _connections.value = _connections.value + (host.id to st)
                if (_activeHost.value?.id == host.id) _connection.value = st
            }
        }
        b
    }

    fun connect(target: CodeHost? = null) {
        val host = target ?: _activeHost.value ?: return
        val b = backendFor(host)
        _connection.value = b.connection.value
        b.connect()
        refreshSessions()
    }

    fun lastError(): String? = _activeHost.value?.let { backends[it.id]?.lastError }

    /** Pause bridge reconnect while backgrounded unless a session turn is in flight. */
    fun setAppBackgrounded(backgrounded: Boolean) {
        backends.values.forEach { it.setAppBackgrounded(backgrounded) }
    }

    fun refreshSessions() {
        val host = _activeHost.value ?: return
        val b = backendFor(host)
        scope.launch {
            val remote = runCatching { b.listSessions() }.getOrNull() ?: return@launch
            val map = _sessions.value.toMutableMap()
            remote.forEach { s -> map[s.id] = map[s.id]?.copy(summary = s) ?: CodeSessionState(s) }
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

    fun attach(sessionId: String) {
        val s = _sessions.value[sessionId] ?: return
        if (s.attached) return
        update(sessionId) { it.copy(attached = true) }
        val host = _hosts.value.find { it.id == s.summary.hostId } ?: return
        scope.launch { runCatching { backendFor(host).attach(s.summary) } }
    }

    /** Starts a prompt when the session is idle; returns false for an overlapping prompt. */
    fun prompt(sessionId: String, text: String): Boolean {
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
        withBackend(sessionId) { b -> b.prompt(sessionId, text) }
        return true
    }

    fun answer(sessionId: String, requestId: String, option: ApprovalOption?) = withBackend(sessionId) { it.answer(sessionId, requestId, option) }

    fun cancel(sessionId: String) = withBackend(sessionId) { it.cancel(sessionId) }

    fun setPermissionMode(sessionId: String, mode: PermissionMode) {
        update(sessionId) { it.copy(summary = it.summary.copy(permissionMode = mode)) }
        persistSessions()
        withBackend(sessionId) { runCatching { it.setPermissionMode(sessionId, mode) } }
    }

    fun forget(sessionId: String) {
        _sessions.value = _sessions.value - sessionId
        persistSessions()
    }

    private fun withBackend(sessionId: String, block: suspend (CodeBackend) -> Unit) {
        val s = _sessions.value[sessionId] ?: return
        val host = _hosts.value.find { it.id == s.summary.hostId } ?: return
        scope.launch { block(backendFor(host)) }
    }

    private fun apply(u: SessionUpdate) {
        update(u.sessionId) { s ->
            val events = TranscriptReducer.apply(s.events, u.update)
            val running = when (val upd = u.update) {
                is CodeUpdate.TurnDone -> false
                is CodeUpdate.TextChunk, is CodeUpdate.ToolPatch -> true
                is CodeUpdate.Upsert -> if (upd.event is CodeEvent.UserPrompt) true else s.running
                is CodeUpdate.SessionInfo -> when (upd.status) {
                    SessionStatus.RUNNING, SessionStatus.NEEDS_APPROVAL -> true
                    SessionStatus.IDLE, SessionStatus.ERROR -> false
                    else -> s.running
                }
                else -> s.running
            }
            val fromText = (events.lastOrNull { it is CodeEvent.AgentText } as? CodeEvent.AgentText)
                ?.text?.lineSequence()?.lastOrNull { it.isNotBlank() }?.replace(MARKDOWN_MARKS, "")?.trim()?.take(140)
            val summary = when (val upd = u.update) {
                is CodeUpdate.SessionInfo -> s.summary.copy(
                    updatedAt = System.currentTimeMillis(),
                    title = upd.title ?: s.summary.title,
                    preview = upd.preview ?: fromText ?: s.summary.preview,
                    branch = upd.branch ?: s.summary.branch
                )
                is CodeUpdate.Title -> s.summary.copy(
                    updatedAt = System.currentTimeMillis(),
                    title = upd.title
                )
                else -> s.summary.copy(
                    updatedAt = System.currentTimeMillis(),
                    preview = fromText ?: s.summary.preview
                )
            }
            s.copy(events = events, running = running, summary = summary)
        }
        if (u.update is CodeUpdate.TurnDone || u.update is CodeUpdate.SessionInfo) persistSessions()
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

    private fun persistSessions() {
        store.sessions = _sessions.value.values.map { it.summary }.sortedByDescending { it.updatedAt }
    }

    companion object {
        /** Markdown punctuation stripped from list previews. */
        private val MARKDOWN_MARKS = Regex("[`*_#>]+")

        @Volatile
        private var instance: CodeHub? = null

        /** Tests only: drop the singleton so the next [get] starts from fresh preferences. */
        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            instance?.backends?.values?.forEach { it.close() }
            instance = null
        }

        fun get(context: Context): CodeHub =
            instance ?: synchronized(this) { instance ?: CodeHub(context.applicationContext).also { instance = it } }
    }
}
