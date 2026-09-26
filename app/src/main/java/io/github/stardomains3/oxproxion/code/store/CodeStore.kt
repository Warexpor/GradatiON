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
 * Non-secret prefs (enabled, hosts metadata, sessions, defaults) stay in plain SharedPreferences.
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

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit { putBoolean(KEY_ENABLED, v) }

    /** Whether the Code tab was the last one open, so the app comes back to it. */
    var lastTabWasCode: Boolean
        get() = prefs.getBoolean(KEY_LAST_TAB, false)
        set(v) = prefs.edit { putBoolean(KEY_LAST_TAB, v) }

    var activeHostId: String?
        get() = prefs.getString(KEY_ACTIVE_HOST, null)
        set(v) = prefs.edit { putString(KEY_ACTIVE_HOST, v) }

    var defaultPermissionMode: PermissionMode
        get() = PermissionMode.fromId(prefs.getString(KEY_PERMISSION, null))
        set(v) = prefs.edit { putString(KEY_PERMISSION, v.id) }

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

    var sessions: List<CodeSessionSummary>
        get() = prefs.getString(KEY_SESSIONS, null)?.let {
            runCatching { json.decodeFromString(ListSerializer(CodeSessionSummary.serializer()), it) }.getOrNull()
        } ?: emptyList()
        set(v) = prefs.edit {
            putString(
                KEY_SESSIONS,
                json.encodeToString(ListSerializer(CodeSessionSummary.serializer()), v.take(200))
            )
        }

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
        private const val KEY_LAST_TAB = "last_tab_code"
        private const val KEY_ACTIVE_HOST = "active_host"
        private const val KEY_PERMISSION = "permission_mode"
        private const val KEY_HOSTS = "hosts"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_TOKENS_MIGRATED = "host_tokens_migrated"
    }
}
