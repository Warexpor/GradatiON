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
import io.github.stardomains3.oxproxion.code.ReconnectBackoff
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

    private fun update(u: String, seq: Long? = null): String {
        val meta = if (seq != null) ""","_meta":{"seq":$seq}""" else ""
        return """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1"$meta,"update":$u}}"""
    }

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

    @Test fun listHarnessesAndBrowseFrames() {
        val harnesses = Json.parseToJsonElement(acp.listHarnesses(9)).jsonObject
        assertEquals("bridge/listHarnesses", harnesses["method"]!!.jsonPrimitive.content)
        assertEquals(9L, harnesses["id"]!!.jsonPrimitive.content.toLong())

        val browse = Json.parseToJsonElement(acp.browse(10, "/home/me/code")).jsonObject
        assertEquals("bridge/browse", browse["method"]!!.jsonPrimitive.content)
        assertEquals("/home/me/code", browse["params"]!!.jsonObject["path"]!!.jsonPrimitive.content)
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

    @Test fun textAndUserKeysDeriveFromBridgeSeq() {
        val frames = listOf(
            update("""{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"Hi"}}""", seq = 10),
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hello"}}""", seq = 11),
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"!"}}""", seq = 12),
            update("""{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"hmm"}}""", seq = 13)
        )
        val outs = frames.flatMap { acp.decode(it) }.filterIsInstance<AdapterOutput.Update>()
        assertEquals("user:10", ((outs[0].update as CodeUpdate.Upsert).event as CodeEvent.UserPrompt).key)
        assertEquals("text:11", (outs[1].update as CodeUpdate.TextChunk).key)
        assertEquals("text:11", (outs[2].update as CodeUpdate.TextChunk).key) // same open message
        assertEquals("thought:13", (outs[3].update as CodeUpdate.TextChunk).key)
        assertEquals(10L, outs[0].seq)
        assertEquals(13L, acp.lastSeq("s1"))
    }

    @Test fun afterSeqResumeContinuesWithoutDuplicating() {
        // Live stream up to seq 2, then session/load with afterSeq=2 delivers only newer frames.
        val a = AcpAdapter()
        var list = emptyList<CodeEvent>()
        fun apply(frames: List<String>) {
            frames.flatMap { a.decode(it) }.forEach { out ->
                if (out is AdapterOutput.Update) list = TranscriptReducer.apply(list, out.update, now = 1L)
            }
        }
        apply(listOf(
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"A"}}""", seq = 1),
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"B"}}""", seq = 2)
        ))
        assertEquals(2L, a.lastSeq("s1"))
        assertEquals("text:1", list.single().key)
        assertEquals("AB", (list[0] as CodeEvent.AgentText).text)

        // Reconnect resume: only seq > 2
        apply(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Read","kind":"read","status":"completed"}""", seq = 3),
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"C"}}""", seq = 4)
        ))
        assertEquals(3, list.size)
        assertEquals("AB", (list[0] as CodeEvent.AgentText).text)
        assertEquals("tool:t1", list[1].key)
        assertEquals("text:4", list[2].key)
        assertEquals("C", (list[2] as CodeEvent.AgentText).text)
        assertEquals(4L, a.lastSeq("s1"))
    }

    @Test fun upsertReplayDoesNotDuplicateToolsPlansApprovals() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"e1","title":"Edit","kind":"edit","status":"pending"}""", seq = 5),
            update("""{"sessionUpdate":"plan","entries":[{"content":"A","status":"pending"}]}""", seq = 6)
        )
        val permission = """{"jsonrpc":"2.0","id":9,"method":"session/request_permission","params":{"sessionId":"s1","_meta":{"seq":7},
            "toolCall":{"toolCallId":"e1","title":"Edit","kind":"edit"},
            "options":[{"optionId":"a","name":"Allow","kind":"allow_once"}]}}"""
        var list = emptyList<CodeEvent>()
        val a = AcpAdapter()
        repeat(2) {
            (frames + permission).flatMap { a.decode(it) }.forEach { out ->
                if (out is AdapterOutput.Update) list = TranscriptReducer.apply(list, out.update, now = 1L)
            }
        }
        assertEquals(3, list.size)
        assertEquals("tool:e1", list[0].key)
        assertEquals("plan:s1", list[1].key)
        assertEquals("approval:9", list[2].key)
        assertEquals(7L, a.lastSeq("s1"))
    }

    @Test fun loadSessionCarriesAfterSeq() {
        val with = Json.parseToJsonElement(acp.loadSession(2, "abc", "/w", afterSeq = 42)).jsonObject
        assertEquals("session/load", with["method"]!!.jsonPrimitive.content)
        val meta = with["params"]!!.jsonObject["_meta"]!!.jsonObject
        assertEquals("42", meta["afterSeq"]!!.jsonPrimitive.content)
        val without = Json.parseToJsonElement(acp.loadSession(3, "abc", "/w", null)).jsonObject
        assertEquals(null, without["params"]!!.jsonObject["_meta"])
    }

    @Test fun twoAdaptersSameSeqProduceSameKeys() {
        val frame = update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"x"}}""", seq = 99)
        val k1 = ((AcpAdapter().decode(frame).single() as AdapterOutput.Update).update as CodeUpdate.TextChunk).key
        val k2 = ((AcpAdapter().decode(frame).single() as AdapterOutput.Update).update as CodeUpdate.TextChunk).key
        assertEquals("text:99", k1)
        assertEquals(k1, k2)
    }

    @Test fun permissionResolvedMarksApprovalAnswered() {
        val a = AcpAdapter()
        val ask = a.decode("""{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"s1","_meta":{"seq":1},
            "toolCall":{"toolCallId":"t1","title":"rm","kind":"execute"},
            "options":[{"optionId":"a","name":"Allow","kind":"allow_once"}]}}""")
        var list = emptyList<CodeEvent>()
        (ask.single() as AdapterOutput.Update).let { list = TranscriptReducer.apply(list, it.update, now = 1L) }
        assertEquals(null, (list.single() as CodeEvent.Approval).chosen)

        val resolved = a.decode("""{"jsonrpc":"2.0","method":"bridge/permissionResolved","params":{"sessionId":"s1","requestId":"7","optionKind":"allow_once","_meta":{"seq":2}}}""")
        val upd = (resolved.single() as AdapterOutput.Update).update as CodeUpdate.ApprovalAnswered
        assertEquals("7", upd.requestId)
        assertEquals(ApprovalOption.Kind.ALLOW_ONCE, upd.chosen)
        list = TranscriptReducer.apply(list, upd, now = 2L)
        assertEquals(ApprovalOption.Kind.ALLOW_ONCE, (list.single() as CodeEvent.Approval).chosen)
        assertEquals(2L, a.lastSeq("s1"))
    }

    @Test fun sessionStatusProducesSessionInfo() {
        val out = acp.decode("""{"jsonrpc":"2.0","method":"bridge/sessionStatus","params":{
            "sessionId":"s1","status":"running","title":"Fix footer","preview":"editing","branch":"main","_meta":{"seq":9}}}""")
        val upd = (out.single() as AdapterOutput.Update)
        assertEquals(9L, upd.seq)
        val info = upd.update as CodeUpdate.SessionInfo
        assertEquals(SessionStatus.RUNNING, info.status)
        assertEquals("Fix footer", info.title)
        assertEquals("editing", info.preview)
        assertEquals("main", info.branch)
        assertEquals(9L, acp.lastSeq("s1"))
    }

    @Test fun reconnectBackoffCapsAndJitters() {
        assertEquals(0L, ReconnectBackoff.delayMs(0, 0.0))
        assertEquals(500L, ReconnectBackoff.delayMs(0, 1.0))
        assertEquals(1000L, ReconnectBackoff.delayMs(1, 1.0))
        assertEquals(30_000L, ReconnectBackoff.delayMs(10, 1.0))
        assertEquals(30_000L, ReconnectBackoff.delayMs(20, 1.0))
        val mid = ReconnectBackoff.delayMs(3, 0.5)
        assertEquals(2000L, mid) // 0.5s * 8 * 0.5
        assertEquals(60_000L, ReconnectBackoff.RESET_AFTER_CONNECTED_MS)
    }
}
