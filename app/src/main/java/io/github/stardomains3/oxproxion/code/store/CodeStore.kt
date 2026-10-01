package io.github.stardomains3.oxproxion.code.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import io.github.stardomains3.oxproxion.code.CodeHost
import io.github.stardomains3.oxproxion.code.CodeSessionSummary
import io.github.stardomains3.oxproxion.code.PermissionMode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Settings and saved hosts for Code mode.
 * Non-secret prefs (enabled, hosts metadata, defaults) stay in plain SharedPreferences.
 * Session index lives in Room ([io.github.stardomains3.oxproxion.code.CodeSessionDao]); legacy
 * prefs sessions are migrated once via [peekLegacySessions] / [markSessionsMigrated].
 * Pairing tokens live in [CodeHostSecrets] (Keystore AES-GCM); plaintext tokens are migrated
 * out of the hosts JSON on first read.
 */
class CodeStore @androidx.annotation.VisibleForTesting constructor(
    context: Context,
    private val secrets: CodeHostSecrets
) {
    constructor(context: Context) : this(context, CodeHostSecrets(context.applicationContext))

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Code tab on the main screen. Off until Settings > Modes turns it on. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit { putBoolean(KEY_ENABLED, v) }

    /**
     * Calls [onChange] whenever [enabled] flips. SharedPreferences keeps listeners weakly, so
     * the caller must hold the returned handle and pass it to [removeEnabledListener].
     */
    fun addEnabledListener(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == KEY_ENABLED) onChange() }
            .also { prefs.registerOnSharedPreferenceChangeListener(it) }

    fun removeEnabledListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    /** Whether the Code tab was the last one open, so the app comes back to it. */
    var lastTabWasCode: Boolean
        get() = prefs.getBoolean(KEY_LAST_TAB, false)
        set(v) = prefs.edit { putBoolean(KEY_LAST_TAB, v) }

    var activeHostId: String?
        get() = prefs.getString(KEY_ACTIVE_HOST, null)
        set(v) = prefs.edit { putString(KEY_ACTIVE_HOST, v) }

    /** Transcript verbosity: false = Normal (folded), true = Thinking (thoughts and output open). */
    var showThinking: Boolean
        get() = prefs.getBoolean(KEY_SHOW_THINKING, false)
        set(v) = prefs.edit { putBoolean(KEY_SHOW_THINKING, v) }

    var defaultPermissionMode: PermissionMode
        // New sessions run full auto until the user picks something else.
        get() = prefs.getString(KEY_PERMISSION, null)?.let(PermissionMode::fromId)
            ?: PermissionMode.FULL_AUTO
        set(v) = prefs.edit { putString(KEY_PERMISSION, v.id) }

    /**
     * Opt-in local notifications when approval needed / turn finished while the app is away
     * and the bridge WebSocket is still connected (§5.6 local slice). Default off.
     */
    var notifyWhenAway: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_AWAY, false)
        set(v) = prefs.edit { putBoolean(KEY_NOTIFY_AWAY, v) }

    var hosts: List<CodeHost>
        get() {
            migratePlaintextTokensIfNeeded()
            return readHostsRaw().map { host ->
                val vaulted = secrets.getToken(host.id)
                if (vaulted.isNotEmpty()) host.copy(token = vaulted) else host
            }
        }
        set(v) {
            val scrubbed = v.map { host -> persistTokenOrKeepPlain(host) }
            secrets.retainOnly(v.map { it.id }.toSet())
            writeHostsRaw(scrubbed)
        }

    /**
     * Read any session list still in SharedPreferences **without** clearing.
     * Returns null when already migrated (caller should load from Room only).
     * Empty list means "not yet migrated, nothing to import".
     * Call [markSessionsMigrated] only after a successful Room write.
     */
    fun peekLegacySessions(): List<CodeSessionSummary>? {
        if (prefs.getBoolean(KEY_SESSIONS_MIGRATED, false)) return null
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        // A decode failure must throw. Swallowing it used to look like "nothing to import", and the
        // caller then deleted the only copy of the session list.
        return json.decodeFromString(ListSerializer(CodeSessionSummary.serializer()), raw)
    }

    /**
     * Clear prefs sessions and set the migrated flag. Call only after Room upsert/replace
     * succeeds so a crash mid-migration leaves prefs intact for the next launch.
     */
    fun markSessionsMigrated() {
        prefs.edit {
            remove(KEY_SESSIONS)
            putBoolean(KEY_SESSIONS_MIGRATED, true)
        }
    }

    /** Test/debug: whether prefs→Room session migration has run. */
    val sessionsMigratedToRoom: Boolean
        get() = prefs.getBoolean(KEY_SESSIONS_MIGRATED, false)

    /**
     * One-shot: move any pairing tokens still embedded in the hosts JSON into the Keystore vault,
     * then rewrite the JSON with empty token fields. Retries on next read if vault write fails.
     */
    private fun migratePlaintextTokensIfNeeded() {
        if (prefs.getBoolean(KEY_TOKENS_MIGRATED, false)) return
        val raw = readHostsRaw()
        val withTokens = raw.filter { it.token.isNotBlank() }
        if (withTokens.isEmpty()) {
            prefs.edit { putBoolean(KEY_TOKENS_MIGRATED, true) }
            return
        }
        for (host in withTokens) {
            if (!secrets.putToken(host.id, host.token)) {
                Log.w(TAG, "Token migration deferred for host ${host.id}")
                return
            }
        }
        writeHostsRaw(raw.map { it.copy(token = "") })
        prefs.edit { putBoolean(KEY_TOKENS_MIGRATED, true) }
    }

    /** Write token to vault when possible; leave plaintext on the host only if vault write fails. */
    private fun persistTokenOrKeepPlain(host: CodeHost): CodeHost {
        if (host.token.isBlank()) {
            secrets.removeToken(host.id)
            return host.copy(token = "")
        }
        return if (secrets.putToken(host.id, host.token)) host.copy(token = "") else host
    }

    private fun readHostsRaw(): List<CodeHost> =
        prefs.getString(KEY_HOSTS, null)?.let {
            runCatching { json.decodeFromString(ListSerializer(CodeHost.serializer()), it) }.getOrNull()
        } ?: emptyList()

    private fun writeHostsRaw(hosts: List<CodeHost>) {
        prefs.edit {
            putString(KEY_HOSTS, json.encodeToString(ListSerializer(CodeHost.serializer()), hosts))
        }
    }

    companion object {
        private const val TAG = "CodeStore"
        const val PREFS_NAME = "code_mode"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SHOW_THINKING = "transcript_show_thinking"
        private const val KEY_LAST_TAB = "last_tab_code"
        private const val KEY_ACTIVE_HOST = "active_host"
        private const val KEY_PERMISSION = "permission_mode"
        private const val KEY_NOTIFY_AWAY = "notify_when_away"
        private const val KEY_HOSTS = "hosts"
        const val KEY_SESSIONS = "sessions"
        private const val KEY_SESSIONS_MIGRATED = "sessions_migrated_to_room"
        private const val KEY_TOKENS_MIGRATED = "host_tokens_migrated"
    }
}
