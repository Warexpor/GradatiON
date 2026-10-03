package io.github.stardomains3.oxproxion.code

import io.github.stardomains3.oxproxion.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Cursor Agent extension methods the phone has to answer, or the turn waits forever.
 *
 * `cursor/ask_question` and `cursor/create_plan` are requests. A single-choice question
 * and a plan approval use the same card as an ACP permission. Several questions at once
 * are skipped so the agent can continue. `cursor/update_todos` is a notification and
 * becomes the session's todo card. A later update with `merge` replaces rows by id.
 * `cancelled` stays cancelled (it is not rewritten as pending). A plan whose todos
 * live only under `phases` still fills that card.
 * `cursor/task` and `cursor/generate_image` are notifications. A request-shaped copy
 * is acknowledged so the agent is not told the method is missing.
 *
 * One JSON-RPC id is one live request. Agents restart that counter, so a later ACP
 * permission can reuse an id this class already answered. [release] drops the Cursor
 * shape; a failed send can still rebuild it until then.
 */
internal class CursorMethods {

    private val todos = ConcurrentHashMap<String, List<PlanEntry>>()
    /** requestId → the Cursor request still using that id. */
    private val requests = ConcurrentHashMap<String, CursorRequest>()

    fun clearSession(sessionId: String) {
        todos.remove(sessionKey(sessionId))
        requests.entries.removeIf { it.value.sessionId == sessionId }
    }

    /**
     * JSON-RPC result for a Cursor request this adapter turned into an approval.
     * Null when [requestId] is an ordinary ACP permission. Kept after the first
     * build so a send that failed can try the same frame again.
     */
    fun answer(requestId: String, optionId: String?): String? {
        val req = requests[requestId] ?: return null
        return when (req.kind) {
            CursorRequestKind.ASK -> askResult(requestId, req.questionId, optionId)
            CursorRequestKind.PLAN -> planResult(requestId, optionId)
        }
    }

    /**
     * This id now belongs to an ACP permission (or the session is gone). A later
     * Allow must use the permission result, not the question or plan that used it.
     */
    fun release(requestId: String) {
        requests.remove(requestId)
    }

    fun handle(
        method: String,
        sessionId: String,
        idEl: JsonElement?,
        params: JsonObject,
        seq: Long?,
        now: Long,
    ): List<AdapterOutput> = when (method) {
        "cursor/update_todos" -> updateTodos(sessionId, idEl, params, seq, now)
        "cursor/ask_question" -> askQuestion(sessionId, idEl, params, seq, now)
        "cursor/create_plan" -> createPlan(sessionId, idEl, params, seq, now)
        "cursor/task" -> ackTask(idEl, params)
        "cursor/generate_image" -> ackImage(idEl, params)
        else -> emptyList()
    }

    private fun updateTodos(
        sessionId: String,
        idEl: JsonElement?,
        params: JsonObject,
        seq: Long?,
        now: Long,
    ): List<AdapterOutput> {
        val incoming = todoEntries(params["todos"])
        val merged = merge(sessionId, incoming, params.bool("merge") == true)
        val update = AdapterOutput.Update(sessionId, CodeUpdate.Upsert(planEvent(now, merged)), seq)
        val id = rpcId(idEl) ?: return listOf(update)
        // This id is answered here. It must not stay a question or a plan.
        requests.remove(id)
        // A request-shaped notification still needs a result, or the agent waits.
        return listOf(update, AdapterOutput.Reply(acceptedTodos(id, merged)))
    }

    private fun askQuestion(
        sessionId: String,
        idEl: JsonElement?,
        params: JsonObject,
        seq: Long?,
        now: Long,
    ): List<AdapterOutput> {
        val requestId = rpcId(idEl) ?: return listOf(AdapterOutput.Ignored("cursor ask without id"))
        val questions = (params["questions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val only = questions.singleOrNull()
        val options = only?.let { choiceOptions(it) }
        if (only == null || options == null) {
            return skipAsk(sessionId, requestId, seq, now)
        }
        requests[requestId] = CursorRequest(
            CursorRequestKind.ASK,
            // Whole-number doubles (5.0 / "5.0") must match the answer's questionId as "5".
            rpcId(only["id"]) ?: "q",
            sessionId,
        )
        val title = params.str("title")?.trim()?.ifEmpty { null }
            ?: only.str("prompt")?.trim()?.ifEmpty { null }
            ?: "Question"
        val approval = CodeEvent.Approval(
            key = "approval:$requestId",
            at = now,
            requestId = requestId,
            callId = rpcId(params["toolCallId"]),
            title = title,
            detail = only.str("prompt")?.trim()?.takeIf { it.isNotEmpty() && it != title },
            kind = ToolKind.OTHER,
            options = options,
        )
        return listOf(AdapterOutput.Update(sessionId, CodeUpdate.Upsert(approval), seq))
    }

    private fun createPlan(
        sessionId: String,
        idEl: JsonElement?,
        params: JsonObject,
        seq: Long?,
        now: Long,
    ): List<AdapterOutput> {
        val requestId = rpcId(idEl) ?: return listOf(AdapterOutput.Ignored("cursor plan without id"))
        requests[requestId] = CursorRequest(CursorRequestKind.PLAN, "", sessionId)
        val entries = merge(sessionId, planTodos(params), merge = false)
        val title = params.str("name")?.trim()?.ifEmpty { null } ?: "Plan"
        val detail = params.str("overview")?.trim()?.ifEmpty { null }
            ?: params.str("plan")?.trim()?.ifEmpty { null }?.let(::clip)
        val approval = CodeEvent.Approval(
            key = "approval:$requestId",
            at = now,
            requestId = requestId,
            callId = rpcId(params["toolCallId"]),
            title = title,
            detail = detail,
            kind = ToolKind.THINK,
            options = listOf(
                ApprovalOption(ACCEPT, "Accept", ApprovalOption.Kind.ALLOW_ONCE),
                ApprovalOption(REJECT, "Reject", ApprovalOption.Kind.REJECT_ONCE),
            ),
        )
        return listOf(
            AdapterOutput.Update(sessionId, CodeUpdate.Upsert(planEvent(now, entries)), seq),
            AdapterOutput.Update(sessionId, CodeUpdate.Upsert(approval), seq),
        )
    }

    /**
     * One question the card can show. Multiple select, or more than one question, cannot.
     * Cursor sends `allow_multiple`; a camelCase copy is the same flag. Missing it used
     * to offer one option and answer as if the agent had asked for a single choice.
     */
    private fun choiceOptions(question: JsonObject): List<ApprovalOption>? {
        if (question.bool("allowMultiple") == true || question.bool("allow_multiple") == true) return null
        val opts = (question["options"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = rpcId(o["id"])?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            val label = o.str("label")?.trim()?.ifEmpty { null } ?: id
            ApprovalOption(id, label, ApprovalOption.Kind.ALLOW_ONCE)
        }
        return opts.takeIf { it.isNotEmpty() }
    }

    private fun skipAsk(sessionId: String, requestId: String, seq: Long?, now: Long): List<AdapterOutput> {
        requests.remove(requestId)
        val reply = AdapterOutput.Reply(askSkipped(requestId))
        if (sessionId.isBlank()) return listOf(reply)
        val notice = AdapterOutput.Update(
            sessionId,
            CodeUpdate.Upsert(
                CodeEvent.Notice(
                    "cursor-ask:$requestId",
                    now,
                    "This agent asked a question this screen can't answer. It was skipped.",
                    NoticeLevel.INFO,
                    textRes = R.string.code_notice_question_skipped,
                )
            ),
            seq,
        )
        return listOf(notice, reply)
    }

    private fun merge(sessionId: String, incoming: List<PlanEntry>, merge: Boolean): List<PlanEntry> {
        val key = sessionKey(sessionId)
        val next = if (!merge) incoming else {
            val order = LinkedHashMap<String, PlanEntry>()
            val anonymous = ArrayList<PlanEntry>()
            for (e in todos[key].orEmpty()) {
                val id = e.id
                if (id.isNullOrEmpty()) anonymous += e else order[id] = e
            }
            for (e in incoming) {
                val id = e.id
                if (id.isNullOrEmpty()) anonymous += e else order[id] = e
            }
            anonymous + order.values
        }
        todos[key] = next
        return next
    }

    private fun planEvent(now: Long, entries: List<PlanEntry>) =
        CodeEvent.Plan(TODO_KEY, now, entries)

    private fun sessionKey(sessionId: String) = sessionId.ifBlank { "*" }

    private fun todoEntries(el: JsonElement?): List<PlanEntry> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val content = o.str("content")?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            PlanEntry(content, planStatus(o.str("status")), rpcId(o["id"])?.trim()?.ifEmpty { null })
        }
    }

    private fun planStatus(s: String?) = when (s?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
        "completed", "complete", "done", "success", "succeeded", "finished" -> PlanStatus.COMPLETED
        "in_progress", "running", "inprogress" -> PlanStatus.IN_PROGRESS
        // Cursor's own status. Pending would put the row back on the list and,
        // on a request-shaped update, echo "pending" to the agent.
        "cancelled", "canceled" -> PlanStatus.CANCELLED
        else -> PlanStatus.PENDING
    }

    /** Top-level todos, or the ones grouped under `phases` when the top list is empty. */
    private fun planTodos(params: JsonObject): List<PlanEntry> {
        val top = todoEntries(params["todos"])
        if (top.isNotEmpty()) return top
        val phases = params["phases"] as? JsonArray ?: return emptyList()
        return phases.flatMap { phase ->
            val o = phase as? JsonObject ?: return@flatMap emptyList()
            todoEntries(o["todos"])
        }
    }

    /**
     * `cursor/task` tells us a subagent finished. A notification needs nothing.
     * A request-shaped frame (some builds still send an id) must not get
     * "Method not found", which the agent treats as the task failing.
     */
    private fun ackTask(idEl: JsonElement?, params: JsonObject): List<AdapterOutput> {
        val id = rpcId(idEl) ?: return emptyList()
        requests.remove(id)
        val outcome = buildJsonObject {
            put("outcome", "completed")
            rpcId(params["agentId"])?.let { put("agentId", it) }
            rpcId(params["durationMs"])?.let { put("durationMs", jsonRpcIdValue(it)) }
        }
        return listOf(AdapterOutput.Reply(rpc(id, buildJsonObject { put("outcome", outcome) })))
    }

    /** Same as [ackTask] for `cursor/generate_image`: ack with the path they already sent. */
    private fun ackImage(idEl: JsonElement?, params: JsonObject): List<AdapterOutput> {
        val id = rpcId(idEl) ?: return emptyList()
        requests.remove(id)
        val path = params.str("filePath")?.trim()?.ifEmpty { null }
            ?: params.str("file_path")?.trim()?.ifEmpty { null }
            ?: ""
        val outcome = buildJsonObject {
            put("outcome", "generated")
            put("filePath", path)
        }
        return listOf(AdapterOutput.Reply(rpc(id, buildJsonObject { put("outcome", outcome) })))
    }

    private fun askResult(requestId: String, questionId: String, optionId: String?): String {
        val outcome = if (optionId.isNullOrEmpty()) {
            buildJsonObject { put("outcome", "cancelled") }
        } else {
            buildJsonObject {
                put("outcome", "answered")
                put("answers", buildJsonArray {
                    add(buildJsonObject {
                        // Digit strings go out as JSON numbers, same as the request id.
                        put("questionId", jsonRpcIdValue(questionId))
                        put("selectedOptionIds", buildJsonArray { add(jsonRpcIdValue(optionId)) })
                    })
                })
            }
        }
        return rpc(requestId, buildJsonObject { put("outcome", outcome) })
    }

    private fun askSkipped(requestId: String) = rpc(
        requestId,
        buildJsonObject { put("outcome", buildJsonObject { put("outcome", "skipped") }) },
    )

    private fun planResult(requestId: String, optionId: String?): String {
        val name = if (optionId == ACCEPT) "accepted" else "rejected"
        return rpc(requestId, buildJsonObject { put("outcome", buildJsonObject { put("outcome", name) }) })
    }

    private fun acceptedTodos(requestId: String, entries: List<PlanEntry>) = rpc(
        requestId,
        buildJsonObject {
            put("outcome", buildJsonObject {
                put("outcome", "accepted")
                put("todos", buildJsonArray {
                    entries.forEach { e ->
                        add(buildJsonObject {
                            e.id?.let { put("id", jsonRpcIdValue(it)) }
                            put("content", e.content)
                            put("status", when (e.status) {
                                PlanStatus.COMPLETED -> "completed"
                                PlanStatus.IN_PROGRESS -> "in_progress"
                                PlanStatus.CANCELLED -> "cancelled"
                                PlanStatus.PENDING -> "pending"
                            })
                        })
                    }
                })
            })
        },
    )

    private fun rpc(id: String, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", jsonRpcIdValue(id))
        put("result", result)
    }.toString()

    /**
     * Numeric ids become `"9"` (not `"9.0"`); string ids stay as sent.
     * Same coercion for ask/plan `toolCallId`, question/option ids, and todo ids.
     */
    private fun rpcId(idEl: JsonElement?): String? {
        val p = idEl as? JsonPrimitive ?: return null
        wholeNumberLong(p)?.let { return it.toString() }
        return p.contentOrNull?.takeIf { it.isNotEmpty() }
    }

    private fun jsonRpcIdValue(id: String): JsonPrimitive {
        id.toLongOrNull()?.let { return JsonPrimitive(it) }
        id.toDoubleOrNull()?.takeIf {
            it.isFinite() && it == kotlin.math.floor(it) &&
                it in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
        }?.toLong()?.let { return JsonPrimitive(it) }
        return JsonPrimitive(id)
    }

    private fun wholeNumberLong(p: JsonPrimitive): Long? {
        p.longOrNull?.let { return it }
        p.doubleOrNull?.let { d ->
            if (d.isFinite() && d == kotlin.math.floor(d) &&
                d in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
            ) return d.toLong()
        }
        val c = p.contentOrNull ?: return null
        c.toLongOrNull()?.let { return it }
        return c.toDoubleOrNull()?.takeIf {
            it.isFinite() && it == kotlin.math.floor(it) &&
                it in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
        }?.toLong()
    }

    private fun clip(text: String) = if (text.length > PLAN_CHARS) text.take(PLAN_CHARS) + "…" else text

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

    private enum class CursorRequestKind { ASK, PLAN }

    private class CursorRequest(
        val kind: CursorRequestKind,
        val questionId: String,
        val sessionId: String,
    )

    companion object {
        const val TODO_KEY = "todos"
        private const val ACCEPT = "accept"
        private const val REJECT = "reject"
        private const val PLAN_CHARS = 1500
    }
}
