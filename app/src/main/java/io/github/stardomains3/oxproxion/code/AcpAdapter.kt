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
 *   tool_call_update, plan), session/request_permission, and responses.
 *
 * Not yet: the terminal and fs client methods (the bridge answers those on the machine itself),
 * available_commands_update (slash commands), current_mode_update, images in prompts.
 */
class AcpAdapter : HarnessAdapter {

    override val protocol = "acp/1"

    private val json = Json { ignoreUnknownKeys = true }
    /** Keys of the currently open agent message / thought per session, so chunks merge. */
    private val openText = HashMap<String, String>()
    private val openThought = HashMap<String, String>()
    private var seq = 0L

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

    override fun loadSession(id: Long, sessionId: String, workspace: String) = request(id, "session/load", buildJsonObject {
        put("sessionId", sessionId)
        put("cwd", workspace)
        put("mcpServers", JsonArray(emptyList()))
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
            method == "session/update" -> decodeUpdate(obj["params"]?.jsonObject ?: return ignored("no params"))
            method == "session/request_permission" && idEl != null ->
                decodePermission(idEl, obj["params"]?.jsonObject ?: return ignored("no params"))
            method == null && idEl != null -> {
                val id = (idEl as? JsonPrimitive)?.longOrNull ?: return ignored("non-numeric id")
                val err = obj["error"]?.let { (it as? JsonObject)?.str("message") ?: it.toString() }
                listOf(AdapterOutput.Result(id, obj["result"], err))
            }
            else -> ignored("method $method")
        }
    }

    private fun decodeUpdate(params: JsonObject): List<AdapterOutput> {
        val sid = params.str("sessionId") ?: return ignored("no sessionId")
        val u = params["update"]?.jsonObject ?: return ignored("no update")
        val now = System.currentTimeMillis()
        val out: CodeUpdate? = when (u.str("sessionUpdate")) {
            "agent_message_chunk" -> {
                openThought.remove(sid)
                val key = openText.getOrPut(sid) { "text:${now}:${seq++}" }
                u["content"]?.jsonObject?.str("text")?.let { CodeUpdate.TextChunk(key, it) }
            }
            "agent_thought_chunk" -> {
                val key = openThought.getOrPut(sid) { "thought:${now}:${seq++}" }
                u["content"]?.jsonObject?.str("text")?.let { CodeUpdate.TextChunk(key, it, thought = true) }
            }
            "user_message_chunk" -> {
                // Replayed history (session/load) or a prompt sent from another device.
                closeText(sid)
                u["content"]?.jsonObject?.str("text")?.let {
                    CodeUpdate.Upsert(CodeEvent.UserPrompt("user:${now}:${seq++}", now, it))
                }
            }
            "tool_call" -> {
                closeText(sid)
                return toolCall(sid, u, now)
            }
            "tool_call_update" -> return toolCallUpdate(sid, u, now)
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
            else -> null
        }
        return if (out == null) ignored("update ${u.str("sessionUpdate")}") else listOf(AdapterOutput.Update(sid, out))
    }

    private fun toolCall(sid: String, u: JsonObject, now: Long): List<AdapterOutput> {
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
        )))
        diffs(callId, u["content"], now).forEach { result += AdapterOutput.Update(sid, CodeUpdate.Upsert(it)) }
        return result
    }

    private fun toolCallUpdate(sid: String, u: JsonObject, now: Long): List<AdapterOutput> {
        val callId = u.str("toolCallId") ?: return ignored("tool_call_update without id")
        val result = ArrayList<AdapterOutput>()
        result += AdapterOutput.Update(sid, CodeUpdate.ToolPatch(
            callId = callId,
            status = toolStatus(u.str("status")),
            title = u.str("title"),
            detail = detailOf(u),
            output = textContent(u["content"])
        ))
        diffs(callId, u["content"], now).forEach { result += AdapterOutput.Update(sid, CodeUpdate.Upsert(it)) }
        return result
    }

    private fun decodePermission(idEl: JsonElement, params: JsonObject): List<AdapterOutput> {
        val sid = params.str("sessionId") ?: return ignored("permission without session")
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
        ))))
    }

    /** A tool call or prompt boundary ends the current agent message, so the next text starts a new one. */
    private fun closeText(sid: String) {
        openText.remove(sid)
        openThought.remove(sid)
    }

    /** Call when a prompt's response arrives, so the next turn starts fresh text. */
    fun endTurn(sessionId: String) = closeText(sessionId)

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

    companion object {
        /** Tool output kept per call; the bridge can serve the full log on demand (plan). */
        const val MAX_OUTPUT = 4000
    }
}
