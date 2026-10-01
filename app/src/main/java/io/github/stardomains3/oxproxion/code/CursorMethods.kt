package io.github.stardomains3.oxproxion.code

import io.github.stardomains3.oxproxion.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
            only.str("id") ?: "q",
            sessionId,
        )
        val title = params.str("title")?.trim()?.ifEmpty { null }
            ?: only.str("prompt")?.trim()?.ifEmpty { null }
            ?: "Question"
        val approval = CodeEvent.Approval(
            key = "approval:$requestId",
            at = now,
            requestId = requestId,
            callId = params.str("toolCallId"),
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
        val entries = merge(sessionId, todoEntries(params["todos"]), merge = false)
        val title = params.str("name")?.trim()?.ifEmpty { null } ?: "Plan"
        val detail = params.str("overview")?.trim()?.ifEmpty { null }
            ?: params.str("plan")?.trim()?.ifEmpty { null }?.let(::clip)
        val approval = CodeEvent.Approval(
            key = "approval:$requestId",
            at = now,
            requestId = requestId,
            callId = params.str("toolCallId"),
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

    /** One question the card can show. Multiple select, or more than one question, cannot. */
    private fun choiceOptions(question: JsonObject): List<ApprovalOption>? {
        if (question.bool("allowMultiple") == true) return null
        val opts = (question["options"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.trim()?.ifEmpty { null } ?: return@mapNotNull null
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
            PlanEntry(content, planStatus(o.str("status")), o.str("id")?.trim()?.ifEmpty { null })
        }
    }

    private fun planStatus(s: String?) = when (s?.trim()?.lowercase()?.replace('-', '_')) {
        "completed", "complete", "done" -> PlanStatus.COMPLETED
        "in_progress", "running", "inprogress" -> PlanStatus.IN_PROGRESS
        else -> PlanStatus.PENDING
    }

    private fun askResult(requestId: String, questionId: String, optionId: String?): String {
        val outcome = if (optionId.isNullOrEmpty()) {
            buildJsonObject { put("outcome", "cancelled") }
        } else {
            buildJsonObject {
                put("outcome", "answered")
                put("answers", buildJsonArray {
                    add(buildJsonObject {
                        put("questionId", questionId)
                        put("selectedOptionIds", buildJsonArray { add(JsonPrimitive(optionId)) })
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
                            e.id?.let { put("id", it) }
                            put("content", e.content)
                            put("status", when (e.status) {
                                PlanStatus.COMPLETED -> "completed"
                                PlanStatus.IN_PROGRESS -> "in_progress"
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
        put("id", id.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(id))
        put("result", result)
    }.toString()

    private fun rpcId(idEl: JsonElement?): String? =
        (idEl as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

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
