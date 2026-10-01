package io.github.stardomains3.oxproxion.code

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Agent Client Protocol (https://agentclientprotocol.com) over JSON-RPC 2.0, as relayed by the
 * GradatiON bridge. Covers the subset the UI renders today:
 *
 * - out: initialize, session/new, session/load, session/prompt, session/cancel, session/set_mode,
 *   permission responses, and the bridge's own `bridge.` extension methods.
 * - in: session/update (agent_message_chunk, agent_thought_chunk, user_message_chunk, tool_call,
 *   tool_call_update, plan), session/request_permission, bridge/permissionResolved,
 *   bridge/sessionStatus, and responses.
 *
 * Event keys are stable across reconnects: text/thought/user derive from bridge `_meta.seq`,
 * tool calls from ACP toolCallId, approvals from the JSON-RPC request id, plans from session id.
 * `session/load` accepts `_meta.afterSeq` so TranscriptReducer upserts stay idempotent on replay.
 *
 * Not yet: the terminal and fs client methods (the bridge answers those on the machine itself).
 * `current_mode_update` syncs the approval pill ([CodeUpdate.SessionInfo.permissionMode]).
 * `session_info_update` carries the agent's session title ([CodeUpdate.SessionInfo.title]).
 * Slash commands: `available_commands_update` → [CodeUpdate.AvailableCommands].
 * Prompt images: [prompt] accepts [PromptAttachment] → ACP `type: image` content blocks.
 * Agent images: `agent_message_chunk` with `type: image` (data+mimeType) → [CodeUpdate.ImageChunk].
 * A `resource_link` or embedded `resource` in an agent or user chunk is shown as text.
 * Tool detail prefers the command for a shell call, and a file location includes its line.
 * Shell and search output is terminal text: color and a rewritten progress line are dropped.
 * A file read keeps those bytes. Tool status accepts `in-progress`, `running`, `error`,
 * `done`, and `cancelled`. Kind accepts the names agents actually send (`Bash`, `grep`, `write`),
 * and a tool `name` when `kind` is missing or `other`.
 * `tool_call_content_chunk` appends. A diff may be old/new text or a v2 `changes` + `patch`.
 * Cursor Agent's `cursor/ask_question`, `cursor/create_plan`, and `cursor/update_todos`
 * are answered here so those requests do not sit forever.
 */
class AcpAdapter : HarnessAdapter {

    override val protocol = "acp/1"

    private val json = Json { ignoreUnknownKeys = true }
    // Frames decode on a background thread while the hub reads and clears cursors from Main, so
    // every map here is concurrent.
    /** Keys of the currently open agent message / thought per session, so chunks merge. */
    private val openText = ConcurrentHashMap<String, String>()
    private val openThought = ConcurrentHashMap<String, String>()
    /**
     * Resume cursor: the highest seq with no hole in front of it. A frame that jumps
     * ahead is shown, but this stays put so `session/load` still asks for the missing ones.
     */
    private val lastSeqBySession = ConcurrentHashMap<String, Long>()
    /**
     * Seqs already on screen that sit past [lastSeqBySession]. A replay must not append
     * them again, and a failed load must not treat the far side of the hole as consumed.
     */
    private val appliedPastCursor = ConcurrentHashMap<String, MutableSet<Long>>()
    private val cursorLock = Any()
    /** Fallback key counter when a frame has no `_meta.seq` (non-bridge / tests). */
    private val localKeyCounter = AtomicLong(0L)

    /**
     * A seq hole being refilled. [floor] is the resume cursor when the hole opened, so an
     * inclusive replay (`seq >= afterSeq`) does not append that chunk again. [seen] holds
     * seqs delivered while this reload is in flight (including the frame that opened it).
     */
    private class OpenGap(
        val floor: Long,
        val seen: MutableSet<Long> = ConcurrentHashMap.newKeySet(),
    )
    private val openGaps = ConcurrentHashMap<String, OpenGap>()
    /**
     * Kind and whether a detail line was already shown, per session and tool id.
     * A `tool_call_update` often omits `kind` and repeats only the working folder.
     */
    private val toolKinds = ConcurrentHashMap<String, String>()
    private val toolDetailSet = ConcurrentHashMap.newKeySet<String>()
    private val cursor = CursorMethods()
    private val repeatedUnderscore = Regex("_+")

    // ── outbound ──────────────────────────────────────────────────────────────────────────

    override fun initialize(id: Long) = request(id, "initialize", buildJsonObject {
        put("protocolVersion", 1)
        put("clientCapabilities", buildJsonObject {
            // The bridge owns the filesystem and terminals on the machine; the phone never does.
            put("fs", buildJsonObject { put("readTextFile", false); put("writeTextFile", false) })
            put("terminal", false)
        })
        put("clientInfo", buildJsonObject {
            put("name", "GradatiON")
            put("title", "GradatiON")
            put("version", "1")
        })
    })

    override fun authenticate(id: Long, methodId: String) = request(id, "authenticate", buildJsonObject {
        put("methodId", methodId)
    })

    override fun newSession(id: Long, request: NewSessionRequest) = request(id, "session/new", buildJsonObject {
        put("cwd", request.workspace)
        put("mcpServers", JsonArray(emptyList()))
        // Bridge extension fields; plain ACP agents ignore unknown params.
        put("_meta", buildJsonObject {
            put("harness", request.harness.id)
            put("permissionMode", request.permissionMode.id)
            request.model?.let { put("model", it) }
        })
    })

    override fun loadSession(id: Long, sessionId: String, workspace: String, afterSeq: Long?) = request(id, "session/load", buildJsonObject {
        put("sessionId", sessionId)
        put("cwd", workspace)
        put("mcpServers", JsonArray(emptyList()))
        // Bridge extension: replay only notifications with seq > afterSeq (idempotent with stable keys).
        if (afterSeq != null) put("_meta", buildJsonObject { put("afterSeq", afterSeq) })
    })

    override fun prompt(
        id: Long,
        sessionId: String,
        text: String,
        attachments: List<PromptAttachment>,
    ) = request(id, "session/prompt", buildJsonObject {
        put("sessionId", sessionId)
        put("prompt", buildJsonArray {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                add(buildJsonObject { put("type", "text"); put("text", trimmed) })
            }
            for (att in attachments) {
                add(buildJsonObject {
                    put("type", "image")
                    put("mimeType", att.mimeType)
                    put("data", att.data)
                })
            }
            // ACP requires a non-empty prompt array; keep a blank text block if somehow empty.
            if (trimmed.isEmpty() && attachments.isEmpty()) {
                add(buildJsonObject { put("type", "text"); put("text", "") })
            }
        })
    })

    override fun cancel(sessionId: String) = notification("session/cancel", buildJsonObject { put("sessionId", sessionId) })

    override fun setMode(id: Long, sessionId: String, mode: PermissionMode) = request(id, "session/set_mode", buildJsonObject {
        put("sessionId", sessionId)
        put("modeId", mode.id)
    })

    override fun answerApproval(requestId: String, optionId: String?): String {
        cursor.answer(requestId, optionId)?.let { return it }
        return acpApproval(requestId, optionId)
    }

    private fun acpApproval(requestId: String, optionId: String?): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", requestId.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(requestId))
        put("result", buildJsonObject {
            put("outcome", buildJsonObject {
                if (optionId.isNullOrEmpty()) put("outcome", "cancelled")
                else { put("outcome", "selected"); put("optionId", optionId) }
            })
        })
    }.toString()

    override fun listSessions(id: Long) = request(id, "bridge/listSessions", JsonObject(emptyMap()))

    override fun listWorkspaces(id: Long, harness: HarnessKind) =
        request(id, "bridge/listWorkspaces", buildJsonObject { put("harness", harness.id) })

    override fun listHarnesses(id: Long) = request(id, "bridge/listHarnesses", JsonObject(emptyMap()))

    override fun browse(id: Long, path: String) =
        request(id, "bridge/browse", buildJsonObject { put("path", path) })

    override fun gitStatus(id: Long, sessionId: String) =
        request(id, "bridge/gitStatus", buildJsonObject { put("sessionId", sessionId) })

    override fun diff(id: Long, sessionId: String, path: String) =
        request(id, "bridge/diff", buildJsonObject {
            put("sessionId", sessionId)
            put("path", path)
        })

    private fun request(id: Long, method: String, params: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
    }.toString()

    private fun notification(method: String, params: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0"); put("method", method); put("params", params)
    }.toString()

    // ── inbound ───────────────────────────────────────────────────────────────────────────

    override fun decode(frame: String): List<AdapterOutput> {
        // R6: never throw out of decode — a single malformed frame must not kill the reader.
        return runCatching { decodeFrame(frame) }.getOrElse { t ->
            ignored("decode failed: ${t.message ?: t::class.simpleName}")
        }
    }

    private fun decodeFrame(frame: String): List<AdapterOutput> {
        val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrElse {
            return listOf(AdapterOutput.Ignored("not JSON"))
        }
        val method = obj.str("method")
        val idEl = obj["id"]
        val admit = admit(method, obj)
        if (admit is Admit.Drop) return ignored("replayed seq")
        val out = decodeBody(method, idEl, obj)
        return if (admit is Admit.Hole) listOf<AdapterOutput>(AdapterOutput.Gap(admit.sessionId, admit.afterSeq)) + out else out
    }

    private sealed interface Admit {
        object Ok : Admit
        /** Already delivered while a seq hole was open; the replay repeats it. */
        object Drop : Admit
        /** This frame's seq jumped past the session's last one: frames after [afterSeq] went missing. */
        data class Hole(val sessionId: String, val afterSeq: Long) : Admit
    }

    /**
     * Seq bookkeeping for one notification, before it is decoded. Bridge seqs are consecutive per
     * session, so a jump means frames were dropped in flight; the first frame past the hole opens
     * a [OpenGap] until the backend's reload finishes. The resume cursor stays on the near side
     * for that whole time: moving it to the jumped seq used to make a failed replay permanent.
     *
     * Text and image chunks append, so a seq that was already applied must not be decoded again
     * (a bridge that replays `seq >= afterSeq`, or a duplicate frame on the socket). Tools and
     * approvals upsert, but dropping here keeps one path for every notification.
     */
    private fun admit(method: String?, obj: JsonObject): Admit {
        if (method !in SEQ_METHODS) return Admit.Ok
        val params = obj["params"] as? JsonObject ?: return Admit.Ok
        val sid = params.str("sessionId") ?: return Admit.Ok
        val seq = bridgeSeq(params, obj) ?: return Admit.Ok
        synchronized(cursorLock) {
            val prev = lastSeqBySession[sid]
            if (prev != null && seq <= prev) return Admit.Drop
            if (appliedPastCursor[sid]?.contains(seq) == true) {
                // The next seq was shown before the hole filled. Close the cursor over it
                // so the frame after it is not another hole, and do not append it again.
                if (prev != null && seq == prev + 1L) advanceCursor(sid, seq)
                return Admit.Drop
            }
            openGaps[sid]?.let { gap ->
                if (seq <= gap.floor) return Admit.Drop
                if (!gap.seen.add(seq)) return Admit.Drop
                parked(sid).add(seq)
                return Admit.Ok
            }
            if (prev == null || seq == prev + 1L) return Admit.Ok
            openGaps[sid] = OpenGap(floor = prev).also { it.seen.add(seq) }
            parked(sid).add(seq)
            return Admit.Hole(sid, prev)
        }
    }

    private fun decodeBody(method: String?, idEl: JsonElement?, obj: JsonObject): List<AdapterOutput> {
        return when {
            method == "session/update" -> {
                val params = obj["params"] as? JsonObject ?: return ignored("no params")
                val updates = decodeUpdate(params, bridgeSeq(params, obj))
                // A few agents send this notification as a request. Ack it so they do not wait.
                if (idEl is JsonPrimitive) updates + AdapterOutput.Reply(rpcResult(idEl)) else updates
            }
            method == "session/request_permission" && idEl != null -> {
                val params = obj["params"] as? JsonObject ?: return ignored("no params")
                decodePermission(idEl, params, bridgeSeq(params, obj))
            }
            method == "bridge/permissionResolved" -> {
                val params = obj["params"] as? JsonObject ?: return ignored("no params")
                decodePermissionResolved(params, bridgeSeq(params, obj))
            }
            method == "bridge/sessionStatus" -> {
                val params = obj["params"] as? JsonObject ?: return ignored("no params")
                decodeSessionStatus(params, bridgeSeq(params, obj))
            }
            method == "cursor/update_todos" || method == "cursor/ask_question" || method == "cursor/create_plan" -> {
                val params = obj["params"] as? JsonObject ?: return ignored("no params")
                val sid = sessionOf(params).orEmpty()
                val seq = bridgeSeq(params, obj)
                if (sid.isNotEmpty()) noteSeq(sid, seq)
                cursor.handle(method, sid, idEl, params, seq, System.currentTimeMillis())
            }
            method == null && idEl != null -> {
                val id = rpcLongId(idEl) ?: return ignored("non-numeric id")
                val err = obj["error"]?.let { (it as? JsonObject)?.str("message") ?: it.toString() }
                listOf(AdapterOutput.Result(id, obj["result"], err))
            }
            // fs/* and terminal/* are answered on the computer. If one is forwarded here,
            // an error response unblocks the agent; ignoring it leaves the turn stuck.
            method != null && idEl is JsonPrimitive ->
                listOf(AdapterOutput.Reply(rpcError(idEl, -32601, "Method not found")))
            else -> ignored("method $method")
        }
    }

    /** JSON-RPC id, including a number a proxy rewrote as a string. */
    private fun rpcLongId(idEl: JsonElement?): Long? {
        val p = idEl as? JsonPrimitive ?: return null
        return p.longOrNull ?: p.contentOrNull?.toLongOrNull()
    }

    private fun rpcResult(id: JsonElement) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", JsonObject(emptyMap()))
    }.toString()

    private fun rpcError(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()

    private fun decodePermissionResolved(params: JsonObject, seq: Long?): List<AdapterOutput> {
        val sid = params.str("sessionId") ?: return ignored("permissionResolved without session")
        val requestId = params.str("requestId") ?: return ignored("permissionResolved without requestId")
        noteSeq(sid, seq)
        val kind = optionKind(params.str("optionKind"))
        return listOf(AdapterOutput.Update(sid, CodeUpdate.ApprovalAnswered(requestId, kind), seq))
    }

    private fun decodeSessionStatus(params: JsonObject, seq: Long?): List<AdapterOutput> {
        val sid = params.str("sessionId") ?: return ignored("sessionStatus without session")
        noteSeq(sid, seq)
        val status = when (params.str("status")?.lowercase()?.replace('-', '_')) {
            "running" -> SessionStatus.RUNNING
            "needs_approval" -> SessionStatus.NEEDS_APPROVAL
            "error" -> SessionStatus.ERROR
            "offline" -> SessionStatus.OFFLINE
            "idle" -> SessionStatus.IDLE
            null -> null
            else -> null
        }
        return listOf(
            AdapterOutput.Update(
                sid,
                CodeUpdate.SessionInfo(
                    status = status,
                    title = params.str("title"),
                    preview = params.str("preview"),
                    branch = params.str("branch")
                ),
                seq
            )
        )
    }

    private fun optionKind(s: String?): ApprovalOption.Kind = approvalKind(s)

    /**
     * ACP's four kinds, plus the short names some agents still send (`reject`, `deny`).
     * An unknown kind falls back to the option's name, so a button labelled Deny is not
     * drawn and notified as Allow. A name we cannot read stays allow-once, as before.
     */
    private fun approvalKind(kind: String?, name: String? = null): ApprovalOption.Kind {
        when (normalizeKind(kind)) {
            "allow_always", "always_allow", "allow_all" -> return ApprovalOption.Kind.ALLOW_ALWAYS
            "reject_always", "deny_always" -> return ApprovalOption.Kind.REJECT_ALWAYS
            "reject_once", "reject", "deny", "deny_once", "cancelled", "canceled" ->
                return ApprovalOption.Kind.REJECT_ONCE
            "allow_once", "allow", "approve" -> return ApprovalOption.Kind.ALLOW_ONCE
        }
        val n = name?.trim()?.lowercase().orEmpty()
        if (n.startsWith("deny") || n.startsWith("reject") || n.startsWith("don't") || n.startsWith("dont")) {
            return if (n.contains("always")) ApprovalOption.Kind.REJECT_ALWAYS else ApprovalOption.Kind.REJECT_ONCE
        }
        if (n.contains("always") && (n.startsWith("allow") || n.startsWith("always"))) {
            return ApprovalOption.Kind.ALLOW_ALWAYS
        }
        return ApprovalOption.Kind.ALLOW_ONCE
    }

    private fun normalizeKind(kind: String?): String {
        val raw = kind?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_').orEmpty()
        return if (raw.contains("__")) raw.replace(repeatedUnderscore, "_") else raw
    }

    /**
     * ACP's kinds, plus the tool names agents put in `kind` (`Bash`, `grep`, `write`, `Read`).
     * The canonical token is what the icon, the detail line, and head-vs-tail clipping share.
     * Null when the frame omitted kind.
     */
    private fun canonicalKind(kind: String?): String? {
        val n = normalizeKind(kind)
        if (n.isEmpty()) return null
        return when (n) {
            "read", "read_file", "readfile", "cat" -> "read"
            "edit", "write", "write_file", "str_replace", "strreplace", "apply_patch", "patch" -> "edit"
            "delete", "remove", "rm" -> "delete"
            "move", "rename", "mv" -> "move"
            "search", "grep", "glob", "find", "rg" -> "search"
            "execute", "bash", "shell", "terminal", "command", "run", "run_command" -> "execute"
            "think", "thought", "reasoning" -> "think"
            "fetch", "web_fetch", "webfetch", "http" -> "fetch"
            else -> n
        }
    }

    private fun decodeUpdate(params: JsonObject, seq: Long?): List<AdapterOutput> {
        val sid = params.str("sessionId") ?: return ignored("no sessionId")
        val u = params["update"] as? JsonObject ?: return ignored("no update")
        noteSeq(sid, seq)
        val now = System.currentTimeMillis()
        val out: CodeUpdate? = when (u.str("sessionUpdate")) {
            "agent_message_chunk" -> {
                openThought.remove(sid)
                return agentMessage(sid, u["content"], seq)
            }
            "agent_thought_chunk" -> {
                val text = AcpMessageContent.textOf(u["content"])
                    ?: return ignored("agent_thought_chunk without text")
                val key = openThought.getOrPut(sid) { stableKey("thought", seq) }
                CodeUpdate.TextChunk(key, text, thought = true)
            }
            "user_message_chunk" -> {
                // Replayed history (session/load) or a prompt sent from another device.
                closeText(sid)
                val body = AcpMessageContent.userBody(u["content"])
                    ?: return ignored("user_message_chunk without text")
                CodeUpdate.Upsert(CodeEvent.UserPrompt(
                    stableKey("user", seq), now, body.text, attachmentCount = body.imageCount,
                ))
            }
            "tool_call" -> {
                closeText(sid)
                return toolCall(sid, u, now, seq)
            }
            "tool_call_update" -> return toolCallUpdate(sid, u, now, seq)
            "tool_call_content_chunk" -> return toolContentChunk(sid, u, now, seq)
            "plan" -> {
                val entries = u["entries"]?.jsonArray?.mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    PlanEntry(o.str("content") ?: "", planStatus(o.str("status")))
                }.orEmpty()
                // One live plan card per session: same key, so it updates in place.
                CodeUpdate.Upsert(CodeEvent.Plan("plan:$sid", now, entries))
            }
            "available_commands_update" -> {
                val arr = u["availableCommands"] as? JsonArray
                    ?: return ignored("available_commands_update without availableCommands array")
                CodeUpdate.AvailableCommands(parseAvailableCommands(arr))
            }
            "current_mode_update" -> {
                // The harness (or another client) changed mode. Unknown ids must not snap the pill to Ask.
                val mode = PermissionMode.fromAcpModeId(u.str("currentModeId") ?: u.str("modeId"))
                    ?: return ignored("current_mode_update unknown mode")
                CodeUpdate.SessionInfo(permissionMode = mode)
            }
            "session_info_update" -> {
                // ACP: the agent named the session. A blank title must not wipe the one we have.
                val title = u.str("title")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return ignored("session_info_update without title")
                CodeUpdate.SessionInfo(title = title)
            }
            else -> null
        }
        return if (out == null) ignored("update ${u.str("sessionUpdate")}")
        else listOf(AdapterOutput.Update(sid, out, seq))
    }

    /**
     * One agent chunk: text, images, and links share the open message key. A chunk that is only
     * an unusable image stays ignored (and does not open a key) so the reason still says why.
     */
    private fun agentMessage(sid: String, content: JsonElement?, seq: Long?): List<AdapterOutput> {
        val parsed = AcpMessageContent.parse(content)
        if (parsed is AcpMessageContent.Parse.Rejected) {
            return ignored("agent_message_chunk ${parsed.reason}")
        }
        val pieces = (parsed as AcpMessageContent.Parse.Ok).pieces
        // Key from the first chunk's bridge seq so session/load replay upserts, not duplicates.
        val prior = openText[sid]
        val key = prior ?: stableKey("text", seq)
        openText[sid] = key
        val updates = ArrayList<CodeUpdate>()
        AcpMessageContent.joinText(pieces, continuing = prior != null)?.let {
            updates += CodeUpdate.TextChunk(key, it)
        }
        for (p in pieces) {
            if (p is AcpMessageContent.Piece.Image) updates += CodeUpdate.ImageChunk(key, p.mimeType, p.data)
        }
        if (updates.isEmpty()) return ignored("agent_message_chunk without content")
        return updates.map { AdapterOutput.Update(sid, it, seq) }
    }

    private fun toolCall(sid: String, u: JsonObject, now: Long, seq: Long?): List<AdapterOutput> {
        val callId = u.str("toolCallId") ?: return ignored("tool_call without id")
        val canon = namedKind(u.str("kind"), u.str("name"))
        val kind = toolKind(canon)
        val result = ArrayList<AdapterOutput>()
        val detail = detailOf(u)
        rememberKind(sid, callId, canon)
        if (!detail.isNullOrBlank()) toolDetailSet.add(toolKey(sid, callId))
        result += AdapterOutput.Update(sid, CodeUpdate.Upsert(CodeEvent.ToolCall(
            key = "tool:$callId",
            at = now,
            callId = callId,
            kind = kind,
            title = u.str("title") ?: "Tool call",
            detail = detail,
            status = toolStatus(u.str("status")) ?: ToolStatus.PENDING,
            output = outputOf(sid, callId, u)
        )), seq)
        diffs(callId, u["content"], now).forEach {
            result += AdapterOutput.Update(sid, CodeUpdate.Upsert(it), seq)
        }
        return result
    }

    private fun toolCallUpdate(sid: String, u: JsonObject, now: Long, seq: Long?): List<AdapterOutput> {
        val callId = u.str("toolCallId") ?: return ignored("tool_call_update without id")
        val result = ArrayList<AdapterOutput>()
        result += AdapterOutput.Update(sid, CodeUpdate.ToolPatch(
            callId = callId,
            status = toolStatus(u.str("status")),
            title = u.str("title"),
            // A location-only update must not wipe the command the tool_call already showed.
            detail = detailForUpdate(sid, callId, u),
            output = outputOf(sid, callId, u),
            // Omitted kind must stay null so a status-only update does not reset the icon.
            // A name can still correct `kind: other` (Cursor's first MCP frame).
            kind = toolKindIfPresent(u.str("kind"), u.str("name")),
        ), seq)
        diffs(callId, u["content"], now).forEach {
            result += AdapterOutput.Update(sid, CodeUpdate.Upsert(it), seq)
        }
        return result
    }

    /** One appended content item. A later `tool_call_update` with `content` still replaces. */
    private fun toolContentChunk(sid: String, u: JsonObject, now: Long, seq: Long?): List<AdapterOutput> {
        val callId = u.str("toolCallId") ?: return ignored("tool_call_content_chunk without id")
        val result = ArrayList<AdapterOutput>()
        val text = outputOf(sid, callId, u)
        if (!text.isNullOrEmpty()) {
            result += AdapterOutput.Update(sid, CodeUpdate.ToolPatch(
                callId = callId,
                status = toolStatus(u.str("status")),
                title = u.str("title"),
                detail = detailForUpdate(sid, callId, u),
                output = text,
                appendOutput = true,
                kind = toolKindIfPresent(u.str("kind"), u.str("name")),
            ), seq)
        }
        diffs(callId, u["content"], now).forEach {
            result += AdapterOutput.Update(sid, CodeUpdate.Upsert(it), seq)
        }
        if (result.isEmpty()) return ignored("tool_call_content_chunk without content")
        return result
    }

    private fun decodePermission(idEl: JsonElement, params: JsonObject, seq: Long?): List<AdapterOutput> {
        val sid = params.str("sessionId") ?: return ignored("permission without session")
        noteSeq(sid, seq)
        val subject = params["subject"] as? JsonObject
        val call = params["toolCall"] as? JsonObject
            ?: subject?.get("toolCall") as? JsonObject
        val command = if (subject?.str("type")?.lowercase() == "command") subject.str("command") else null
        // R6: non-primitive JSON-RPC id must not ClassCastException out of decode.
        val requestId = (idEl as? JsonPrimitive)?.contentOrNull
            ?: return ignored("non-primitive permission id")
        // The agent reuses ids. A question or plan that held this one must not
        // rewrite the permission result the next Allow sends.
        cursor.release(requestId)
        val options = params["options"]?.jsonArray?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("optionId") ?: return@mapNotNull null
            val kind = approvalKind(o.str("kind"), o.str("name"))
            ApprovalOption(id, o.str("name") ?: kind.name, kind)
        }.orEmpty()
        val now = System.currentTimeMillis()
        closeText(sid)
        // A request with no choices still has to be answerable; "Deny" replies `cancelled`.
        val offered = options.ifEmpty {
            listOf(ApprovalOption(ApprovalOption.CANCEL_ID, "Deny", ApprovalOption.Kind.REJECT_ONCE))
        }
        return listOf(AdapterOutput.Update(sid, CodeUpdate.Upsert(CodeEvent.Approval(
            key = "approval:$requestId",
            at = now,
            requestId = requestId,
            callId = call?.str("toolCallId") ?: subject?.str("toolCallId"),
            title = params.str("title")?.trim()?.ifEmpty { null }
                ?: call?.str("title")?.trim()?.ifEmpty { null }
                ?: "The agent wants to continue",
            detail = command?.trim()?.ifEmpty { null }
                ?: params.str("description")?.trim()?.ifEmpty { null }
                ?: call?.let { detailOf(it) },
            kind = toolKind(namedKind(call?.str("kind"), call?.str("name"))),
            options = offered
        )), seq))
    }

    /** A tool call or prompt boundary ends the current agent message, so the next text starts a new one. */
    private fun closeText(sid: String) {
        openText.remove(sid)
        openThought.remove(sid)
    }

    /** Call when a prompt's response arrives, so the next turn starts fresh text. */
    fun endTurn(sessionId: String) = closeText(sessionId)

    /** Contiguous resume cursor for [sessionId], or null if none yet. A hole does not move this. */
    fun lastSeq(sessionId: String): Long? = lastSeqBySession[sessionId]

    /** Restore a resume cursor from Room after process death (keeps the higher value). */
    fun seedLastSeq(sessionId: String, seq: Long) {
        synchronized(cursorLock) {
            val merged = lastSeqBySession.merge(sessionId, seq) { a, b -> maxOf(a, b) } ?: seq
            appliedPastCursor[sessionId]?.let { extra ->
                extra.removeIf { it <= merged }
                if (extra.isEmpty()) appliedPastCursor.remove(sessionId)
            }
        }
    }

    /** Drop resume cursor when the hub forgets a session (B2). */
    fun clearLastSeq(sessionId: String) {
        synchronized(cursorLock) {
            lastSeqBySession.remove(sessionId)
            appliedPastCursor.remove(sessionId)
            openGaps.remove(sessionId)
        }
        openText.remove(sessionId)
        openThought.remove(sessionId)
        val prefix = "$sessionId\u0000"
        toolKinds.keys.removeAll { it.startsWith(prefix) }
        toolDetailSet.removeAll { it.startsWith(prefix) }
        cursor.clearSession(sessionId)
    }

    /**
     * The replay for a seq hole has finished. [filled] means the load completed, so a
     * bridge that skips sequence numbers can move the cursor to what is already on screen.
     * A failed load leaves the cursor on the near side; the next resume still asks for the hole.
     */
    fun endGap(sessionId: String, filled: Boolean = false) {
        synchronized(cursorLock) {
            openGaps.remove(sessionId)
            if (filled) commitParked(sessionId)
        }
    }

    /**
     * A `session/load` that was not itself a gap refill has finished, and nothing is
     * waiting on a hole. Parked seqs the bridge did not replay are then consumed.
     */
    fun sealResumeCursor(sessionId: String) {
        synchronized(cursorLock) {
            if (openGaps.containsKey(sessionId)) return
            commitParked(sessionId)
        }
    }

    /**
     * Stable event key: prefer bridge `_meta.seq` (reconnect/resume safe). Tool/approval/plan keys
     * use ACP ids instead. Without seq (plain ACP / older fixtures), fall back to a local counter.
     */
    private fun stableKey(kind: String, bridgeSeq: Long?): String {
        val n = bridgeSeq ?: localKeyCounter.getAndIncrement()
        return "$kind:$n"
    }

    private fun noteSeq(sessionId: String, seq: Long?) {
        if (seq == null) return
        advanceCursor(sessionId, seq)
    }

    private fun parked(sessionId: String): MutableSet<Long> =
        appliedPastCursor.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }

    /**
     * Move the resume cursor only through seqs that have actually been applied.
     * A jump is remembered and skipped over once the missing numbers arrive, or
     * once [commitParked] accepts a finished load.
     */
    private fun advanceCursor(sessionId: String, seq: Long) {
        synchronized(cursorLock) {
            val prev = lastSeqBySession[sessionId]
            if (prev != null && seq <= prev) return
            if (prev != null && seq != prev + 1L) {
                parked(sessionId).add(seq)
                return
            }
            var cursor = seq
            val extra = appliedPastCursor[sessionId]
            extra?.remove(seq)
            if (extra != null) {
                while (extra.remove(cursor + 1L)) cursor++
                if (extra.isEmpty()) appliedPastCursor.remove(sessionId)
            }
            lastSeqBySession[sessionId] = cursor
        }
    }

    /** Caller holds [cursorLock]. */
    private fun commitParked(sessionId: String) {
        val extra = appliedPastCursor.remove(sessionId) ?: return
        val high = extra.maxOrNull() ?: return
        val current = lastSeqBySession[sessionId]
        if (current == null || high > current) lastSeqBySession[sessionId] = high
    }

    /** Bridge (or top-level) `_meta.seq` on a notification / permission request. */
    private fun bridgeSeq(params: JsonObject, root: JsonObject): Long? =
        metaSeq(params) ?: metaSeq(root)

    /** ACP `sessionId`, or the same field a bridge stuffed in `_meta`. */
    private fun sessionOf(params: JsonObject): String? =
        params.str("sessionId") ?: params.str("session_id")
            ?: (params["_meta"] as? JsonObject)?.str("sessionId")

    private fun metaSeq(obj: JsonObject): Long? {
        val meta = obj["_meta"] as? JsonObject ?: return null
        val el = meta["seq"] as? JsonPrimitive ?: return null
        return el.longOrNull ?: el.contentOrNull?.toLongOrNull()
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    /**
     * Detail worth applying on `tool_call_update`. Null keeps the previous line.
     * A later frame that only repeats the working folder used to replace `npm test`.
     */
    private fun detailForUpdate(sid: String, callId: String, u: JsonObject): String? {
        val raw = rawInputOf(u)
        val specific = commandOf(raw) != null ||
            firstRaw(raw, "pattern", "query", "url", "regex") != null ||
            firstRaw(raw, "file_path", "filePath", "path", "target_file", "targetFile") != null
        val hasLine = (u["locations"] as? JsonArray).orEmpty().any { e ->
            val line = (e as? JsonObject)?.str("line")?.toIntOrNull()
            line != null && line > 0
        }
        val key = toolKey(sid, callId)
        val hadDetail = toolDetailSet.contains(key)
        val hasPath = (u["locations"] as? JsonArray).orEmpty().any { e ->
            val path = (e as? JsonObject)?.str("path")
            !path.isNullOrBlank()
        }
        // A folder-only update must not replace `npm test`. The first path still fills an empty row.
        if (!specific && !hasLine && (hadDetail || !hasPath)) return null
        val detail = detailOf(u) ?: return null
        if (detail.isNotBlank()) toolDetailSet.add(key)
        return detail
    }

    private fun toolKey(sid: String, callId: String) = "$sid\u0000$callId"

    /** Later updates often omit kind. The first frame's kind still decides which end of a long log to keep. */
    private fun rememberKind(sid: String, callId: String, kind: String?): String? {
        val key = toolKey(sid, callId)
        val canon = canonicalKind(kind)
        if (canon != null) toolKinds[key] = canon
        return canon ?: toolKinds[key]
    }

    private fun outputOf(sid: String, callId: String, u: JsonObject): String? {
        val kind = rememberKind(sid, callId, namedKind(u.str("kind"), u.str("name")))
        val raw = textContent(u["content"]) ?: rawOutputText(u["rawOutput"]) ?: return null
        // Color and `\r` progress belong on a log, not in a file the agent opened.
        val shown = if (ToolOutputText.stripsTerminal(kind)) TerminalText.readable(raw) else raw
        if (shown.isEmpty()) return null
        return ToolOutputText.clip(kind, shown, MAX_OUTPUT)
    }

    /**
     * Some agents put the log in `rawOutput` instead of a content block: a string,
     * `{stdout, stderr}`, or a list of lines. A number is not a log.
     */
    private fun rawOutputText(el: JsonElement?): String? = when (el) {
        is JsonPrimitive -> primitiveLog(el)
        is JsonObject -> {
            val out = firstRaw(el, "output", "stdout", "text", "result")
            val err = firstRaw(el, "stderr")
            when {
                out != null && err != null && err != out -> "$out\n$err"
                out != null -> out
                else -> err
            }
        }
        is JsonArray -> el.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> primitiveLog(item)
                is JsonObject -> toolOutputPiece(item) ?: firstRaw(item, "text", "output", "stdout", "stderr")
                else -> null
            }
        }.joinToString("\n").ifEmpty { null }
        else -> null
    }

    /** A JSON string's content is the text. Numbers and booleans are not a log. */
    private fun primitiveLog(el: JsonPrimitive): String? {
        if (el is JsonNull) return null
        val asNumber = el.longOrNull != null || el.doubleOrNull != null || el.booleanOrNull != null
        if (asNumber) return null
        return el.content.trim().ifEmpty { null }
    }

    private fun detailOf(u: JsonObject): String? {
        val locations = (u["locations"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val path = o.str("path") ?: return@mapNotNull null
            ToolCallDetail.Location(path, o.str("line")?.toIntOrNull())
        }
        val raw = rawInputOf(u)
        return ToolCallDetail.format(
            kind = namedKind(u.str("kind"), u.str("name")),
            locations = locations,
            command = commandOf(raw),
            query = firstRaw(raw, "pattern", "query", "url", "regex"),
            filePath = firstRaw(raw, "file_path", "filePath", "path", "target_file", "targetFile"),
        )
    }

    /**
     * `rawInput` is an object, or a JSON string of one (some agents stringify the tool args).
     * A string that is not an object is ignored; the title still shows.
     */
    private fun rawInputOf(u: JsonObject): JsonObject? = when (val el = u["rawInput"]) {
        is JsonObject -> el
        is JsonPrimitive -> {
            val text = if (el is JsonNull) null else el.contentOrNull
            if (text.isNullOrBlank()) null
            else runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        }
        else -> null
    }

    /**
     * `command` may be a string or an argv array. A separate `args` / `arguments` list is
     * appended when the command itself is not already that list, so `git` plus `["status"]`
     * reads `git status`. Pieces that contain spaces are quoted.
     */
    private fun commandOf(raw: JsonObject?): String? {
        if (raw == null) return null
        val el = raw["command"] ?: raw["cmd"]
        val base = when (el) {
            is JsonArray -> joinArgs(el)
            is JsonPrimitive -> if (el is JsonNull) "" else el.contentOrNull.orEmpty()
            else -> ""
        }.trim()
        if (el is JsonArray) return base.ifEmpty { null }
        val extra = when (val args = raw["args"] ?: raw["arguments"]) {
            is JsonArray -> joinArgs(args)
            is JsonPrimitive -> if (args is JsonNull) "" else args.contentOrNull.orEmpty().trim()
            else -> ""
        }
        val text = when {
            base.isEmpty() -> extra
            extra.isEmpty() -> base
            else -> "$base $extra"
        }.trim()
        return text.ifEmpty { null }
    }

    private fun joinArgs(arr: JsonArray): String =
        arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotEmpty() } }
            .joinToString(" ") { piece -> if (piece.any { it.isWhitespace() }) "\"$piece\"" else piece }

    private fun firstRaw(raw: JsonObject?, vararg keys: String): String? {
        if (raw == null) return null
        for (k in keys) {
            val s = raw.str(k)?.trim()?.ifEmpty { null } ?: continue
            return s
        }
        return null
    }

    private fun textContent(content: JsonElement?): String? {
        if (content is JsonPrimitive) return primitiveLog(content)
        val items = contentItems(content) ?: return null
        val text = items.mapNotNull { toolOutputPiece(it) }.joinToString("\n")
        return text.ifEmpty { null }
    }

    /** Tool `content` is an array, or one block some agents send as a bare object. */
    private fun contentItems(content: JsonElement?): List<JsonObject>? = when (content) {
        is JsonArray -> content.mapNotNull { it as? JsonObject }.ifEmpty { null }
        is JsonObject -> listOf(content)
        else -> null
    }

    /**
     * Text the transcript can show from one tool-content block.
     * `type: content` is an ACP content block; file reads often use an embedded resource
     * (`resource.text`) instead of a text block. A `terminal` block is shown only when the
     * bridge inlines `output` — the phone does not call terminal methods.
     */
    private fun toolOutputPiece(o: JsonObject): String? = when (o.str("type")) {
        "content" -> AcpMessageContent.textOf(o["content"])
        "text" -> o.str("text")?.takeIf { it.isNotEmpty() }
        "terminal" -> o.str("output")?.takeIf { it.isNotEmpty() }
            ?: o.str("text")?.takeIf { it.isNotEmpty() }
        "resource", "resource_link" -> AcpMessageContent.textOf(o)
        else -> null
    }

    private fun diffs(callId: String, content: JsonElement?, now: Long): List<CodeEvent.FileDiff> {
        val items = contentItems(content) ?: return emptyList()
        return items.flatMap { diffBlocks(callId, it, now) }
    }

    /**
     * One `type: diff` block. v1 is `path` plus old/new text or a unified string.
     * v2 is `changes[]` plus an optional `patch.text` (git patch). A missing path
     * is taken from the patch header so the edit is not dropped.
     */
    private fun diffBlocks(callId: String, o: JsonObject, now: Long): List<CodeEvent.FileDiff> {
        if (o.str("type") != "diff") return emptyList()
        val unified = unifiedOf(o)
        val changes = diffChanges(o)
        if (changes.isEmpty()) {
            val path = o.str("path")?.trim()?.ifEmpty { null } ?: unified?.let(::pathFromGitPatch) ?: return emptyList()
            return listOfNotNull(classicDiff(callId, path, o, unified, now))
        }
        val sections = if (unified.isNullOrBlank()) emptyList() else splitGitSections(unified)
        return changes.map { ch ->
            val section = pickSection(sections, unified, ch.path, changes.size)
            val parsed = section?.let { Diff.parseUnified(it) }.orEmpty()
            val lines = parsed.ifEmpty {
                listOf(DiffLine(DiffLine.Type.HUNK, null, null, ch.operation.ifBlank { "modify" }))
            }
            val (add, del) = Diff.counts(lines)
            val isNew = when (ch.operation) {
                "add" -> true
                "delete" -> false
                else -> section != null && Diff.unifiedIsNewFile(section)
            }
            CodeEvent.FileDiff("diff:$callId:${ch.path}", now, callId, ch.path, lines, add, del, isNewFile = isNew)
        }
    }

    private data class DiffChange(val path: String, val operation: String)

    private fun diffChanges(o: JsonObject): List<DiffChange> {
        val arr = o["changes"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            val path = c.str("path")?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            DiffChange(path, c.str("operation")?.trim()?.lowercase().orEmpty())
        }
    }

    /** Unified text from `diff`, a string `patch`, or v2 `patch.text`. */
    private fun unifiedOf(o: JsonObject): String? {
        o.str("diff")?.takeIf { it.isNotBlank() }?.let { return it }
        return when (val p = o["patch"]) {
            is JsonPrimitive -> if (p is JsonNull) null else p.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonObject -> p.str("text")?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun classicDiff(
        callId: String,
        path: String,
        o: JsonObject,
        unified: String?,
        now: Long,
    ): CodeEvent.FileDiff? {
        val newEl = o["newText"]
        val hasNew = newEl is JsonPrimitive && newEl !is JsonNull
        // Some agents send a unified patch instead of old/new file text.
        if (!hasNew && unified != null) {
            val lines = Diff.parseUnified(unified)
            if (lines.isEmpty()) return null
            val (add, del) = Diff.counts(lines)
            return CodeEvent.FileDiff(
                "diff:$callId:$path", now, callId, path, lines, add, del,
                isNewFile = Diff.unifiedIsNewFile(unified),
            )
        }
        // A non-string old/new text used to throw out of decode and drop the tool call with it.
        val old = when (val el = o["oldText"]) {
            null, is JsonNull -> null
            is JsonPrimitive -> el.contentOrNull
            else -> return null
        }
        val newText = when (val el = o["newText"]) {
            is JsonPrimitive -> if (el is JsonNull) return null else el.contentOrNull ?: return null
            else -> return null
        }
        val lines = Diff.between(old, newText)
        val (add, del) = Diff.counts(lines)
        return CodeEvent.FileDiff("diff:$callId:$path", now, callId, path, lines, add, del, isNewFile = old == null)
    }

    private fun splitGitSections(text: String): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("diff --git ") && cur.isNotEmpty()) {
                parts += cur.toString()
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(line)
        }
        if (cur.isNotEmpty()) parts += cur.toString()
        return parts
    }

    private fun pickSection(sections: List<String>, whole: String?, path: String, changeCount: Int): String? {
        if (sections.isEmpty()) return whole
        sections.firstOrNull { sectionMentions(it, path) }?.let { return it }
        if (sections.size == 1 && changeCount == 1) return sections[0]
        return null
    }

    private fun sectionMentions(section: String, path: String): Boolean {
        if (path.isEmpty()) return false
        return section.lineSequence().any { line ->
            (line.startsWith("diff ") || line.startsWith("--- ") || line.startsWith("+++ ")) &&
                (line.contains(path) || line.contains("b/$path") || line.contains("a/$path"))
        }
    }

    /** `diff --git a/x b/y` or a `+++` header, so a patch with no `path` field still opens. */
    private fun pathFromGitPatch(text: String): String? {
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("diff --git ")) {
                val b = line.removePrefix("diff --git ").split(' ').lastOrNull()?.let(::stripGitPath)
                if (!b.isNullOrEmpty() && b != "/dev/null") return b
            }
            if (line.startsWith("+++ ") && !line.contains("/dev/null")) {
                val rest = line.substring(4).substringBefore('\t').trim()
                val path = stripGitPath(rest)
                if (path.isNotEmpty()) return path
            }
        }
        return null
    }

    private fun stripGitPath(token: String): String {
        var t = token.trim().trim('"')
        if (t.startsWith("b/") || t.startsWith("a/")) t = t.substring(2)
        return t
    }

    /**
     * Null when the frame named neither kind nor name, so a status-only update
     * does not reset the icon. `kind: other` still yields to a recognized `name`.
     */
    private fun toolKindIfPresent(kind: String?, name: String?): ToolKind? {
        if (kind.isNullOrBlank() && name.isNullOrBlank()) return null
        return toolKind(namedKind(kind, name))
    }

    /** ACP kind, or the tool's programmatic name when kind is omitted or `other`. */
    private fun namedKind(kind: String?, name: String?): String? {
        val k = canonicalKind(kind)
        if (k != null && k != "other") return k
        val n = canonicalKind(name)
        if (n != null && n != "other") return n
        return k
    }

    private fun toolKind(s: String?) = when (canonicalKind(s)) {
        "read" -> ToolKind.READ
        "edit" -> ToolKind.EDIT
        "delete" -> ToolKind.DELETE
        "move" -> ToolKind.MOVE
        "search" -> ToolKind.SEARCH
        "execute" -> ToolKind.EXECUTE
        "think" -> ToolKind.THINK
        "fetch" -> ToolKind.FETCH
        else -> ToolKind.OTHER
    }

    /**
     * ACP writes `in_progress` and `failed`. Agents also send `running`, `error`, `done`,
     * and `cancelled`. An unknown token stays null so a status-only update does not invent a state.
     * `cancelled` is its own quiet stop: the spinner ends, and it is not drawn as a failure.
     */
    private fun toolStatus(s: String?) = when (normalizeKind(s)) {
        "pending", "queued" -> ToolStatus.PENDING
        "in_progress", "running", "inprogress" -> ToolStatus.RUNNING
        "completed", "complete", "success", "succeeded", "done", "finished" -> ToolStatus.COMPLETED
        "failed", "failure", "error", "errored" -> ToolStatus.FAILED
        "cancelled", "canceled" -> ToolStatus.CANCELLED
        else -> null
    }

    /**
     * Same spelling as [toolStatus] for the states a plan row can show.
     * Anything else stays pending so the step is still listed.
     */
    private fun planStatus(s: String?) = when (normalizeKind(s)) {
        "completed", "complete", "done", "success", "succeeded", "finished" -> PlanStatus.COMPLETED
        "in_progress", "running", "inprogress" -> PlanStatus.IN_PROGRESS
        else -> PlanStatus.PENDING
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun ignored(why: String) = listOf(AdapterOutput.Ignored(why))


    /**
     * ACP `available_commands_update.availableCommands[]`: name + description required;
     * optional unstructured `input.hint` becomes [AvailableCommand.inputHint].
     * Caller must pass a real [JsonArray] (including `[]`); missing/null/non-array is Ignored upstream.
     */
    private fun parseAvailableCommands(arr: JsonArray): List<AvailableCommand> {
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o.str("name")?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val description = o.str("description")?.trim().orEmpty()
            val hint = (o["input"] as? JsonObject)?.str("hint")?.trim()?.ifEmpty { null }
            AvailableCommand(name = name, description = description, inputHint = hint)
        }
    }

    companion object {
        /** Tool output kept per call. Reads keep the head; shell logs keep the tail. */
        const val MAX_OUTPUT = 4000
        private val SEQ_METHODS = setOf(
            "session/update", "session/request_permission", "bridge/permissionResolved", "bridge/sessionStatus",
            "cursor/update_todos", "cursor/ask_question", "cursor/create_plan",
        )
    }
}
