package io.github.stardomains3.oxproxion.code

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

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
 * Not yet: the terminal and fs client methods (the bridge answers those on the machine itself),
 * current_mode_update, images in prompts.
 * Slash commands: `available_commands_update` → [CodeUpdate.AvailableCommands].
 */
class AcpAdapter : HarnessAdapter {

    override val protocol = "acp/1"

    private val json = Json { ignoreUnknownKeys = true }
    /** Keys of the currently open agent message / thought per session, so chunks merge. */
    private val openText = HashMap<String, String>()
    private val openThought = HashMap<String, String>()
    /** Highest bridge `_meta.seq` seen per session (for session/load afterSeq resume). */
    private val lastSeqBySession = HashMap<String, Long>()
    /** Fallback key counter when a frame has no `_meta.seq` (non-bridge / tests). */
    private var localKeyCounter = 0L

    // ── outbound ──────────────────────────────────────────────────────────────────────────

    override fun initialize(id: Long) = request(id, "initialize", buildJsonObject {
        put("protocolVersion", 1)
        put("clientCapabilities", buildJsonObject {
            // The bridge owns the filesystem and terminals on the machine; the phone never does.
            put("fs", buildJsonObject { put("readTextFile", false); put("writeTextFile", false) })
            put("terminal", false)
        })
        put("clientInfo", buildJsonObject { put("name", "GradatiON"); put("version", "1") })
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

    override fun prompt(id: Long, sessionId: String, text: String) = request(id, "session/prompt", buildJsonObject {
        put("sessionId", sessionId)
        put("prompt", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
    })

    override fun cancel(sessionId: String) = notification("session/cancel", buildJsonObject { put("sessionId", sessionId) })

    override fun setMode(id: Long, sessionId: String, mode: PermissionMode) = request(id, "session/set_mode", buildJsonObject {
        put("sessionId", sessionId)
        put("modeId", mode.id)
    })

    override fun answerApproval(requestId: String, optionId: String?): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", requestId.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(requestId))
        put("result", buildJsonObject {
            put("outcome", buildJsonObject {
                if (optionId == null) put("outcome", "cancelled")
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
        val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrElse {
            return listOf(AdapterOutput.Ignored("not JSON"))
        }
        val method = obj.str("method")
        val idEl = obj["id"]
        return when {
            method == "session/update" -> {
                val params = obj["params"]?.jsonObject ?: return ignored("no params")
                decodeUpdate(params, bridgeSeq(params, obj))
            }
            method == "session/request_permission" && idEl != null -> {
                val params = obj["params"]?.jsonObject ?: return ignored("no params")
                decodePermission(idEl, params, bridgeSeq(params, obj))
            }
            method == "bridge/permissionResolved" -> {
                val params = obj["params"]?.jsonObject ?: return ignored("no params")
                decodePermissionResolved(params, bridgeSeq(params, obj))
            }
            method == "bridge/sessionStatus" -> {
                val params = obj["params"]?.jsonObject ?: return ignored("no params")
                decodeSessionStatus(params, bridgeSeq(params, obj))
            }
            method == null && idEl != null -> {
                val id = (idEl as? JsonPrimitive)?.longOrNull ?: return ignored("non-numeric id")
                val err = obj["error"]?.let { (it as? JsonObject)?.str("message") ?: it.toString() }
                listOf(AdapterOutput.Result(id, obj["result"], err))
            }
            else -> ignored("method $method")
        }
    }

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

    private fun optionKind(s: String?): ApprovalOption.Kind = when (s) {
        "allow_always" -> ApprovalOption.Kind.ALLOW_ALWAYS
        "reject_once" -> ApprovalOption.Kind.REJECT_ONCE
        "reject_always" -> ApprovalOption.Kind.REJECT_ALWAYS
        "allow_once" -> ApprovalOption.Kind.ALLOW_ONCE
        else -> ApprovalOption.Kind.ALLOW_ONCE
    }

    private fun decodeUpdate(params: JsonObject, seq: Long?): List<AdapterOutput> {
        val sid = params.str("sessionId") ?: return ignored("no sessionId")
        val u = params["update"]?.jsonObject ?: return ignored("no update")
        noteSeq(sid, seq)
        val now = System.currentTimeMillis()
        val out: CodeUpdate? = when (u.str("sessionUpdate")) {
            "agent_message_chunk" -> {
                openThought.remove(sid)
                // Key from the first chunk's bridge seq so session/load replay upserts, not duplicates.
                val key = openText.getOrPut(sid) { stableKey("text", seq) }
                u["content"]?.jsonObject?.str("text")?.let { CodeUpdate.TextChunk(key, it) }
            }
            "agent_thought_chunk" -> {
                val key = openThought.getOrPut(sid) { stableKey("thought", seq) }
                u["content"]?.jsonObject?.str("text")?.let { CodeUpdate.TextChunk(key, it, thought = true) }
            }
            "user_message_chunk" -> {
                // Replayed history (session/load) or a prompt sent from another device.
                closeText(sid)
                u["content"]?.jsonObject?.str("text")?.let {
                    CodeUpdate.Upsert(CodeEvent.UserPrompt(stableKey("user", seq), now, it))
                }
            }
            "tool_call" -> {
                closeText(sid)
                return toolCall(sid, u, now, seq)
            }
            "tool_call_update" -> return toolCallUpdate(sid, u, now, seq)
            "plan" -> {
                val entries = u["entries"]?.jsonArray?.mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    PlanEntry(o.str("content") ?: "", when (o.str("status")) {
                        "completed" -> PlanStatus.COMPLETED
                        "in_progress" -> PlanStatus.IN_PROGRESS
                        else -> PlanStatus.PENDING
                    })
                }.orEmpty()
                // One live plan card per session: same key, so it updates in place.
                CodeUpdate.Upsert(CodeEvent.Plan("plan:$sid", now, entries))
            }
            "available_commands_update" -> {
                val arr = u["availableCommands"] as? JsonArray
                    ?: return ignored("available_commands_update without availableCommands array")
                CodeUpdate.AvailableCommands(parseAvailableCommands(arr))
            }
            else -> null
        }
        return if (out == null) ignored("update ${u.str("sessionUpdate")}")
        else listOf(AdapterOutput.Update(sid, out, seq))
    }

    private fun toolCall(sid: String, u: JsonObject, now: Long, seq: Long?): List<AdapterOutput> {
        val callId = u.str("toolCallId") ?: return ignored("tool_call without id")
        val kind = toolKind(u.str("kind"))
        val result = ArrayList<AdapterOutput>()
        result += AdapterOutput.Update(sid, CodeUpdate.Upsert(CodeEvent.ToolCall(
            key = "tool:$callId",
            at = now,
            callId = callId,
            kind = kind,
            title = u.str("title") ?: "Tool call",
            detail = detailOf(u),
            status = toolStatus(u.str("status")) ?: ToolStatus.PENDING,
            output = textContent(u["content"])
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
            detail = detailOf(u),
            output = textContent(u["content"])
        ), seq)
        diffs(callId, u["content"], now).forEach {
            result += AdapterOutput.Update(sid, CodeUpdate.Upsert(it), seq)
        }
        return result
    }

    private fun decodePermission(idEl: JsonElement, params: JsonObject, seq: Long?): List<AdapterOutput> {
        val sid = params.str("sessionId") ?: return ignored("permission without session")
        noteSeq(sid, seq)
        val call = params["toolCall"]?.jsonObject
        val requestId = (idEl as JsonPrimitive).content
        val options = params["options"]?.jsonArray?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val kind = when (o.str("kind")) {
                "allow_always" -> ApprovalOption.Kind.ALLOW_ALWAYS
                "reject_once" -> ApprovalOption.Kind.REJECT_ONCE
                "reject_always" -> ApprovalOption.Kind.REJECT_ALWAYS
                else -> ApprovalOption.Kind.ALLOW_ONCE
            }
            ApprovalOption(o.str("optionId") ?: return@mapNotNull null, o.str("name") ?: kind.name, kind)
        }.orEmpty()
        val now = System.currentTimeMillis()
        closeText(sid)
        return listOf(AdapterOutput.Update(sid, CodeUpdate.Upsert(CodeEvent.Approval(
            key = "approval:$requestId",
            at = now,
            requestId = requestId,
            callId = call?.str("toolCallId"),
            title = call?.str("title") ?: "The agent wants to continue",
            detail = call?.let { detailOf(it) },
            kind = toolKind(call?.str("kind")),
            options = options
        )), seq))
    }

    /** A tool call or prompt boundary ends the current agent message, so the next text starts a new one. */
    private fun closeText(sid: String) {
        openText.remove(sid)
        openThought.remove(sid)
    }

    /** Call when a prompt's response arrives, so the next turn starts fresh text. */
    fun endTurn(sessionId: String) = closeText(sessionId)

    /** Highest bridge `_meta.seq` seen for [sessionId], or null if none yet. */
    fun lastSeq(sessionId: String): Long? = lastSeqBySession[sessionId]

    /** Restore a resume cursor from Room after process death (keeps the higher value). */
    fun seedLastSeq(sessionId: String, seq: Long) {
        val prev = lastSeqBySession[sessionId]
        if (prev == null || seq > prev) lastSeqBySession[sessionId] = seq
    }

    /**
     * Stable event key: prefer bridge `_meta.seq` (reconnect/resume safe). Tool/approval/plan keys
     * use ACP ids instead. Without seq (plain ACP / older fixtures), fall back to a local counter.
     */
    private fun stableKey(kind: String, bridgeSeq: Long?): String {
        val n = bridgeSeq ?: localKeyCounter++
        return "$kind:$n"
    }

    private fun noteSeq(sessionId: String, seq: Long?) {
        if (seq == null) return
        val prev = lastSeqBySession[sessionId]
        if (prev == null || seq > prev) lastSeqBySession[sessionId] = seq
    }

    /** Bridge (or top-level) `_meta.seq` on a notification / permission request. */
    private fun bridgeSeq(params: JsonObject, root: JsonObject): Long? =
        metaSeq(params) ?: metaSeq(root)

    private fun metaSeq(obj: JsonObject): Long? {
        val meta = obj["_meta"] as? JsonObject ?: return null
        val el = meta["seq"] as? JsonPrimitive ?: return null
        return el.longOrNull ?: el.contentOrNull?.toLongOrNull()
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private fun detailOf(u: JsonObject): String? {
        u["locations"]?.let { locs ->
            (locs as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("path") }?.let { return it }
        }
        val raw = u["rawInput"] as? JsonObject ?: return null
        return raw.str("command") ?: raw.str("cmd") ?: raw.str("file_path") ?: raw.str("path")
            ?: raw.str("pattern") ?: raw.str("query") ?: raw.str("url")
    }

    private fun textContent(content: JsonElement?): String? {
        val arr = content as? JsonArray ?: return null
        val text = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o.str("type") == "content") o["content"]?.jsonObject?.str("text") else null
        }.joinToString("\n")
        return text.ifEmpty { null }?.let { if (it.length > MAX_OUTPUT) "…" + it.takeLast(MAX_OUTPUT) else it }
    }

    private fun diffs(callId: String, content: JsonElement?, now: Long): List<CodeEvent.FileDiff> {
        val arr = content as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o.str("type") != "diff") return@mapNotNull null
            val path = o.str("path") ?: return@mapNotNull null
            val old = o["oldText"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
            val lines = Diff.between(old, o.str("newText") ?: "")
            val (add, del) = Diff.counts(lines)
            CodeEvent.FileDiff("diff:$callId:$path", now, callId, path, lines, add, del, isNewFile = old == null)
        }
    }

    private fun toolKind(s: String?) = when (s) {
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

    private fun toolStatus(s: String?) = when (s) {
        "pending" -> ToolStatus.PENDING
        "in_progress" -> ToolStatus.RUNNING
        "completed" -> ToolStatus.COMPLETED
        "failed" -> ToolStatus.FAILED
        else -> null
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
        /** Tool output kept per call (tail). No bridge full-log RPC yet; phone shows this only. */
        const val MAX_OUTPUT = 4000
    }
}
