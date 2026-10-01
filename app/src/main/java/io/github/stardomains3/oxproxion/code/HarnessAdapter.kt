package io.github.stardomains3.oxproxion.code

import android.util.Log
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

    /**
     * Inline image for the open agent message with [key] (ACP `agent_message_chunk` `type: image`).
     * Merged into [CodeEvent.AgentText.images]; [data] is raw base64, [mimeType] e.g. `image/png`.
     */
    data class ImageChunk(val key: String, val mimeType: String, val data: String) : CodeUpdate()

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

    /** Bridge `bridge/sessionStatus`: list preview / running flag without a transcript event. */
    data class SessionInfo(
        val status: SessionStatus? = null,
        val title: String? = null,
        val preview: String? = null,
        val branch: String? = null
    ) : CodeUpdate()

    /** ACP `available_commands_update`: slash commands for the composer picker. */
    data class AvailableCommands(val commands: List<AvailableCommand>) : CodeUpdate()
}

/** What an adapter decodes one inbound frame into. */
sealed class AdapterOutput {
    /** [seq] is the bridge `_meta.seq` when present (resume / idempotent replay). */
    data class Update(val sessionId: String, val update: CodeUpdate, val seq: Long? = null) : AdapterOutput()
    /** Reply to a request we sent (matched by JSON-RPC id). */
    data class Result(val id: Long, val result: JsonElement?, val error: String?) : AdapterOutput()
    /**
     * Frames for [sessionId] went missing: the next seq seen jumped past `afterSeq + 1` (the transport
     * drops its oldest frames when a collector falls far behind). Ask the bridge to replay from
     * [afterSeq]; the adapter drops replayed frames it already delivered.
     */
    data class Gap(val sessionId: String, val afterSeq: Long) : AdapterOutput()
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
    fun prompt(id: Long, sessionId: String, text: String, attachments: List<PromptAttachment> = emptyList()): String
    fun cancel(sessionId: String): String
    fun setMode(id: Long, sessionId: String, mode: PermissionMode): String
    /** Answers an agent-initiated permission request ([CodeEvent.Approval.requestId]). */
    fun answerApproval(requestId: String, optionId: String?): String

    /** Bridge extensions (not part of ACP): list sessions / workspaces / harnesses / browse / git. */
    fun listSessions(id: Long): String
    fun listWorkspaces(id: Long, harness: HarnessKind): String
    fun listHarnesses(id: Long): String
    fun browse(id: Long, path: String): String
    fun gitStatus(id: Long, sessionId: String): String
    fun diff(id: Long, sessionId: String, path: String): String

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
            is CodeUpdate.ImageChunk -> {
                val i = list.indexOfLast { it.key == update.key }
                val index = when {
                    i < 0 -> 0
                    else -> (list[i] as? CodeEvent.AgentText)?.images?.size ?: 0
                }
                // Block identity owns the LruCache slot — distinct payloads never collide
                // even when mime/length/head+tail samples match (IMAGE-01).
                val img = AgentInlineImage(
                    mimeType = update.mimeType,
                    data = update.data,
                    cacheKey = CodePromptImages.inlineCacheKey(
                        blockId = "${update.key}#$index",
                        mimeType = update.mimeType,
                        dataLength = update.data.length,
                    ),
                )
                if (i < 0) {
                    list + CodeEvent.AgentText(
                        update.key, now, "", streaming = true, images = listOf(img),
                    )
                } else {
                    val e = list[i]
                    if (e !is CodeEvent.AgentText) list
                    else {
                        val images = if (e.images.size >= MAX_AGENT_IMAGES) {
                            Log.d("TranscriptReducer", "drop agent image over cap $MAX_AGENT_IMAGES key=${update.key}")
                            e.images
                        } else e.images + img
                        list.toMutableList().also {
                            it[i] = e.copy(images = images, streaming = true)
                        }
                    }
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
            // The turn is over, so whatever it asked and nobody answered can no longer be answered.
            is CodeUpdate.TurnDone -> list.map {
                when {
                    it is CodeEvent.AgentText && it.streaming -> it.copy(streaming = false)
                    it is CodeEvent.Approval && it.pending -> it.copy(expired = true)
                    else -> it
                }
            } + CodeEvent.TurnEnd("turn:$now", now, update.stopReason, update.summary)
            is CodeUpdate.Title -> list
            is CodeUpdate.SessionInfo -> list
            is CodeUpdate.AvailableCommands -> list
        }

    private fun upsert(list: List<CodeEvent>, e: CodeEvent): List<CodeEvent> {
        val i = list.indexOfLast { it.key == e.key }
        if (i < 0) return list + e
        // A replayed request must not reopen an approval that was already answered or expired.
        val old = list[i]
        val next = if (old is CodeEvent.Approval && e is CodeEvent.Approval && !old.pending) {
            e.copy(chosen = old.chosen, expired = old.expired)
        } else e
        return list.toMutableList().also { it[i] = next }
    }

    /** Session status as the list and header show it, derived from the transcript tail. */
    fun statusOf(list: List<CodeEvent>, running: Boolean): SessionStatus = when {
        list.any { it is CodeEvent.Approval && it.pending } -> SessionStatus.NEEDS_APPROVAL
        running -> SessionStatus.RUNNING
        list.lastOrNull() is CodeEvent.Notice && (list.last() as CodeEvent.Notice).level == NoticeLevel.ERROR -> SessionStatus.ERROR
        else -> SessionStatus.IDLE
    }

    /** Cap inline images merged into one [CodeEvent.AgentText] (matches prompt attach max). */
    private const val MAX_AGENT_IMAGES = 4
}
