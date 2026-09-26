package io.github.stardomains3.oxproxion.code

import kotlinx.serialization.Serializable

/*
 * Code mode core model. Harness-agnostic: every harness (Claude Code, Codex, OpenCode, Grok Build,
 * Cursor CLI, Pi, anything speaking ACP) is mapped onto these few concepts by a [HarnessAdapter],
 * and the UI only ever renders these. See docs/code-mode-plan.md for the full design.
 *
 *   CodeHost     a machine the user controls, reached through a bridge (or directly)
 *   Harness      a coding agent installed on that machine
 *   CodeSession  one agent conversation, bound to a host, a harness and a workspace folder
 *   CodeEvent    everything that happens in a session: prompts, text, tool calls, diffs,
 *                approval requests, plan updates, notices, turn ends
 */

/** Coding agents GradatiON knows how to label. The wire protocol never depends on this list. */
@Serializable
enum class HarnessKind(val id: String, val displayName: String, val shortName: String) {
    CLAUDE_CODE("claude-code", "Claude Code", "Claude"),
    CODEX("codex", "Codex CLI", "Codex"),
    OPENCODE("opencode", "OpenCode", "OpenCode"),
    GROK_BUILD("grok-build", "Grok Build", "Grok"),
    CURSOR_CLI("cursor-cli", "Cursor CLI", "Cursor"),
    PI("pi", "Pi", "Pi"),
    CUSTOM("custom", "Custom agent", "Agent");

    companion object {
        fun fromId(id: String?): HarnessKind = entries.find { it.id == id } ?: CUSTOM
    }
}

/**
 * One harness reported by the bridge (`bridge/listHarnesses`). [kind] maps [id] onto the local
 * [HarnessKind] enum (unknown ids become [HarnessKind.CUSTOM]).
 */
data class HarnessInfo(
    val id: String,
    val name: String,
    val available: Boolean,
    val models: List<String> = emptyList()
) {
    val kind: HarnessKind get() = HarnessKind.fromId(id)
}

/** One entry from `bridge/browse` (folder picker). */
data class BrowseEntry(val name: String, val dir: Boolean)

/** One changed path from `bridge/gitStatus` (`status` is porcelain XY, e.g. " M", "??"). */
data class GitFileStatus(val path: String, val status: String)

/** Result of `bridge/gitStatus` for a session workspace. */
data class GitStatusResult(
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val files: List<GitFileStatus>
)

/** Result of `bridge/diff` for one path. */
data class GitDiffResult(val unified: String)

/** How the phone reaches a host. Only [BRIDGE] is designed in detail; [DEMO] is in-process. */
@Serializable
enum class TransportKind { BRIDGE, DEMO }

/**
 * A machine the user controls. The phone talks to a small bridge daemon on it (or on a relay in
 * front of it) over a WebSocket; the bridge launches and supervises the harnesses.
 */
@Serializable
data class CodeHost(
    val id: String,
    val name: String,
    /** ws:// or wss:// URL of the bridge, e.g. wss://laptop.tailnet.ts.net:7878/v1 */
    val url: String = "",
    /** Pairing token. Persisted in Keystore-backed [io.github.stardomains3.oxproxion.code.store.CodeHostSecrets], not in plain prefs. */
    val token: String = "",
    /**
     * SHA-256 of the bridge leaf certificate from the pairing QR (`fp=`).
     * Non-blank → [BridgeTls] pins it on the WebSocket; blank → legacy system-CA trust.
     */
    val fingerprint: String = "",
    val transport: TransportKind = TransportKind.BRIDGE,
    val defaultHarness: HarnessKind = HarnessKind.CLAUDE_CODE,
    val defaultWorkspace: String = "",
    /** Workspaces the user has picked before on this host, most recent first. */
    val recentWorkspaces: List<String> = emptyList()
) {
    val isDemo get() = transport == TransportKind.DEMO
}

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

/** Who may do what without asking. Mirrors the permission modes most harnesses expose. */
@Serializable
enum class PermissionMode(val id: String) {
    /** Ask before every edit and command. */
    ASK("ask"),
    /** Edits go through, commands still ask. */
    AUTO_EDIT("auto-edit"),
    /** Read-only planning; the agent proposes, nothing is written. */
    PLAN("plan"),
    /** Everything goes through. Dangerous, off by default. */
    FULL_AUTO("full-auto");

    companion object {
        fun fromId(id: String?): PermissionMode = entries.find { it.id == id } ?: ASK
    }
}

enum class SessionStatus {
    /** Waiting for the user's next prompt. */
    IDLE,
    /** The agent is working on a turn. */
    RUNNING,
    /** The agent is blocked on an approval. */
    NEEDS_APPROVAL,
    /** Last turn ended with an error. */
    ERROR,
    /** The host is unreachable; the session may still be alive on the machine. */
    OFFLINE
}

@Serializable
data class CodeSessionSummary(
    val id: String,
    val hostId: String,
    val harness: HarnessKind,
    val workspace: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val permissionMode: PermissionMode = PermissionMode.ASK,
    val model: String? = null,
    /** One line for the list: last agent sentence, or what it is doing now. */
    val preview: String = "",
    val branch: String? = null,
    /** Highest bridge `_meta.seq` seen; used for `session/load` resume after process death. */
    val lastSeq: Long? = null
)

data class NewSessionRequest(
    val hostId: String,
    val harness: HarnessKind,
    val workspace: String,
    val prompt: String,
    val permissionMode: PermissionMode,
    val model: String? = null
)

enum class ToolKind { READ, EDIT, EXECUTE, SEARCH, FETCH, THINK, DELETE, MOVE, OTHER }
enum class ToolStatus { PENDING, RUNNING, COMPLETED, FAILED }
enum class PlanStatus { PENDING, IN_PROGRESS, COMPLETED }
enum class NoticeLevel { INFO, WARNING, ERROR }

data class PlanEntry(val content: String, val status: PlanStatus)

/**
 * One slash command from ACP `available_commands_update` ([name] without the leading `/`).
 * [inputHint] is the unstructured input hint when the command takes arguments.
 */
data class AvailableCommand(
    val name: String,
    val description: String,
    val inputHint: String? = null,
)

/** Option shown on an approval card. [kind] tells the UI which button is which. */
data class ApprovalOption(val id: String, val label: String, val kind: Kind) {
    enum class Kind { ALLOW_ONCE, ALLOW_ALWAYS, REJECT_ONCE, REJECT_ALWAYS }
}

/**
 * Everything the transcript can show. [key] is stable across updates of the same thing (a tool
 * call's status changing, text streaming in), so the list can diff and animate cheaply.
 */
sealed class CodeEvent {
    abstract val key: String
    abstract val at: Long

    data class UserPrompt(override val key: String, override val at: Long, val text: String) : CodeEvent()

    /** Agent prose (markdown). Chunks for the same message are merged by the controller. */
    data class AgentText(
        override val key: String,
        override val at: Long,
        val text: String,
        val streaming: Boolean = false
    ) : CodeEvent()

    /** Reasoning summary, collapsed by default. */
    data class Thought(override val key: String, override val at: Long, val text: String) : CodeEvent()

    data class ToolCall(
        override val key: String,
        override val at: Long,
        val callId: String,
        val kind: ToolKind,
        val title: String,
        /** Command line, file path or query, shown in mono under the title. */
        val detail: String? = null,
        val status: ToolStatus = ToolStatus.PENDING,
        /** Terminal output or result text; card shows last N lines, full viewer shows all stored. */
        val output: String? = null
    ) : CodeEvent()

    data class FileDiff(
        override val key: String,
        override val at: Long,
        val callId: String?,
        val path: String,
        val lines: List<DiffLine>,
        val added: Int,
        val removed: Int,
        val isNewFile: Boolean = false
    ) : CodeEvent()

    data class Approval(
        override val key: String,
        override val at: Long,
        val requestId: String,
        val callId: String?,
        val title: String,
        val detail: String? = null,
        val kind: ToolKind = ToolKind.OTHER,
        val options: List<ApprovalOption>,
        /** Set once answered (here or on another device); the card then collapses. */
        val chosen: ApprovalOption.Kind? = null
    ) : CodeEvent()

    data class Plan(override val key: String, override val at: Long, val entries: List<PlanEntry>) : CodeEvent()

    data class Notice(
        override val key: String,
        override val at: Long,
        val text: String,
        val level: NoticeLevel = NoticeLevel.INFO
    ) : CodeEvent()

    /** End of an agent turn: stop reason plus optional cost/usage line. */
    data class TurnEnd(
        override val key: String,
        override val at: Long,
        val stopReason: String,
        val summary: String? = null
    ) : CodeEvent()
}
