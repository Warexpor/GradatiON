package io.github.stardomains3.oxproxion.code

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Settings and saved hosts for Code mode. Plain SharedPreferences + JSON for the foundation;
 * the plan moves tokens to Keystore-encrypted storage and sessions/transcripts to Room.
 */
class CodeStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("code_mode", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Whether the Code tab was the last one open, so the app comes back to it. */
    var lastTabWasCode: Boolean
        get() = prefs.getBoolean(KEY_LAST_TAB, false)
        set(v) = prefs.edit().putBoolean(KEY_LAST_TAB, v).apply()

    var activeHostId: String?
        get() = prefs.getString(KEY_ACTIVE_HOST, null)
        set(v) = prefs.edit().putString(KEY_ACTIVE_HOST, v).apply()

    var defaultPermissionMode: PermissionMode
        get() = PermissionMode.fromId(prefs.getString(KEY_PERMISSION, null))
        set(v) = prefs.edit().putString(KEY_PERMISSION, v.id).apply()

    var hosts: List<CodeHost>
        get() = prefs.getString(KEY_HOSTS, null)?.let {
            runCatching { json.decodeFromString(ListSerializer(CodeHost.serializer()), it) }.getOrNull()
        } ?: emptyList()
        set(v) = prefs.edit().putString(KEY_HOSTS, json.encodeToString(ListSerializer(CodeHost.serializer()), v)).apply()

    var sessions: List<CodeSessionSummary>
        get() = prefs.getString(KEY_SESSIONS, null)?.let {
            runCatching { json.decodeFromString(ListSerializer(CodeSessionSummary.serializer()), it) }.getOrNull()
        } ?: emptyList()
        set(v) = prefs.edit().putString(KEY_SESSIONS, json.encodeToString(ListSerializer(CodeSessionSummary.serializer()), v.take(200))).apply()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_TAB = "last_tab_code"
        const val KEY_ACTIVE_HOST = "active_host"
        const val KEY_PERMISSION = "permission_mode"
        const val KEY_HOSTS = "hosts"
        const val KEY_SESSIONS = "sessions"
    }
}

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
        scope.launch { b.connection.collect { if (_activeHost.value?.id == host.id) _connection.value = it } }
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

    fun prompt(sessionId: String, text: String) = withBackend(sessionId) { b ->
        update(sessionId) { it.copy(running = true) }
        b.prompt(sessionId, text)
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
            val running = when (u.update) {
                is CodeUpdate.TurnDone -> false
                is CodeUpdate.TextChunk, is CodeUpdate.ToolPatch -> true
                is CodeUpdate.Upsert -> if (u.update.event is CodeEvent.UserPrompt) true else s.running
                else -> s.running
            }
            val preview = (events.lastOrNull { it is CodeEvent.AgentText } as? CodeEvent.AgentText)
                ?.text?.lineSequence()?.lastOrNull { it.isNotBlank() }?.replace(MARKDOWN_MARKS, "")?.trim()?.take(140) ?: s.summary.preview
            s.copy(
                events = events,
                running = running,
                summary = s.summary.copy(updatedAt = System.currentTimeMillis(), preview = preview)
            )
        }
        if (u.update is CodeUpdate.TurnDone) persistSessions()
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
