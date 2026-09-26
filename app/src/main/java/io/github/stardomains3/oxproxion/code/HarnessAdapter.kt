package io.github.stardomains3.oxproxion.code

import kotlinx.serialization.json.JsonElement

/**
 * A change to one session's transcript, produced by an adapter from a wire frame. The
 * [TranscriptReducer] folds these into the list the UI renders, so adapters never touch UI state.
 */
sealed class CodeUpdate {
    /** Insert, or replace the event with the same key. */
    data class Upsert(val event: CodeEvent) : CodeUpdate()

    /** Streamed prose: appended to the open agent message (or thought) with [key]. */
    data class TextChunk(val key: String, val chunk: String, val thought: Boolean = false) : CodeUpdate()

    /** Partial update of a tool call; null fields keep their value. */
    data class ToolPatch(
        val callId: String,
        val status: ToolStatus? = null,
        val title: String? = null,
        val detail: String? = null,
        val output: String? = null
    ) : CodeUpdate()

    /** An approval was answered, here or elsewhere. */
    data class ApprovalAnswered(val requestId: String, val chosen: ApprovalOption.Kind) : CodeUpdate()

    data class TurnDone(val stopReason: String, val summary: String? = null) : CodeUpdate()

    data class Title(val title: String) : CodeUpdate()
}

/** What an adapter decodes one inbound frame into. */
sealed class AdapterOutput {
    /** [seq] is the bridge `_meta.seq` when present (resume / idempotent replay). */
    data class Update(val sessionId: String, val update: CodeUpdate, val seq: Long? = null) : AdapterOutput()
    /** Reply to a request we sent (matched by JSON-RPC id). */
    data class Result(val id: Long, val result: JsonElement?, val error: String?) : AdapterOutput()
    /** Something the phone can't use (yet). Logged, never shown. */
    data class Ignored(val reason: String) : AdapterOutput()
}

/**
 * Translates between one wire protocol and the core model. Pluggable per harness family:
 *
 * - [AcpAdapter]: Agent Client Protocol (JSON-RPC). The bridge speaks it for OpenCode (`opencode acp`),
 *   Grok Build (`grok agent stdio`), Cursor CLI (`agent acp`), Pi (`pi-acp`), and the published ACP
 *   wrappers for Claude Code and Codex. This is the
 *   default and the only one the phone strictly needs when a bridge is in the middle.
 * - Direct adapters (planned, optional): OpenCode's HTTP/SSE server, Codex app-server, Claude
 *   Agent SDK stream-json. Only for setups without the bridge.
 *
 * Adapters are pure: frames in, [AdapterOutput] out, frames to send returned as strings. That
 * keeps them unit-testable without a socket (see CodeAcpAdapterTest).
 */
interface HarnessAdapter {
    /** Protocol label sent to the bridge in the handshake, e.g. "acp/1". */
    val protocol: String

    fun initialize(id: Long): String
    fun newSession(id: Long, request: NewSessionRequest): String
    /** [afterSeq]: bridge replays only notifications with seq greater than this (null = full history). */
    fun loadSession(id: Long, sessionId: String, workspace: String, afterSeq: Long? = null): String
    fun prompt(id: Long, sessionId: String, text: String): String
    fun cancel(sessionId: String): String
    fun setMode(id: Long, sessionId: String, mode: PermissionMode): String
    /** Answers an agent-initiated permission request ([CodeEvent.Approval.requestId]). */
    fun answerApproval(requestId: String, optionId: String?): String

    /** Bridge extensions (not part of ACP): list sessions / workspaces / installed harnesses. */
    fun listSessions(id: Long): String
    fun listWorkspaces(id: Long, harness: HarnessKind): String

    fun decode(frame: String): List<AdapterOutput>
}

/** Folds [CodeUpdate]s into a transcript. Pure and allocation-light: one list copy per update. */
object TranscriptReducer {

    fun apply(list: List<CodeEvent>, update: CodeUpdate, now: Long = System.currentTimeMillis()): List<CodeEvent> =
        when (update) {
            is CodeUpdate.Upsert -> upsert(list, update.event)
            is CodeUpdate.TextChunk -> {
                val i = list.indexOfLast { it.key == update.key }
                if (i < 0) {
                    list + if (update.thought) CodeEvent.Thought(update.key, now, update.chunk)
                    else CodeEvent.AgentText(update.key, now, update.chunk, streaming = true)
                } else {
                    val merged = when (val e = list[i]) {
                        is CodeEvent.AgentText -> e.copy(text = e.text + update.chunk, streaming = true)
                        is CodeEvent.Thought -> e.copy(text = e.text + update.chunk)
                        else -> e
                    }
                    list.toMutableList().also { it[i] = merged }
                }
            }
            is CodeUpdate.ToolPatch -> {
                val i = list.indexOfLast { it is CodeEvent.ToolCall && it.callId == update.callId }
                if (i < 0) list else {
                    val t = list[i] as CodeEvent.ToolCall
                    list.toMutableList().also {
                        it[i] = t.copy(
                            status = update.status ?: t.status,
                            title = update.title ?: t.title,
                            detail = update.detail ?: t.detail,
                            output = update.output ?: t.output
                        )
                    }
                }
            }
            is CodeUpdate.ApprovalAnswered -> list.map {
                if (it is CodeEvent.Approval && it.requestId == update.requestId) it.copy(chosen = update.chosen) else it
            }
            is CodeUpdate.TurnDone -> list.map {
                if (it is CodeEvent.AgentText && it.streaming) it.copy(streaming = false) else it
            } + CodeEvent.TurnEnd("turn:$now", now, update.stopReason, update.summary)
            is CodeUpdate.Title -> list
        }

    private fun upsert(list: List<CodeEvent>, e: CodeEvent): List<CodeEvent> {
        val i = list.indexOfLast { it.key == e.key }
        return if (i < 0) list + e else list.toMutableList().also { it[i] = e }
    }

    /** Session status as the list and header show it, derived from the transcript tail. */
    fun statusOf(list: List<CodeEvent>, running: Boolean): SessionStatus = when {
        list.any { it is CodeEvent.Approval && it.chosen == null } -> SessionStatus.NEEDS_APPROVAL
        running -> SessionStatus.RUNNING
        list.lastOrNull() is CodeEvent.Notice && (list.last() as CodeEvent.Notice).level == NoticeLevel.ERROR -> SessionStatus.ERROR
        else -> SessionStatus.IDLE
    }
}
