package io.github.stardomains3.oxproxion.code

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable

/*
 * Code mode core model. Harness-agnostic: every harness (Claude Code, Codex, OpenCode, Grok Build,
 * Cursor Agent, Pi, anything speaking ACP) is mapped onto these few concepts by a [HarnessAdapter],
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
    /** Wire id stays `cursor-cli`. The product name is Cursor Agent (`cursor-agent` / `agent acp`). */
    CURSOR_CLI("cursor-cli", "Cursor Agent", "Cursor"),
    PI("pi", "Pi", "Pi"),
    CUSTOM("custom", "Custom agent", "Agent");

    companion object {
        /**
         * Ids a bridge may report for [CURSOR_CLI] besides the canonical wire id `cursor-cli`.
         * Outbound frames still send [CURSOR_CLI.id].
         */
        private val CURSOR_ALIASES = setOf("cursor-agent", "cursor")

        /**
         * Case and underscores do not matter (`Claude_Code`, `CURSOR-AGENT`).
         * `agent` stays [CUSTOM]: that word is a permission mode, not a harness.
         * Outbound frames still send [HarnessKind.id].
         */
        fun fromId(id: String?): HarnessKind {
            val key = id?.trim()?.lowercase()?.replace('_', '-')
                ?.replace(Regex("-+"), "-")
                ?.takeIf { it.isNotEmpty() }
                ?: return CUSTOM
            entries.find { it.id == key }?.let { return it }
            return if (key in CURSOR_ALIASES) CURSOR_CLI else CUSTOM
        }
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

    /**
     * Label in pickers and machine detail. Cursor Agent always uses [HarnessKind.displayName]
     * so a bridge that still sends the old "Cursor CLI" string does not keep that name.
     * Other harnesses keep the name the bridge sent.
     */
    val label: String
        get() = if (kind == HarnessKind.CURSOR_CLI) kind.displayName else name
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
    /** Everything goes through. Default for new sessions (user's choice). */
    FULL_AUTO("full-auto");

    companion object {
        fun fromId(id: String?): PermissionMode = entries.find { it.id == id } ?: ASK

        /**
         * ACP `currentModeId` / `modeId`, or null when it is not a mode this phone shows.
         * Unknown ids stay null so a foreign mode does not snap the pill back to Ask.
         * Accepts this app's ids and the ones Claude Code / Codex ACP publish.
         */
        fun fromAcpModeId(id: String?): PermissionMode? {
            val key = id?.trim()?.lowercase()?.replace('_', '-') ?: return null
            return when (key) {
                "ask", "default" -> ASK
                "auto-edit", "acceptedits" -> AUTO_EDIT
                "plan" -> PLAN
                // Cursor Agent's `agent` mode is full tool access, the same pill as full auto.
                "full-auto", "bypasspermissions", "dontask", "agent" -> FULL_AUTO
                else -> entries.find { it.id == key }
            }
        }
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

/**
 * One image (or future media) attachment for [session/prompt] / [NewSessionRequest].
 * [data] is raw base64 (no data-URI prefix); [mimeType] is e.g. `image/jpeg`.
 * [previewUri] is a local content Uri string for composer chips only — never sent on the wire.
 * [previewBitmap] is a small chip thumbnail decoded on IO; body property so it stays out of
 * equals/hashCode/copy (wire identity is mime+data only).
 */
data class PromptAttachment(
    val mimeType: String,
    val data: String,
    val previewUri: String? = null,
) {
    /** Small chip thumbnail (IO-decoded); never sent on the wire. */
    var previewBitmap: android.graphics.Bitmap? = null
}

data class NewSessionRequest(
    val hostId: String,
    val harness: HarnessKind,
    val workspace: String,
    val prompt: String,
    val permissionMode: PermissionMode,
    val model: String? = null,
    val attachments: List<PromptAttachment> = emptyList(),
)

enum class ToolKind { READ, EDIT, EXECUTE, SEARCH, FETCH, THINK, DELETE, MOVE, OTHER }
enum class ToolStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }
enum class PlanStatus { PENDING, IN_PROGRESS, COMPLETED, CANCELLED }
enum class NoticeLevel { INFO, WARNING, ERROR }

data class PlanEntry(val content: String, val status: PlanStatus, val id: String? = null)

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

    companion object {
        /**
         * Stand-in id for the "Deny" the phone adds when a request arrives without options.
         * It answers with outcome `cancelled` instead of naming an option.
         */
        const val CANCEL_ID = ""
    }
}

/**
 * One inline image from an ACP `agent_message_chunk` content block (`type: image`).
 * [data] is raw base64 (no data-URI prefix); [mimeType] e.g. `image/png`.
 * MVP: data+mime only — no remote URIs.
 * [cacheKey] must uniquely identify this image block (message key + index); see
 * [CodePromptImages.inlineCacheKey]. Never derive it from a head/tail base64 sample alone.
 */
data class AgentInlineImage(
    val mimeType: String,
    val data: String,
    val cacheKey: String,
)

/**
 * Everything the transcript can show. [key] is stable across updates of the same thing (a tool
 * call's status changing, text streaming in), so the list can diff and animate cheaply.
 */
sealed class CodeEvent {
    abstract val key: String
    abstract val at: Long

    data class UserPrompt(
        override val key: String,
        override val at: Long,
        val text: String,
        /** Count of image attachments sent with this prompt (wire payload is not stored). */
        val attachmentCount: Int = 0,
    ) : CodeEvent()

    /** Agent prose (markdown). Chunks for the same message are merged by the controller.
     *  [images] are inline ACP `type: image` blocks (base64 + mime); decoded in the transcript UI.
     */
    data class AgentText(
        override val key: String,
        override val at: Long,
        val text: String,
        val streaming: Boolean = false,
        val images: List<AgentInlineImage> = emptyList(),
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
        val chosen: ApprovalOption.Kind? = null,
        /** The turn ended while this was unanswered; the agent no longer waits, so no buttons. */
        val expired: Boolean = false
    ) : CodeEvent() {
        /** Still waiting on the user. */
        val pending: Boolean get() = chosen == null && !expired
    }

    data class Plan(override val key: String, override val at: Long, val entries: List<PlanEntry>) : CodeEvent()

    /**
     * [textRes] (with [args]) wins over [text] when set, so notices the phone makes up follow the
     * app language; [text] carries wire messages verbatim and is the fallback.
     */
    data class Notice(
        override val key: String,
        override val at: Long,
        val text: String,
        val level: NoticeLevel = NoticeLevel.INFO,
        @StringRes val textRes: Int = 0,
        val args: List<String> = emptyList()
    ) : CodeEvent()

    /** End of an agent turn: stop reason plus optional cost/usage line. */
    data class TurnEnd(
        override val key: String,
        override val at: Long,
        val stopReason: String,
        val summary: String? = null,
        val usage: TurnUsage? = null,
    ) : CodeEvent()
}

/** Token and cost counts a bridge may attach to a finished turn. Absent fields were not reported. */
data class TurnUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val costUsd: Double? = null,
)
