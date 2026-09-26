package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.AcpAdapter
import io.github.stardomains3.oxproxion.code.AdapterOutput
import io.github.stardomains3.oxproxion.code.ApprovalOption
import io.github.stardomains3.oxproxion.code.CodeEvent
import io.github.stardomains3.oxproxion.code.CodeUpdate
import io.github.stardomains3.oxproxion.code.Diff
import io.github.stardomains3.oxproxion.code.DiffLine
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.NewSessionRequest
import io.github.stardomains3.oxproxion.code.PermissionMode
import io.github.stardomains3.oxproxion.code.PlanStatus
import io.github.stardomains3.oxproxion.code.SessionStatus
import io.github.stardomains3.oxproxion.code.ToolKind
import io.github.stardomains3.oxproxion.code.ToolStatus
import io.github.stardomains3.oxproxion.code.TranscriptReducer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Code mode foundation: ACP decoding/encoding, the transcript reducer, and line diffs. */
class CodeProtocolTest {

    private val acp = AcpAdapter()

    private fun fold(frames: List<String>): List<CodeEvent> {
        var list = emptyList<CodeEvent>()
        frames.flatMap { acp.decode(it) }.forEach { out ->
            if (out is AdapterOutput.Update) list = TranscriptReducer.apply(list, out.update, now = 1L)
        }
        return list
    }

    private fun update(u: String) = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":$u}}"""

    @Test fun textChunksMergeIntoOneMessage() {
        val list = fold(listOf(
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hello"}}"""),
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":" world"}}""")
        ))
        assertEquals(1, list.size)
        val t = list[0] as CodeEvent.AgentText
        assertEquals("Hello world", t.text)
        assertTrue(t.streaming)
    }

    @Test fun toolCallBreaksTextAndUpdatesInPlace() {
        val list = fold(listOf(
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Let me look."}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Run tests","kind":"execute","status":"pending","rawInput":{"command":"npm test"}}"""),
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed","content":[{"type":"content","content":{"type":"text","text":"ok"}}]}"""),
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Done."}}""")
        ))
        assertEquals(3, list.size)
        val tool = list[1] as CodeEvent.ToolCall
        assertEquals(ToolKind.EXECUTE, tool.kind)
        assertEquals("npm test", tool.detail)
        assertEquals(ToolStatus.COMPLETED, tool.status)
        assertEquals("ok", tool.output)
        assertEquals("Done.", (list[2] as CodeEvent.AgentText).text)
    }

    @Test fun diffContentBecomesFileDiff() {
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"e1","title":"Edit a.kt","kind":"edit","status":"completed",
               "content":[{"type":"diff","path":"src/a.kt","oldText":"a\nb\nc","newText":"a\nB\nc"}]}"""
        )))
        val diff = list.filterIsInstance<CodeEvent.FileDiff>().single()
        assertEquals("src/a.kt", diff.path)
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
    }

    @Test fun planUpdatesOneCard() {
        val list = fold(listOf(
            update("""{"sessionUpdate":"plan","entries":[{"content":"A","status":"in_progress"},{"content":"B","status":"pending"}]}"""),
            update("""{"sessionUpdate":"plan","entries":[{"content":"A","status":"completed"},{"content":"B","status":"in_progress"}]}""")
        ))
        val plan = list.single() as CodeEvent.Plan
        assertEquals(PlanStatus.COMPLETED, plan.entries[0].status)
        assertEquals(PlanStatus.IN_PROGRESS, plan.entries[1].status)
    }

    @Test fun permissionRequestAndAnswer() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"s1",
            "toolCall":{"toolCallId":"t9","title":"rm -rf build","kind":"execute"},
            "options":[{"optionId":"a","name":"Allow","kind":"allow_once"},{"optionId":"r","name":"Deny","kind":"reject_once"}]}}""")
        val approval = ((out.single() as AdapterOutput.Update).update as CodeUpdate.Upsert).event as CodeEvent.Approval
        assertEquals("7", approval.requestId)
        assertEquals(2, approval.options.size)
        assertEquals(SessionStatus.NEEDS_APPROVAL, TranscriptReducer.statusOf(listOf(approval), running = true))

        val reply = Json.parseToJsonElement(acp.answerApproval("7", "a")).jsonObject
        assertEquals("7", reply["id"]!!.jsonPrimitive.content)
        val outcome = reply["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("selected", outcome["outcome"]!!.jsonPrimitive.content)
        assertEquals("a", outcome["optionId"]!!.jsonPrimitive.content)

        val answered = TranscriptReducer.apply(listOf(approval), CodeUpdate.ApprovalAnswered("7", ApprovalOption.Kind.ALLOW_ONCE))
        assertEquals(SessionStatus.RUNNING, TranscriptReducer.statusOf(answered, running = true))
    }

    @Test fun responsesMatchById() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":3,"result":{"sessionId":"abc"}}""").single() as AdapterOutput.Result
        assertEquals(3L, out.id)
        assertEquals("abc", out.result!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
        val err = acp.decode("""{"jsonrpc":"2.0","id":4,"error":{"code":-1,"message":"nope"}}""").single() as AdapterOutput.Result
        assertEquals("nope", err.error)
    }

    @Test fun garbageIsIgnoredNotThrown() {
        assertTrue(acp.decode("not json").single() is AdapterOutput.Ignored)
        assertTrue(acp.decode("""{"jsonrpc":"2.0","method":"something/else"}""").single() is AdapterOutput.Ignored)
    }

    @Test fun newSessionCarriesHarnessAndMode() {
        val frame = Json.parseToJsonElement(acp.newSession(1, NewSessionRequest("h", HarnessKind.CODEX, "/w", "hi", PermissionMode.PLAN))).jsonObject
        assertEquals("session/new", frame["method"]!!.jsonPrimitive.content)
        val params = frame["params"]!!.jsonObject
        assertEquals("/w", params["cwd"]!!.jsonPrimitive.content)
        assertEquals("codex", params["_meta"]!!.jsonObject["harness"]!!.jsonPrimitive.content)
        assertEquals("plan", params["_meta"]!!.jsonObject["permissionMode"]!!.jsonPrimitive.content)
    }

    @Test fun turnDoneClosesStreamingText() {
        var list = TranscriptReducer.apply(emptyList(), CodeUpdate.TextChunk("k", "hi"))
        list = TranscriptReducer.apply(list, CodeUpdate.TurnDone("end_turn", "1 file"))
        assertEquals(false, (list[0] as CodeEvent.AgentText).streaming)
        assertTrue(list[1] is CodeEvent.TurnEnd)
    }

    @Test fun diffBetweenKeepsContextAndNumbers() {
        val old = (1..20).joinToString("\n") { "line $it" }
        val new = old.replace("line 10", "line ten")
        val lines = Diff.between(old, new)
        assertEquals(DiffLine.Type.HUNK, lines.first().type)
        val del = lines.single { it.type == DiffLine.Type.DELETE }
        val add = lines.single { it.type == DiffLine.Type.ADD }
        assertEquals(10, del.oldNo)
        assertEquals(10, add.newNo)
        // 3 lines of context either side, nothing else.
        assertEquals(1 + 3 + 2 + 3, lines.size)
    }

    @Test fun diffNewFileIsAllAdds() {
        val lines = Diff.between(null, "a\nb")
        assertEquals(2, lines.count { it.type == DiffLine.Type.ADD })
    }

    @Test fun parseUnifiedDiff() {
        val lines = Diff.parseUnified("""
            --- a/x.txt
            +++ b/x.txt
            @@ -1,3 +1,3 @@ fun main
             one
            -two
            +TWO
             three
        """.trimIndent())
        assertEquals(listOf(DiffLine.Type.HUNK, DiffLine.Type.CONTEXT, DiffLine.Type.DELETE, DiffLine.Type.ADD, DiffLine.Type.CONTEXT), lines.map { it.type })
        assertEquals(2, lines[3].newNo)
    }
}
