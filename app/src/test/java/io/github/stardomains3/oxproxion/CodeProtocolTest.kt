package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.AcpAdapter
import io.github.stardomains3.oxproxion.code.InboundSession
import io.github.stardomains3.oxproxion.code.AcpHandshake
import io.github.stardomains3.oxproxion.code.AgentInlineImage
import io.github.stardomains3.oxproxion.code.AvailableCommand
import io.github.stardomains3.oxproxion.code.CodeComposer
import io.github.stardomains3.oxproxion.code.AdapterOutput
import io.github.stardomains3.oxproxion.code.ApprovalOption
import io.github.stardomains3.oxproxion.code.CodeEvent
import io.github.stardomains3.oxproxion.code.CodeUpdate
import io.github.stardomains3.oxproxion.code.Diff
import io.github.stardomains3.oxproxion.code.DiffLine
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.NewSessionRequest
import io.github.stardomains3.oxproxion.code.PermissionMode
import io.github.stardomains3.oxproxion.code.PromptAttachment
import io.github.stardomains3.oxproxion.code.CodePromptImages
import io.github.stardomains3.oxproxion.code.ReconnectBackoff
import io.github.stardomains3.oxproxion.code.PlanStatus
import io.github.stardomains3.oxproxion.code.SessionStatus
import io.github.stardomains3.oxproxion.code.ToolKind
import io.github.stardomains3.oxproxion.code.ToolStatus
import io.github.stardomains3.oxproxion.code.TranscriptReducer
import io.github.stardomains3.oxproxion.code.CodeHost
import io.github.stardomains3.oxproxion.code.DemoBackend
import io.github.stardomains3.oxproxion.code.SessionUpdate
import io.github.stardomains3.oxproxion.code.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
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

    @Test fun agentMessageChunkTextPlusImageMerges() {
        // Tiny 1×1 PNG base64
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVQI12P4z8AAAAADAAEABf4C/gAAAABJRU5ErkJggg=="
        val list = fold(listOf(
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"See this:"}}"""),
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"image","mimeType":"image/png","data":"$png"}}""")
        ))
        assertEquals(1, list.size)
        val t = list[0] as CodeEvent.AgentText
        assertEquals("See this:", t.text)
        assertEquals(1, t.images.size)
        assertEquals("image/png", t.images[0].mimeType)
        assertEquals(png, t.images[0].data)
        assertTrue(t.streaming)
    }

    @Test fun agentMessageChunkImageOnly() {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVQI12P4z8AAAAADAAEABf4C/gAAAABJRU5ErkJggg=="
        val list = fold(listOf(
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"image","mimeType":"image/png","data":"$png"}}""")
        ))
        assertEquals(1, list.size)
        val t = list[0] as CodeEvent.AgentText
        assertEquals("", t.text)
        assertEquals(1, t.images.size)
        assertEquals("image/png", t.images[0].mimeType)
        assertEquals(png, t.images[0].data)
        assertEquals(
            CodePromptImages.inlineCacheKey("${t.key}#0", "image/png", png.length),
            t.images[0].cacheKey,
        )
        assertTrue(t.streaming)
    }

    @Test fun agentMessageChunkImageMissingDataIgnored() {
        val outs = acp.decode(update(
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"image","mimeType":"image/png"}}"""
        ))
        assertTrue(outs.single() is AdapterOutput.Ignored)
    }

    @Test fun agentMessageChunkUriOnlyImageIgnored() {
        // MVP: no remote URI loading — uri without data is ignored.
        val outs = acp.decode(update(
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"image","mimeType":"image/png","uri":"https://evil.example/x.png"}}"""
        ))
        assertTrue(outs.single() is AdapterOutput.Ignored)
    }

    @Test fun agentMessageChunkGifMimeIgnored() {
        // I4: parse allowlist matches decode (jpeg/png/webp) — gif must not consume a slot.
        val gif = "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
        val outs = acp.decode(update(
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"image","mimeType":"image/gif","data":"$gif"}}"""
        ))
        val ign = outs.single() as AdapterOutput.Ignored
        assertTrue(ign.reason.contains("mime"))
        val list = fold(listOf(
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"image","mimeType":"image/gif","data":"$gif"}}"""),
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"image","mimeType":"image/png","data":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVQI12P4z8AAAAADAAEABf4C/gAAAABJRU5ErkJggg=="}}"""),
        ))
        val t = list.single() as CodeEvent.AgentText
        assertEquals(1, t.images.size)
        assertEquals("image/png", t.images[0].mimeType)
    }

    @Test fun agentMessageChunkOversizedImageIgnored() {
        // I2: parse-time size gate — oversized base64 must not enter the session model.
        val big = "A".repeat(CodePromptImages.MAX_INLINE_BASE64_CHARS + 1)
        val outs = acp.decode(update(
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"image","mimeType":"image/png","data":"$big"}}"""
        ))
        val ign = outs.single() as AdapterOutput.Ignored
        assertTrue(ign.reason.contains("too large"))
        val list = fold(listOf(update(
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"image","mimeType":"image/png","data":"$big"}}"""
        )))
        assertTrue(list.none { it is CodeEvent.AgentText && it.images.isNotEmpty() })
    }

    @Test fun inlineCacheKeyUsesBlockIdNotBase64Sample() {
        // IMAGE-01: same mime/length/head+tail sample must not collide when block ids differ.
        val head = "A".repeat(64)
        val tail = "B".repeat(64)
        val d1 = head + "X".repeat(128) + tail
        val d2 = head + "Y".repeat(128) + tail
        assertEquals(d1.length, d2.length)
        val k1 = CodePromptImages.inlineCacheKey("text:1#0", "image/png", d1.length)
        val k2 = CodePromptImages.inlineCacheKey("text:1#1", "image/png", d2.length)
        assertNotEquals(k1, k2)
        assertEquals(k1, CodePromptImages.inlineCacheKey("text:1#0", "image/png", d1.length))
        // Content digest distinguishes payloads even when samples match.
        assertNotEquals(
            CodePromptImages.inlineContentDigest(d1),
            CodePromptImages.inlineContentDigest(d2),
        )
    }

    @Test fun twoAgentImagesSameSampleGetDistinctCacheKeys() {
        // IMAGE-01: reducer assigns messageKey#index so LruCache cannot reuse the wrong bitmap.
        val head = "A".repeat(64)
        val tail = "B".repeat(64)
        val d1 = head + "X".repeat(128) + tail
        val d2 = head + "Y".repeat(128) + tail
        val list = fold(listOf(
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"image","mimeType":"image/png","data":"$d1"}}"""),
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"image","mimeType":"image/png","data":"$d2"}}"""),
        ))
        val t = list.single() as CodeEvent.AgentText
        assertEquals(2, t.images.size)
        assertNotEquals(t.images[0].cacheKey, t.images[1].cacheKey)
        assertEquals(
            CodePromptImages.inlineCacheKey("${t.key}#0", "image/png", d1.length),
            t.images[0].cacheKey,
        )
        assertEquals(
            CodePromptImages.inlineCacheKey("${t.key}#1", "image/png", d2.length),
            t.images[1].cacheKey,
        )
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

    @Test fun executeDetailKeepsCommandWhenLocationIsTheFolder() {
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Bash","kind":"execute","status":"pending",
               "locations":[{"path":"/home/me/repo"}],
               "rawInput":{"command":"npm test"}}"""
        )))
        assertEquals("npm test", (list.single() as CodeEvent.ToolCall).detail)
    }

    @Test fun executeDetailJoinsArgvCommand() {
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Bash","kind":"execute","status":"pending",
               "rawInput":{"command":["./gradlew",":app:testDebugUnitTest"]}}"""
        )))
        assertEquals("./gradlew :app:testDebugUnitTest", (list.single() as CodeEvent.ToolCall).detail)
    }

    @Test fun executeDetailJoinsCommandAndArgs() {
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Bash","kind":"execute","status":"pending",
               "rawInput":{"command":"git","args":["commit","-m","hello world"]}}"""
        )))
        assertEquals("git commit -m \"hello world\"", (list.single() as CodeEvent.ToolCall).detail)
    }

    @Test fun stringifiedRawInputStillShowsTheCommand() {
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Bash","kind":"execute","status":"pending",
               "rawInput":"{\"command\":\"npm test\"}"}"""
        )))
        assertEquals("npm test", (list.single() as CodeEvent.ToolCall).detail)
    }

    @Test fun readDetailAcceptsTargetFile() {
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"r1","title":"Read","kind":"read","status":"completed",
               "rawInput":{"target_file":"src/A.kt"}}"""
        )))
        assertEquals("src/A.kt", (list.single() as CodeEvent.ToolCall).detail)
    }

    @Test fun toolUpdateWithoutAFirstCallStillShowsTheCard() {
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call_update","toolCallId":"late","title":"Bash","kind":"execute","status":"in_progress",
               "rawInput":{"command":"npm test"}}"""
        )))
        val tool = list.single() as CodeEvent.ToolCall
        assertEquals("tool:late", tool.key)
        assertEquals(ToolKind.EXECUTE, tool.kind)
        assertEquals("npm test", tool.detail)
        assertEquals(ToolStatus.RUNNING, tool.status)
    }

    @Test fun toolUpdateSetsKindWhenTheFirstCallOmittedIt() {
        val list = fold(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Bash","status":"pending"}"""),
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"t1","kind":"execute","status":"completed","rawInput":{"command":"npm test"}}"""),
        ))
        val tool = list.single() as CodeEvent.ToolCall
        assertEquals(ToolKind.EXECUTE, tool.kind)
        assertEquals("npm test", tool.detail)
        assertEquals(ToolStatus.COMPLETED, tool.status)
    }

    @Test fun statusOnlyUpdateKeepsTheKind() {
        val list = fold(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Read","kind":"read","status":"pending","rawInput":{"file_path":"A.kt"}}"""),
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed"}"""),
        ))
        val tool = list.single() as CodeEvent.ToolCall
        assertEquals(ToolKind.READ, tool.kind)
        assertEquals("A.kt", tool.detail)
    }

    @Test fun readDetailIncludesLineAndCapsExtraLocations() {
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"r1","title":"Read","kind":"read","status":"completed",
               "locations":[
                 {"path":"src/A.kt","line":18},
                 {"path":"src/B.kt","line":2},
                 {"path":"src/C.kt","line":3},
                 {"path":"src/D.kt","line":4}
               ],
               "rawInput":{"file_path":"src/A.kt"}}"""
        )))
        assertEquals("src/A.kt:18 · src/B.kt:2 · src/C.kt:3 +1", (list.single() as CodeEvent.ToolCall).detail)
    }

    @Test fun malformedDiffTextDoesNotDropTheToolCall() {
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"e1","title":"Edit","kind":"edit","status":"completed",
               "content":[
                 {"type":"diff","path":"a.kt","oldText":{"nope":true},"newText":"x"},
                 {"type":"content","content":{"type":"text","text":"kept"}}
               ]}"""
        )))
        assertTrue(list.none { it is CodeEvent.FileDiff })
        val tool = list.filterIsInstance<CodeEvent.ToolCall>().single()
        assertEquals("kept", tool.output)
    }

    @Test fun agentResourceLinkAppendsOnItsOwnLine() {
        val list = fold(listOf(
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"See"}}""", seq = 1),
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"resource_link","name":"a.kt","uri":"file:///a.kt"}}""", seq = 2),
        ))
        assertEquals("See\na.kt (file:///a.kt)", (list.single() as CodeEvent.AgentText).text)
    }

    @Test fun agentEmbeddedResourceShowsFileText() {
        val list = fold(listOf(update(
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"resource","resource":{"uri":"file:///a.kt","text":"fun main() {}"}}}"""
        )))
        assertEquals("fun main() {}", (list.single() as CodeEvent.AgentText).text)
    }

    @Test fun userImageOnlyPromptStillShows() {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVQI12P4z8AAAAADAAEABf4C/gAAAABJRU5ErkJggg=="
        val list = fold(listOf(update(
            """{"sessionUpdate":"user_message_chunk","content":{"type":"image","mimeType":"image/png","data":"$png"}}"""
        )))
        val prompt = list.single() as CodeEvent.UserPrompt
        assertEquals("", prompt.text)
        assertEquals(1, prompt.attachmentCount)
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

    @Test fun malformedPermissionIdIsIgnoredNotThrown() {
        // R6: non-primitive JSON-RPC id must not ClassCastException out of decode.
        val outs = acp.decode(
            """{"jsonrpc":"2.0","id":{"nested":true},"method":"session/request_permission","params":{"sessionId":"s1","options":[]}}"""
        )
        assertTrue(outs.single() is AdapterOutput.Ignored)
    }

    @Test fun malformedUpdateContentIsIgnoredNotThrown() {
        // R6: content that is not an object must not throw from .jsonObject.
        val outs = acp.decode(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":1},"update":{"sessionUpdate":"agent_message_chunk","content":"not-an-object"}}}"""
        )
        assertTrue(outs.single() is AdapterOutput.Ignored)
        val user = acp.decode(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":2},"update":{"sessionUpdate":"user_message_chunk","content":["array"]}}}"""
        )
        assertTrue(user.single() is AdapterOutput.Ignored)
    }

    @Test fun newSessionCarriesHarnessAndMode() {
        val frame = Json.parseToJsonElement(acp.newSession(1, NewSessionRequest("h", HarnessKind.CODEX, "/w", "hi", PermissionMode.PLAN))).jsonObject
        assertEquals("session/new", frame["method"]!!.jsonPrimitive.content)
        val params = frame["params"]!!.jsonObject
        assertEquals("/w", params["cwd"]!!.jsonPrimitive.content)
        assertEquals("codex", params["_meta"]!!.jsonObject["harness"]!!.jsonPrimitive.content)
        assertEquals("plan", params["_meta"]!!.jsonObject["permissionMode"]!!.jsonPrimitive.content)
        assertNull(params["_meta"]!!.jsonObject["model"])
    }

    @Test fun newSessionCarriesModelInMeta() {
        val frame = Json.parseToJsonElement(
            acp.newSession(
                1,
                NewSessionRequest(
                    "h", HarnessKind.CLAUDE_CODE, "/w", "hi", PermissionMode.ASK,
                    model = "claude-sonnet-4",
                ),
            ),
        ).jsonObject
        val meta = frame["params"]!!.jsonObject["_meta"]!!.jsonObject
        assertEquals("claude-sonnet-4", meta["model"]!!.jsonPrimitive.content)
        assertEquals("claude-code", meta["harness"]!!.jsonPrimitive.content)
    }

    @Test fun listHarnessesAndBrowseFrames() {
        val harnesses = Json.parseToJsonElement(acp.listHarnesses(9)).jsonObject
        assertEquals("bridge/listHarnesses", harnesses["method"]!!.jsonPrimitive.content)
        assertEquals(9L, harnesses["id"]!!.jsonPrimitive.content.toLong())

        val browse = Json.parseToJsonElement(acp.browse(10, "/home/me/code")).jsonObject
        assertEquals("bridge/browse", browse["method"]!!.jsonPrimitive.content)
        assertEquals("/home/me/code", browse["params"]!!.jsonObject["path"]!!.jsonPrimitive.content)
    }

    @Test fun gitStatusAndDiffFrames() {
        val status = Json.parseToJsonElement(acp.gitStatus(11, "sess-1")).jsonObject
        assertEquals("bridge/gitStatus", status["method"]!!.jsonPrimitive.content)
        assertEquals("sess-1", status["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)

        val diff = Json.parseToJsonElement(acp.diff(12, "sess-1", "src/a.kt")).jsonObject
        assertEquals("bridge/diff", diff["method"]!!.jsonPrimitive.content)
        val params = diff["params"]!!.jsonObject
        assertEquals("sess-1", params["sessionId"]!!.jsonPrimitive.content)
        assertEquals("src/a.kt", params["path"]!!.jsonPrimitive.content)
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

    @Test fun parseUnifiedDropsGitPreambleAndKeepsDashedLines() {
        val rename = Diff.parseUnified(
            """
            diff --git a/old.kt b/new.kt
            similarity index 90%
            rename from old.kt
            rename to new.kt
            index aaa..bbb 100644
            --- a/old.kt
            +++ b/new.kt
            @@ -1 +1 @@
            -val x = 1
            +val x = 2
            """.trimIndent()
        )
        assertEquals(
            listOf(DiffLine.Type.HUNK, DiffLine.Type.DELETE, DiffLine.Type.ADD),
            rename.map { it.type }
        )
        assertEquals("val x = 1", rename[1].text)
        assertEquals(1, rename[1].oldNo)
        assertEquals(1, rename[2].newNo)

        val dashes = Diff.parseUnified(
            """
            @@ -1,2 +1,2 @@
            ---- comment
            ++++ heading
            """.trimIndent()
        )
        assertEquals("--- comment", dashes[1].text)
        assertEquals("+++ heading", dashes[2].text)

        val crlf = Diff.parseUnified("@@ -1 +1 @@\r\n-a\r\n+b\r\n")
        assertEquals(listOf(DiffLine.Type.HUNK, DiffLine.Type.DELETE, DiffLine.Type.ADD), crlf.map { it.type })
        assertEquals("a", crlf[1].text)
        assertEquals("b", crlf[2].text)

        val binary = Diff.parseUnified(
            """
            diff --git a/x.png b/x.png
            Binary files a/x.png and b/x.png differ
            """.trimIndent()
        )
        assertEquals(1, binary.size)
        assertEquals(DiffLine.Type.HUNK, binary.single().type)
        assertEquals("Binary files a/x.png and b/x.png differ", binary.single().text)

        val patch = Diff.parseUnified("GIT binary patch\nliteral 12\nzcmV+\n")
        assertEquals(listOf("GIT binary patch"), patch.map { it.text })

        val pureRename = Diff.parseUnified(
            """
            diff --git a/old b/new
            similarity index 100%
            rename from old
            rename to new
            """.trimIndent()
        )
        assertTrue(pureRename.isEmpty())
    }

    @Test fun parseUnifiedKeepsDashCommentsAndTheNextFile() {
        // A deleted SQL/Lua comment is `--- comment` on the wire, the same prefix as a file header.
        val lines = Diff.parseUnified(
            """
            --- a/one.sql
            +++ b/one.sql
            @@ -1,3 +1,3 @@
            --- old comment
            +-- new comment
             stay
            --- a/two.sql
            +++ b/two.sql
            @@ -1 +1 @@
            -old
            +new
            """.trimIndent()
        )
        assertEquals(
            listOf(
                DiffLine.Type.HUNK,
                DiffLine.Type.DELETE,
                DiffLine.Type.ADD,
                DiffLine.Type.CONTEXT,
                DiffLine.Type.HUNK,
                DiffLine.Type.DELETE,
                DiffLine.Type.ADD,
            ),
            lines.map { it.type }
        )
        assertEquals("-- old comment", lines[1].text)
        assertEquals("-- new comment", lines[2].text)
        assertEquals("stay", lines[3].text)
        assertEquals("old", lines[5].text)
        assertEquals("new", lines[6].text)

        val addedHeading = Diff.parseUnified(
            """
            @@ -1 +1 @@
            -title
            +++ A heading
            """.trimIndent()
        )
        assertEquals("++ A heading", addedHeading[2].text)
        assertEquals(DiffLine.Type.ADD, addedHeading[2].type)
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

    @Test fun sessionInfoUpdateSetsTitle() {
        val out = acp.decode(update("""{"sessionUpdate":"session_info_update","title":"Fix the footer"}""", seq = 4))
        val info = (out.single() as AdapterOutput.Update).update as CodeUpdate.SessionInfo
        assertEquals("Fix the footer", info.title)
        assertEquals(4L, acp.lastSeq("s1"))
        val blank = acp.decode(update("""{"sessionUpdate":"session_info_update","title":"  "}"""))
        assertTrue(blank.single() is AdapterOutput.Ignored)
    }

    @Test fun toolOutputIncludesEmbeddedResourceAndTerminalSnapshot() {
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"r1","title":"Read a.kt","kind":"read","status":"completed",
               "content":[
                 {"type":"content","content":{"type":"resource","resource":{"uri":"file:///a.kt","text":"fun main() {}"}}},
                 {"type":"terminal","terminalId":"t","output":"exit 0"}
               ]}"""
        )))
        val tool = list.filterIsInstance<CodeEvent.ToolCall>().single()
        assertEquals("fun main() {}\nexit 0", tool.output)
    }


    @Test fun availableCommandsUpdateParsesNameDescriptionAndHint() {
        val out = acp.decode(update("""{"sessionUpdate":"available_commands_update","availableCommands":[
            {"name":"compact","description":"Compact context"},
            {"name":"help","description":"Show help","input":{"hint":"topic"}},
            {"name":"","description":"skip empty name"},
            {"description":"no name either"}
        ]}""", seq = 3))
        val upd = (out.single() as AdapterOutput.Update)
        assertEquals(3L, upd.seq)
        val cmds = (upd.update as CodeUpdate.AvailableCommands).commands
        assertEquals(2, cmds.size)
        assertEquals(AvailableCommand("compact", "Compact context"), cmds[0])
        assertEquals(AvailableCommand("help", "Show help", inputHint = "topic"), cmds[1])
        // Does not touch the transcript.
        assertTrue(TranscriptReducer.apply(emptyList(), upd.update, now = 1L).isEmpty())
    }

    @Test fun availableCommandsUpdateMalformedIsIgnored() {
        val bad = listOf(
            """{"sessionUpdate":"available_commands_update"}""",
            """{"sessionUpdate":"available_commands_update","availableCommands":null}""",
            """{"sessionUpdate":"available_commands_update","availableCommands":"x"}""",
            """{"sessionUpdate":"available_commands_update","availableCommands":{"name":"x"}}""",
        )
        for (u in bad) {
            val out = acp.decode(update(u))
            assertTrue("expected Ignored for $u, got $out", out.single() is AdapterOutput.Ignored)
        }
    }

    @Test fun availableCommandsUpdateEmptyArrayClears() {
        val out = acp.decode(update("""{"sessionUpdate":"available_commands_update","availableCommands":[]}"""))
        val cmds = ((out.single() as AdapterOutput.Update).update as CodeUpdate.AvailableCommands).commands
        assertTrue(cmds.isEmpty())
    }

    @Test fun slashDraftFilterAndInsert() {
        assertTrue(CodeComposer.isSlashDraft("/"))
        assertTrue(CodeComposer.isSlashDraft("/com"))
        assertFalse(CodeComposer.isSlashDraft("/com args"))
        assertFalse(CodeComposer.isSlashDraft("hello"))
        val cmds = listOf(
            AvailableCommand("compact", "c"),
            AvailableCommand("clear", "x"),
            AvailableCommand("help", "h", inputHint = "topic"),
        )
        assertEquals(listOf("compact", "clear"), CodeComposer.filterCommands(cmds, "c").map { it.name })
        assertEquals(cmds, CodeComposer.filterCommands(cmds, ""))
        val (text, caret) = CodeComposer.insertSlashCommand("/he", cmds[2])
        assertEquals("/help ", text)
        assertEquals(6, caret)
        val (text2, caret2) = CodeComposer.insertSlashCommand("/hel leftover", cmds[2])
        // "/hel leftover" is not a bare draft for the picker, but insert still replaces the token.
        assertEquals("/help leftover", text2)
        assertEquals(6, caret2)
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

    @Test fun promptFrameIncludesImageBlock() {
        val att = PromptAttachment(mimeType = "image/png", data = "iVBORw0KGgo")
        val frame = Json.parseToJsonElement(acp.prompt(5, "s1", "look at this", listOf(att))).jsonObject
        assertEquals("session/prompt", frame["method"]!!.jsonPrimitive.content)
        assertEquals(5L, frame["id"]!!.jsonPrimitive.content.toLong())
        val params = frame["params"]!!.jsonObject
        assertEquals("s1", params["sessionId"]!!.jsonPrimitive.content)
        val prompt = params["prompt"]!!.jsonArray
        assertEquals(2, prompt.size)
        val text = prompt[0].jsonObject
        assertEquals("text", text["type"]!!.jsonPrimitive.content)
        assertEquals("look at this", text["text"]!!.jsonPrimitive.content)
        val image = prompt[1].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", image["mimeType"]!!.jsonPrimitive.content)
        assertEquals("iVBORw0KGgo", image["data"]!!.jsonPrimitive.content)
    }

    @Test fun promptFrameImageOnlyOmitsEmptyText() {
        val att = PromptAttachment(mimeType = "image/jpeg", data = "AAAA")
        val prompt = Json.parseToJsonElement(acp.prompt(6, "s1", "  ", listOf(att)))
            .jsonObject["params"]!!.jsonObject["prompt"]!!.jsonArray
        assertEquals(1, prompt.size)
        assertEquals("image", prompt[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun promptFrameTextOnlyUnchanged() {
        val prompt = Json.parseToJsonElement(acp.prompt(7, "s1", "hello"))
            .jsonObject["params"]!!.jsonObject["prompt"]!!.jsonArray
        assertEquals(1, prompt.size)
        assertEquals("text", prompt[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("hello", prompt[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun demoStartSessionPassesAttachments() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val host = CodeHost(
                id = "demo",
                name = "Demo",
                url = "",
                token = "",
                transport = TransportKind.DEMO,
            )
            val backend = DemoBackend(host, scope)
            val att = PromptAttachment(mimeType = "image/jpeg", data = "AAAA")
            val collected = mutableListOf<SessionUpdate>()
            val collectJob = scope.launch { backend.updates.collect { collected += it } }
            backend.startSession(
                NewSessionRequest(
                    hostId = host.id,
                    harness = HarnessKind.CODEX,
                    workspace = "~/code/GradatiON",
                    prompt = "see image",
                    permissionMode = PermissionMode.ASK,
                    attachments = listOf(att),
                )
            )
            withTimeout(2_000) {
                while (collected.none { upd ->
                        val u = upd.update
                        u is CodeUpdate.Upsert &&
                            u.event is CodeEvent.UserPrompt &&
                            u.event.attachmentCount == 1
                    }
                ) {
                    delay(10)
                }
            }
            collectJob.cancel()
            val prompt = collected.mapNotNull {
                ((it.update as? CodeUpdate.Upsert)?.event as? CodeEvent.UserPrompt)
            }.first()
            assertEquals(1, prompt.attachmentCount)
            assertEquals("see image", prompt.text)
        } finally {
            scope.cancel()
        }
    }

    // ── audit fixes: approvals, diffs, seq gaps ───────────────────────────────────────────

    @Test fun unansweredApprovalExpiresWhenTheTurnEnds() {
        val ask = acp.decode("""{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"s1",
            "toolCall":{"toolCallId":"t1","title":"rm","kind":"execute"},
            "options":[{"optionId":"a","name":"Allow","kind":"allow_once"}]}}""")
        var list = TranscriptReducer.apply(emptyList(), (ask.single() as AdapterOutput.Update).update, now = 1L)
        assertEquals(SessionStatus.NEEDS_APPROVAL, TranscriptReducer.statusOf(list, running = true))
        list = TranscriptReducer.apply(list, CodeUpdate.TurnDone("end_turn"), now = 2L)
        val approval = list.filterIsInstance<CodeEvent.Approval>().single()
        assertTrue(approval.expired)
        assertFalse(approval.pending)
        assertNull(approval.chosen)
        assertEquals(SessionStatus.IDLE, TranscriptReducer.statusOf(list, running = false))
    }

    @Test fun answeredApprovalDoesNotExpire() {
        val ask = acp.decode("""{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"s1",
            "options":[{"optionId":"a","name":"Allow","kind":"allow_once"}]}}""")
        var list = TranscriptReducer.apply(emptyList(), (ask.single() as AdapterOutput.Update).update, now = 1L)
        list = TranscriptReducer.apply(list, CodeUpdate.ApprovalAnswered("7", ApprovalOption.Kind.ALLOW_ONCE), now = 2L)
        list = TranscriptReducer.apply(list, CodeUpdate.TurnDone("end_turn"), now = 3L)
        val approval = list.filterIsInstance<CodeEvent.Approval>().single()
        assertFalse(approval.expired)
        assertEquals(ApprovalOption.Kind.ALLOW_ONCE, approval.chosen)
    }

    @Test fun rejectKindIsNotTreatedAsAllow() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":9,"method":"session/request_permission","params":{"sessionId":"s1",
            "options":[
              {"optionId":"no","name":"Deny","kind":"reject"},
              {"optionId":"never","name":"Don't allow","kind":"unknown"},
              {"optionId":"yes","name":"Allow","kind":"allow"}
            ]}}""")
        val approval = ((out.single() as AdapterOutput.Update).update as CodeUpdate.Upsert).event as CodeEvent.Approval
        assertEquals(ApprovalOption.Kind.REJECT_ONCE, approval.options[0].kind)
        assertEquals(ApprovalOption.Kind.REJECT_ONCE, approval.options[1].kind)
        assertEquals(ApprovalOption.Kind.ALLOW_ONCE, approval.options[2].kind)

        val resolved = acp.decode("""{"jsonrpc":"2.0","method":"bridge/permissionResolved","params":{"sessionId":"s1","requestId":"9","optionKind":"deny"}}""")
        val upd = (resolved.single() as AdapterOutput.Update).update as CodeUpdate.ApprovalAnswered
        assertEquals(ApprovalOption.Kind.REJECT_ONCE, upd.chosen)
    }

    @Test fun requestWithoutOptionsOffersDenyThatCancels() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":8,"method":"session/request_permission","params":{"sessionId":"s1",
            "toolCall":{"toolCallId":"t1","title":"x","kind":"execute"},"options":[]}}""")
        val approval = ((out.single() as AdapterOutput.Update).update as CodeUpdate.Upsert).event as CodeEvent.Approval
        val deny = approval.options.single()
        assertEquals(ApprovalOption.CANCEL_ID, deny.id)
        assertEquals(ApprovalOption.Kind.REJECT_ONCE, deny.kind)
        val reply = Json.parseToJsonElement(acp.answerApproval("8", deny.id)).jsonObject
        val outcome = reply["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("cancelled", outcome["outcome"]!!.jsonPrimitive.content)
    }

    @Test fun bigFileEditKeepsTheUntouchedLinesAsContext() {
        // 3,000 x 3,000 lines is past maxCells, but one changed line in the middle must not turn the
        // whole file into a delete-and-add: the common prefix and suffix are trimmed first.
        val old = (1..3000).joinToString("\n") { "line $it" }
        val new = (1..3000).joinToString("\n") { if (it == 1500) "line 1500 changed" else "line $it" }
        val lines = Diff.between(old, new, maxCells = 1_000L)
        val (added, removed) = Diff.counts(lines)
        assertEquals(1, added)
        assertEquals(1, removed)
        assertTrue("only a hunk around the change is kept", lines.size < 20)
    }

    @Test fun bigRewriteStillDegradesToReplaceForTheChangedSpan() {
        val old = (1..2000).joinToString("\n") { "old $it" }
        val new = (1..2000).joinToString("\n") { "new $it" }
        val lines = Diff.between(old, new, maxCells = 1_000L)
        val (added, removed) = Diff.counts(lines)
        assertEquals(2000, added)
        assertEquals(2000, removed)
    }

    @Test fun seqJumpAnnouncesAGapAndTheReplayIsNotAppliedTwice() {
        val a = AcpAdapter()
        fun chunk(text: String, seq: Long) =
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"$text"}}""", seq = seq)
        assertTrue(a.decode(chunk("A", 1)).single() is AdapterOutput.Update)
        // 2 and 3 never arrived; 4 jumps past the hole.
        val jump = a.decode(chunk("D", 4))
        val gap = jump.first() as AdapterOutput.Gap
        assertEquals("s1", gap.sessionId)
        assertEquals(1L, gap.afterSeq)
        assertTrue(jump[1] is AdapterOutput.Update)
        // The far frame is on screen, but resume still asks for seq > 1 until the hole is filled.
        assertEquals(1L, a.lastSeq("s1"))
        // The reload replays everything after seq 1: the missing frames apply, the delivered one is dropped.
        assertTrue(a.decode(chunk("B", 2)).single() is AdapterOutput.Update)
        assertTrue(a.decode(chunk("C", 3)).single() is AdapterOutput.Update)
        assertEquals(4L, a.lastSeq("s1"))
        assertTrue(a.decode(chunk("D", 4)).single() is AdapterOutput.Ignored)
        a.endGap("s1")
        assertEquals(4L, a.lastSeq("s1"))
        assertTrue(a.decode(chunk("E", 5)).single() is AdapterOutput.Update)
    }

    @Test fun failedGapFillDoesNotSkipTheHoleOrAppendTheFarFrameTwice() {
        val a = AcpAdapter()
        fun chunk(text: String, seq: Long) =
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"$text"}}""", seq = seq)
        var list = emptyList<CodeEvent>()
        fun apply(frame: String) {
            a.decode(frame).forEach { out ->
                if (out is AdapterOutput.Update) list = TranscriptReducer.apply(list, out.update, now = 1L)
            }
        }
        apply(chunk("A", 1))
        val jump = a.decode(chunk("D", 4))
        jump.forEach { out ->
            if (out is AdapterOutput.Update) list = TranscriptReducer.apply(list, out.update, now = 1L)
        }
        a.endGap("s1", filled = false)
        assertEquals(1L, a.lastSeq("s1"))
        assertTrue(a.decode(chunk("D", 4)).single() is AdapterOutput.Ignored)
        apply(chunk("D", 4))
        assertEquals("AD", (list.single() as CodeEvent.AgentText).text)
        apply(chunk("B", 2))
        apply(chunk("C", 3))
        assertEquals("ADBC", (list.single() as CodeEvent.AgentText).text)
        assertEquals(4L, a.lastSeq("s1"))
    }

    @Test fun finishedGapLoadAcceptsASkippedSequence() {
        // Some bridges do not number every seq. A load that completes still moves the cursor
        // to the frame already on screen, so the next one is not another hole.
        val a = AcpAdapter()
        fun chunk(text: String, seq: Long) =
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"$text"}}""", seq = seq)
        assertTrue(a.decode(chunk("A", 1)).single() is AdapterOutput.Update)
        assertTrue(a.decode(chunk("J", 10)).first() is AdapterOutput.Gap)
        assertEquals(1L, a.lastSeq("s1"))
        a.endGap("s1", filled = true)
        assertEquals(10L, a.lastSeq("s1"))
        assertTrue(a.decode(chunk("K", 11)).single() is AdapterOutput.Update)
        assertEquals(11L, a.lastSeq("s1"))
    }

    @Test fun alreadySeenSeqIsNotDecodedAgain() {
        // Text chunks append, so a duplicate of an applied seq must not grow the bubble.
        val a = AcpAdapter()
        fun chunk(text: String, seq: Long) =
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"$text"}}""", seq = seq)
        var list = emptyList<CodeEvent>()
        fun apply(frame: String) {
            a.decode(frame).forEach { out ->
                if (out is AdapterOutput.Update) list = TranscriptReducer.apply(list, out.update, now = 1L)
            }
        }
        apply(chunk("A", 1))
        apply(chunk("B", 2))
        assertTrue(a.decode(chunk("A", 1)).single() is AdapterOutput.Ignored)
        apply(chunk("A", 1))
        apply(chunk("B", 2))
        assertEquals("AB", (list.single() as CodeEvent.AgentText).text)
    }

    @Test fun gapReplayDropsTheInclusiveBoundarySeq() {
        // Reload asks for seq > afterSeq. A bridge that also resends afterSeq must not append it.
        val a = AcpAdapter()
        fun chunk(text: String, seq: Long) =
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"$text"}}""", seq = seq)
        var list = emptyList<CodeEvent>()
        fun apply(frame: String) {
            a.decode(frame).forEach { out ->
                if (out is AdapterOutput.Update) list = TranscriptReducer.apply(list, out.update, now = 1L)
            }
        }
        apply(chunk("A", 1))
        val jump = a.decode(chunk("C", 3))
        assertEquals(1L, (jump.first() as AdapterOutput.Gap).afterSeq)
        jump.forEach { out ->
            if (out is AdapterOutput.Update) list = TranscriptReducer.apply(list, out.update, now = 1L)
        }
        apply(chunk("A", 1)) // inclusive replay of the boundary
        apply(chunk("B", 2))
        apply(chunk("C", 3)) // the frame that opened the hole, already delivered
        assertEquals("ACB", (list.single() as CodeEvent.AgentText).text)
        assertEquals(3L, a.lastSeq("s1"))
    }

    @Test fun consecutiveSeqsNeverReportAGap() {
        val a = AcpAdapter()
        val outs = (1L..6L).flatMap {
            a.decode(update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"x"}}""", seq = it))
        }
        assertTrue(outs.none { it is AdapterOutput.Gap })
    }

    @Test fun currentModeUpdateSyncsKnownModes() {
        val out = acp.decode(update(
            """{"sessionUpdate":"current_mode_update","currentModeId":"acceptEdits"}""",
            seq = 4,
        ))
        val info = (out.single() as AdapterOutput.Update).update as CodeUpdate.SessionInfo
        assertEquals(PermissionMode.AUTO_EDIT, info.permissionMode)
        assertEquals(4L, acp.lastSeq("s1"))

        val full = acp.decode(update(
            """{"sessionUpdate":"current_mode_update","modeId":"bypassPermissions"}""",
            seq = 5,
        ))
        val info2 = (full.single() as AdapterOutput.Update).update as CodeUpdate.SessionInfo
        assertEquals(PermissionMode.FULL_AUTO, info2.permissionMode)
    }

    @Test fun currentModeUpdateIgnoresUnknownMode() {
        val out = acp.decode(update(
            """{"sessionUpdate":"current_mode_update","currentModeId":"yolo"}""",
            seq = 2,
        ))
        assertTrue(out.single() is AdapterOutput.Ignored)
        assertEquals(2L, acp.lastSeq("s1"))
    }

    @Test fun stringRpcIdStillMatches() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":"12","result":{"stopReason":"end_turn"}}""")
        val result = out.single() as AdapterOutput.Result
        assertEquals(12L, result.id)
        assertEquals("end_turn", result.result!!.jsonObject["stopReason"]!!.jsonPrimitive.content)
    }

    @Test fun nonNumericStringIdIsIgnored() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":"nope","result":{}}""")
        assertTrue(out.single() is AdapterOutput.Ignored)
    }

    @Test fun requestShapedSessionUpdateIsAcked() {
        val frame = """{"jsonrpc":"2.0","id":3,"method":"session/update","params":{"sessionId":"s1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hi"}}}}"""
        val outs = acp.decode(frame)
        val text = outs.filterIsInstance<AdapterOutput.Update>().single().update as CodeUpdate.TextChunk
        assertEquals("Hi", text.chunk)
        val reply = outs.filterIsInstance<AdapterOutput.Reply>().single().frame
        assertTrue(reply.contains("\"id\":3"))
        assertTrue(reply.contains("\"result\""))
        assertFalse(reply.contains("error"))
    }

    @Test fun forwardedFileReadIsAnErrorResponse() {
        val outs = acp.decode("""{"jsonrpc":"2.0","id":4,"method":"fs/read_text_file","params":{"path":"a.kt"}}""")
        val reply = outs.single() as AdapterOutput.Reply
        assertTrue(reply.frame.contains("-32601"))
        assertTrue(reply.frame.contains("Method not found"))
        assertTrue(reply.frame.contains("\"id\":4"))
    }

    @Test fun permissionRequestIsNotAutoReplied() {
        val outs = acp.decode("""{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"s1","options":[]}}""")
        assertTrue(outs.none { it is AdapterOutput.Reply })
        assertTrue(outs.single() is AdapterOutput.Update)
    }

    @Test fun toolUpdateKeepsCommandWhenOnlyTheFolderArrives() {
        val list = fold(listOf(
            update(
                """{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Bash","kind":"execute","status":"pending",
                   "locations":[{"path":"/home/me/repo"}],"rawInput":{"command":"npm test"}}"""
            ),
            update(
                """{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"in_progress",
                   "locations":[{"path":"/home/me/repo"}]}"""
            ),
        ))
        val tool = list.single() as CodeEvent.ToolCall
        assertEquals("npm test", tool.detail)
        assertEquals(ToolStatus.RUNNING, tool.status)
    }

    @Test fun toolUpdateReplacesCommandWhenANewOneArrives() {
        val list = fold(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Bash","kind":"execute","status":"pending","rawInput":{"command":"npm test"}}"""),
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"in_progress","rawInput":{"command":"npm test --watch"}}"""),
        ))
        assertEquals("npm test --watch", (list.single() as CodeEvent.ToolCall).detail)
    }

    @Test fun readOutputKeepsTheHead() {
        val body = "START-" + "x".repeat(AcpAdapter.MAX_OUTPUT)
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"r1","title":"Read","kind":"read","status":"completed",
               "content":[{"type":"content","content":{"type":"text","text":"$body"}}]}"""
        )))
        val output = (list.single() as CodeEvent.ToolCall).output!!
        assertTrue(output.startsWith("START-"))
        assertTrue(output.endsWith("…"))
        assertTrue(output.length < body.length)
    }

    @Test fun shellOutputKeepsTheTail() {
        val body = "y".repeat(AcpAdapter.MAX_OUTPUT) + "-TAIL"
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Bash","kind":"execute","status":"completed",
               "content":[{"type":"content","content":{"type":"text","text":"$body"}}]}"""
        )))
        val output = (list.single() as CodeEvent.ToolCall).output!!
        assertTrue(output.endsWith("-TAIL"))
        assertTrue(output.startsWith("…"))
    }

    @Test fun unifiedDiffContentBecomesFileDiff() {
        val patch = "@@ -1 +1 @@\\n-old\\n+new\\n"
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"e1","title":"Edit","kind":"edit","status":"completed",
               "content":[{"type":"diff","path":"a.kt","diff":"$patch"}]}"""
        )))
        val diff = list.filterIsInstance<CodeEvent.FileDiff>().single()
        assertEquals("a.kt", diff.path)
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertEquals("old", diff.lines.first { it.type == DiffLine.Type.DELETE }.text)
        assertEquals("new", diff.lines.first { it.type == DiffLine.Type.ADD }.text)
    }

    @Test fun crlfFileContentsAreNotAFullRewrite() {
        val same = Diff.between("hello\r\n", "hello\n")
        assertTrue(same.isEmpty())
        val changed = Diff.between("one\r\ntwo\r\n", "one\r\nthree\r\n")
        assertEquals("two", changed.first { it.type == DiffLine.Type.DELETE }.text)
        assertEquals("three", changed.first { it.type == DiffLine.Type.ADD }.text)
        assertTrue(changed.none { it.text.contains('\r') })
    }

    @Test fun readUpdateWithoutKindKeepsTheHead() {
        val body = "START-" + "x".repeat(AcpAdapter.MAX_OUTPUT)
        val list = foldFresh(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"r-head","title":"Read","kind":"read","status":"in_progress"}"""),
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"r-head","status":"completed","content":[{"type":"content","content":{"type":"text","text":"$body"}}]}"""),
        ))
        val output = (list.single() as CodeEvent.ToolCall).output!!
        assertTrue(output.startsWith("START-"))
        assertTrue(output.endsWith("…"))
    }

    @Test fun shellUpdateWithoutKindKeepsTheTail() {
        val body = "y".repeat(AcpAdapter.MAX_OUTPUT) + "-TAIL"
        val list = foldFresh(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"x-tail","title":"Bash","kind":"execute","status":"in_progress"}"""),
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"x-tail","status":"completed","content":[{"type":"content","content":{"type":"text","text":"$body"}}]}"""),
        ))
        val output = (list.single() as CodeEvent.ToolCall).output!!
        assertTrue(output.endsWith("-TAIL"))
        assertTrue(output.startsWith("…"))
    }

    @Test fun toolUpdateShowsPathWhenTheRowHadNoDetail() {
        val list = foldFresh(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"path1","title":"Read","kind":"read","status":"pending"}"""),
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"path1","status":"in_progress","locations":[{"path":"src/A.kt"}]}"""),
        ))
        assertEquals("src/A.kt", (list.single() as CodeEvent.ToolCall).detail)
    }

    @Test fun folderOnlyUpdateStillDoesNotReplaceACommand() {
        val list = foldFresh(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"cmd1","title":"Bash","kind":"execute","status":"pending","rawInput":{"command":"npm test"},"locations":[{"path":"/repo"}]}"""),
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"cmd1","status":"completed","locations":[{"path":"/repo"}]}"""),
        ))
        assertEquals("npm test", (list.single() as CodeEvent.ToolCall).detail)
    }

    @Test fun rawOutputIsShownWhenContentIsMissing() {
        val list = foldFresh(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"raw1","title":"Bash","kind":"execute","status":"completed","rawOutput":"ok from raw"}"""),
        ))
        assertEquals("ok from raw", (list.single() as CodeEvent.ToolCall).output)
    }

    @Test fun rawOutputObjectUsesStdout() {
        val list = foldFresh(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"raw2","title":"Bash","kind":"execute","status":"completed","rawOutput":{"stdout":"hello out"}}"""),
        ))
        assertEquals("hello out", (list.single() as CodeEvent.ToolCall).output)
    }

    @Test fun contentBeatsRawOutput() {
        val list = foldFresh(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"raw3","title":"Bash","kind":"execute","status":"completed","content":[{"type":"text","text":"from content"}],"rawOutput":"from raw"}"""),
        ))
        assertEquals("from content", (list.single() as CodeEvent.ToolCall).output)
    }

    @Test fun unifiedDiffOfANewFileIsMarkedNew() {
        val patch = "new file mode 100644\\n--- /dev/null\\n+++ b/n.kt\\n@@ -0,0 +1 @@\\n+hello\\n"
        val list = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"new1","title":"Write","kind":"edit","status":"completed","content":[{"type":"diff","path":"n.kt","diff":"$patch"}]}"""
        )))
        val diff = list.filterIsInstance<CodeEvent.FileDiff>().single()
        assertTrue(diff.isNewFile)
        assertEquals("hello", diff.lines.first { it.type == DiffLine.Type.ADD }.text)
    }

    @Test fun unifiedDiffOfADeletionIsNotANewFile() {
        val patch = "deleted file mode 100644\\n--- a/n.kt\\n+++ /dev/null\\n@@ -1 +0,0 @@\\n-hello\\n"
        val list = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"del1","title":"Delete","kind":"delete","status":"completed","content":[{"type":"diff","path":"n.kt","diff":"$patch"}]}"""
        )))
        val diff = list.filterIsInstance<CodeEvent.FileDiff>().single()
        assertFalse(diff.isNewFile)
        assertFalse(Diff.unifiedIsNewFile("@@ -1,3 +1,3 @@\n-a\n+b\n"))
    }

    @Test fun shellOutputDropsColorAndKeepsTheLastProgressLine() {
        val raw = "\u001b[32m10%\u001b[0m\r\u001b[2K\u001b[32m100%\u001b[0m\nFAIL WidgetTest"
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"sh","title":"Bash","kind":"execute","status":"completed",
               "content":[{"type":"terminal","output":${JsonPrimitive(raw)}}]}"""
        )))
        assertEquals("100%\nFAIL WidgetTest", (list.single() as CodeEvent.ToolCall).output)
    }

    @Test fun searchOutputDropsRipgrepColor() {
        val raw = "\u001b[1;32msrc/A.kt\u001b[0m:3:fun main"
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"rg","title":"Search","kind":"search","status":"completed",
               "rawOutput":${JsonPrimitive(raw)}}"""
        )))
        assertEquals("src/A.kt:3:fun main", (list.single() as CodeEvent.ToolCall).output)
    }

    @Test fun readOutputKeepsColorBytesInTheFile() {
        val raw = "const RED = \"\u001b[31m\""
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"rd","title":"Read","kind":"read","status":"completed",
               "content":[{"type":"content","content":{"type":"text","text":${JsonPrimitive(raw)}}}]}"""
        )))
        assertEquals(raw, (list.single() as CodeEvent.ToolCall).output)
    }

    @Test fun toolStatusAcceptsHyphenAndRunning() {
        val hyphen = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"a","title":"Bash","kind":"execute","status":"in-progress"}"""
        )))
        assertEquals(ToolStatus.RUNNING, (hyphen.single() as CodeEvent.ToolCall).status)
        val running = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"b","title":"Bash","kind":"execute","status":"running"}"""
        )))
        assertEquals(ToolStatus.RUNNING, (running.single() as CodeEvent.ToolCall).status)
    }

    @Test fun planStatusAcceptsAHyphen() {
        val list = foldFresh(listOf(update(
            """{"sessionUpdate":"plan","entries":[{"content":"Run tests","status":"in-progress"}]}"""
        )))
        assertEquals(PlanStatus.IN_PROGRESS, (list.single() as CodeEvent.Plan).entries.single().status)
    }

    @Test fun bashKindIsAShellAndKeepsTheTail() {
        val body = "y".repeat(AcpAdapter.MAX_OUTPUT) + "-TAIL"
        val list = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"bash1","title":"Bash","kind":"Bash","status":"completed",
               "content":[{"type":"content","content":{"type":"text","text":"$body"}}]}"""
        )))
        val tool = list.single() as CodeEvent.ToolCall
        assertEquals(ToolKind.EXECUTE, tool.kind)
        val output = checkNotNull(tool.output)
        assertTrue(output.endsWith("-TAIL"))
        assertTrue(output.startsWith("…"))
    }

    @Test fun readKindIgnoresCaseAndKeepsTheHead() {
        val body = "START-" + "x".repeat(AcpAdapter.MAX_OUTPUT)
        val list = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"Read1","title":"Read","kind":"Read","status":"completed",
               "content":{"type":"content","content":{"type":"text","text":"$body"}}}"""
        )))
        val tool = list.single() as CodeEvent.ToolCall
        assertEquals(ToolKind.READ, tool.kind)
        val output = checkNotNull(tool.output)
        assertTrue(output.startsWith("START-"))
        assertTrue(output.endsWith("…"))
    }

    @Test fun grepKindShowsTheQueryNotTheFolder() {
        val list = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"g1","title":"Grep","kind":"grep","status":"completed",
               "locations":[{"path":"/repo"}],"rawInput":{"pattern":"snake_case","path":"src"}}"""
        )))
        val tool = list.single() as CodeEvent.ToolCall
        assertEquals(ToolKind.SEARCH, tool.kind)
        assertEquals("snake_case", tool.detail)
    }

    @Test fun writeKindIsAnEdit() {
        val list = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"w1","title":"Write","kind":"write","status":"completed",
               "rawInput":{"file_path":"A.kt"}}"""
        )))
        val tool = list.single() as CodeEvent.ToolCall
        assertEquals(ToolKind.EDIT, tool.kind)
        assertEquals("A.kt", tool.detail)
    }

    @Test fun errorStatusEndsTheSpinner() {
        val list = foldFresh(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"err1","title":"Bash","kind":"execute","status":"running"}"""),
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"err1","status":"error"}"""),
        ))
        assertEquals(ToolStatus.FAILED, (list.single() as CodeEvent.ToolCall).status)
    }

    @Test fun doneAndCancelledAreNotStillRunning() {
        val done = foldFresh(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"done1","title":"Bash","kind":"execute","status":"in_progress"}"""),
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"done1","status":"done"}"""),
        ))
        assertEquals(ToolStatus.COMPLETED, (done.single() as CodeEvent.ToolCall).status)
        val cancelled = foldFresh(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"stop1","title":"Bash","kind":"execute","status":"running"}"""),
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"stop1","status":"cancelled"}"""),
        ))
        assertEquals(ToolStatus.CANCELLED, (cancelled.single() as CodeEvent.ToolCall).status)
    }

    @Test fun unknownStatusLeavesTheSpinner() {
        val list = foldFresh(listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"unk1","title":"Bash","kind":"execute","status":"running"}"""),
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"unk1","status":"deferred"}"""),
        ))
        assertEquals(ToolStatus.RUNNING, (list.single() as CodeEvent.ToolCall).status)
    }

    @Test fun planStepRunningIsCurrentAndDoneIsComplete() {
        val list = foldFresh(listOf(update(
            """{"sessionUpdate":"plan","entries":[{"content":"Run tests","status":"running"},{"content":"Ship","status":"done"}]}"""
        )))
        val entries = (list.single() as CodeEvent.Plan).entries
        assertEquals(PlanStatus.IN_PROGRESS, entries[0].status)
        assertEquals(PlanStatus.COMPLETED, entries[1].status)
    }

    @Test fun stderrAndLineArrayAreShown() {
        val err = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"se1","title":"Bash","kind":"execute","status":"failed","rawOutput":{"stderr":"not found"}}"""
        )))
        assertEquals("not found", (err.single() as CodeEvent.ToolCall).output)
        val both = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"se2","title":"Bash","kind":"execute","status":"completed","rawOutput":{"stdout":"ok","stderr":"warn"}}"""
        )))
        assertEquals("ok\nwarn", (both.single() as CodeEvent.ToolCall).output)
        val lines = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"se3","title":"Bash","kind":"execute","status":"completed","rawOutput":["one","two"]}"""
        )))
        assertEquals("one\ntwo", (lines.single() as CodeEvent.ToolCall).output)
    }

    @Test fun bareStringContentIsShown() {
        val list = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"str1","title":"Bash","kind":"shell","status":"completed","content":"hello from string"}"""
        )))
        val tool = list.single() as CodeEvent.ToolCall
        assertEquals(ToolKind.EXECUTE, tool.kind)
        assertEquals("hello from string", tool.output)
    }

    @Test fun singleDiffObjectStillRenders() {
        val patch = "@@ -1 +1 @@\\n-old\\n+new\\n"
        val list = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"dobj","title":"Write","kind":"write","status":"completed","content":{"type":"diff","path":"a.kt","diff":"$patch"}}"""
        )))
        assertEquals(ToolKind.EDIT, list.filterIsInstance<CodeEvent.ToolCall>().single().kind)
        val diff = list.filterIsInstance<CodeEvent.FileDiff>().single()
        assertEquals("a.kt", diff.path)
        assertEquals("new", diff.lines.first { it.type == DiffLine.Type.ADD }.text)
    }

    @Test fun contentChunkAppendsUntilAFullUpdateReplaces() {
        val fresh = AcpAdapter()
        fun step(u: String, list: List<CodeEvent>): List<CodeEvent> {
            var next = list
            fresh.decode(update(u)).forEach { out ->
                if (out is AdapterOutput.Update) next = TranscriptReducer.apply(next, out.update, now = 1L)
            }
            return next
        }
        var list = step(
            """{"sessionUpdate":"tool_call","toolCallId":"c1","title":"Run","kind":"execute","status":"in_progress","content":[{"type":"content","content":{"type":"text","text":"one"}}]}""",
            emptyList(),
        )
        list = step(
            """{"sessionUpdate":"tool_call_content_chunk","toolCallId":"c1","content":{"type":"content","content":{"type":"text","text":"two"}}}""",
            list,
        )
        assertEquals("one\ntwo", (list.single() as CodeEvent.ToolCall).output)
        list = step(
            """{"sessionUpdate":"tool_call_update","toolCallId":"c1","status":"completed","content":[{"type":"text","text":"final"}]}""",
            list,
        )
        val tool = list.single() as CodeEvent.ToolCall
        assertEquals("final", tool.output)
        assertEquals(ToolStatus.COMPLETED, tool.status)
    }

    @Test fun contentChunkWithoutAFirstCallStillShows() {
        val list = foldFresh(listOf(
            update("""{"sessionUpdate":"tool_call_content_chunk","toolCallId":"c2","content":{"type":"text","text":"partial"}}"""),
            update("""{"sessionUpdate":"tool_call_content_chunk","toolCallId":"c2","content":{"type":"text","text":" more"}}"""),
        ))
        val tool = list.single() as CodeEvent.ToolCall
        assertEquals("partial\n more", tool.output)
    }

    @Test fun v2DiffChangesAndPatchTextRender() {
        val patch = "diff --git /repo/src/a.kt /repo/src/a.kt\\n--- /repo/src/a.kt\\n+++ /repo/src/a.kt\\n@@ -1 +1 @@\\n-old\\n+new\\n"
        val list = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"d2","title":"Edit","kind":"edit","status":"completed","content":{"type":"diff","changes":[{"operation":"modify","path":"/repo/src/a.kt"}],"patch":{"format":"git_patch","text":"$patch"}}}"""
        )))
        val diff = list.filterIsInstance<CodeEvent.FileDiff>().single()
        assertEquals("/repo/src/a.kt", diff.path)
        assertEquals("new", diff.lines.first { it.type == DiffLine.Type.ADD }.text)
        assertEquals(false, diff.isNewFile)
    }

    @Test fun v2AddedFileIsMarkedNewEvenWithoutAPatchBody() {
        val list = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call_update","toolCallId":"d3","content":{"type":"diff","changes":[{"operation":"add","path":"/repo/New.kt"}]}}"""
        )))
        val diff = list.filterIsInstance<CodeEvent.FileDiff>().single()
        assertEquals("/repo/New.kt", diff.path)
        assertEquals(true, diff.isNewFile)
    }

    @Test fun permissionSubjectCommandUsesTheTitleAndCommand() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":5,"method":"session/request_permission","params":{"sessionId":"s1","title":"Run tests?","description":"The suite","subject":{"type":"command","command":"cargo test","cwd":"/proj","toolCallId":"c1"},"options":[{"optionId":"allow-once","name":"Allow once","kind":"allow_once"},{"optionId":"reject-once","name":"Reject","kind":"reject_once"}]}}""")
        val approval = ((out.single() as AdapterOutput.Update).update as CodeUpdate.Upsert).event as CodeEvent.Approval
        assertEquals("Run tests?", approval.title)
        assertEquals("cargo test", approval.detail)
        assertEquals("c1", approval.callId)
        assertEquals(ApprovalOption.Kind.REJECT_ONCE, approval.options[1].kind)
    }

    @Test fun cursorAgentModeIsFullAuto() {
        val out = acp.decode(update("""{"sessionUpdate":"current_mode_update","currentModeId":"agent"}"""))
        val info = (out.single() as AdapterOutput.Update).update as CodeUpdate.SessionInfo
        assertEquals(PermissionMode.FULL_AUTO, info.permissionMode)
    }

    @Test fun toolNameCorrectsAnOtherKind() {
        val list = foldFresh(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"n1","title":"MCP: tool","kind":"other","name":"read_file","status":"pending","rawInput":{"target_file":"App.kt"}}"""
        )))
        val tool = list.single() as CodeEvent.ToolCall
        assertEquals(ToolKind.READ, tool.kind)
        assertEquals("App.kt", tool.detail)
    }

    @Test fun cursorAskQuestionAnswersWithTheChosenOption() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":9,"method":"cursor/ask_question","params":{"sessionId":"s1","title":"Need input","toolCallId":"call_123","questions":[{"id":"q1","prompt":"Which mode?","allowMultiple":false,"options":[{"id":"agent","label":"Agent"},{"id":"plan","label":"Plan"}]}]}}""")
        val approval = ((out.single() as AdapterOutput.Update).update as CodeUpdate.Upsert).event as CodeEvent.Approval
        assertEquals("Need input", approval.title)
        assertEquals(listOf("agent", "plan"), approval.options.map { it.id })
        val reply = Json.parseToJsonElement(acp.answerApproval("9", "plan")).jsonObject
        val outcome = reply["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("answered", outcome["outcome"]!!.jsonPrimitive.content)
        val answer = outcome["answers"]!!.jsonArray.single().jsonObject
        assertEquals("q1", answer["questionId"]!!.jsonPrimitive.content)
        assertEquals("plan", answer["selectedOptionIds"]!!.jsonArray.single().jsonPrimitive.content)
        val cancel = Json.parseToJsonElement(acp.answerApproval("9", null)).jsonObject
        assertEquals("cancelled", cancel["result"]!!.jsonObject["outcome"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
    }

    @Test fun cursorRequestIdDoesNotRewriteALaterPermission() {
        acp.decode("""{"jsonrpc":"2.0","id":9,"method":"cursor/ask_question","params":{"sessionId":"s1","questions":[{"id":"q1","prompt":"Which mode?","options":[{"id":"agent","label":"Agent"},{"id":"plan","label":"Plan"}]}]}}""")
        val asked = Json.parseToJsonElement(acp.answerApproval("9", "agent")).jsonObject
        assertEquals("answered", asked["result"]!!.jsonObject["outcome"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
        // A failed send rebuilds the same Cursor result until a new request takes the id.
        val retry = Json.parseToJsonElement(acp.answerApproval("9", "agent")).jsonObject
        assertEquals("answered", retry["result"]!!.jsonObject["outcome"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
        acp.decode("""{"jsonrpc":"2.0","id":9,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"toolCallId":"c1","title":"Run tests","kind":"execute"},"options":[{"optionId":"allow-once","name":"Allow once","kind":"allow_once"},{"optionId":"reject-once","name":"Reject","kind":"reject_once"}]}}""")
        val perm = Json.parseToJsonElement(acp.answerApproval("9", "allow-once")).jsonObject
        val outcome = perm["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("selected", outcome["outcome"]!!.jsonPrimitive.content)
        assertEquals("allow-once", outcome["optionId"]!!.jsonPrimitive.content)
    }

    @Test fun cursorPlanIdReusedAsAQuestionUsesTheQuestionShape() {
        acp.decode("""{"jsonrpc":"2.0","id":11,"method":"cursor/create_plan","params":{"sessionId":"s1","name":"Refactor","overview":"Tighten.","todos":[{"id":"1","content":"Inspect","status":"pending"}]}}""")
        acp.decode("""{"jsonrpc":"2.0","id":11,"method":"cursor/ask_question","params":{"sessionId":"s1","questions":[{"id":"q1","prompt":"Go?","options":[{"id":"yes","label":"Yes"}]}]}}""")
        val reply = Json.parseToJsonElement(acp.answerApproval("11", "yes")).jsonObject
        val outcome = reply["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("answered", outcome["outcome"]!!.jsonPrimitive.content)
        assertEquals("q1", outcome["answers"]!!.jsonArray.single().jsonObject["questionId"]!!.jsonPrimitive.content)
    }

    @Test fun forgottenSessionDropsItsCursorQuestion() {
        acp.decode("""{"jsonrpc":"2.0","id":4,"method":"cursor/ask_question","params":{"sessionId":"s1","questions":[{"id":"q1","prompt":"Go?","options":[{"id":"yes","label":"Yes"}]}]}}""")
        acp.clearLastSeq("s1")
        val reply = Json.parseToJsonElement(acp.answerApproval("4", "yes")).jsonObject
        val outcome = reply["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("selected", outcome["outcome"]!!.jsonPrimitive.content)
        assertEquals("yes", outcome["optionId"]!!.jsonPrimitive.content)
    }

    @Test fun cursorAskWithSeveralQuestionsIsSkipped() {
        acp.decode("""{"jsonrpc":"2.0","id":"ask-2","method":"cursor/create_plan","params":{"sessionId":"s1","name":"Old","overview":"Before.","todos":[{"id":"1","content":"Inspect","status":"pending"}]}}""")
        val out = acp.decode("""{"jsonrpc":"2.0","id":"ask-2","method":"cursor/ask_question","params":{"sessionId":"s1","questions":[{"id":"q1","prompt":"A","options":[{"id":"a","label":"A"}]},{"id":"q2","prompt":"B","options":[{"id":"b","label":"B"}]}]}}""")
        assertTrue(out.any { it is AdapterOutput.Update && (it.update as? CodeUpdate.Upsert)?.event is CodeEvent.Notice })
        val reply = out.filterIsInstance<AdapterOutput.Reply>().single().frame
        val outcome = Json.parseToJsonElement(reply).jsonObject["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("skipped", outcome["outcome"]!!.jsonPrimitive.content)
        assertEquals("ask-2", Json.parseToJsonElement(reply).jsonObject["id"]!!.jsonPrimitive.content)
        val later = Json.parseToJsonElement(acp.answerApproval("ask-2", "accept")).jsonObject
        assertEquals("selected", later["result"]!!.jsonObject["outcome"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
    }

    @Test fun cursorCreatePlanIsATodoCardAndAnApproval() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":11,"method":"cursor/create_plan","params":{"sessionId":"s1","name":"Refactor tabs","overview":"Tighten layout.","plan":"1. Inspect.","toolCallId":"call_124","todos":[{"id":"todo-1","content":"Inspect","status":"completed"},{"id":"todo-2","content":"Update","status":"in_progress"}]}}""")
        val updates = out.filterIsInstance<AdapterOutput.Update>().map { it.update }
        val plan = updates.mapNotNull { (it as? CodeUpdate.Upsert)?.event as? CodeEvent.Plan }.single()
        assertEquals(PlanStatus.COMPLETED, plan.entries[0].status)
        assertEquals(PlanStatus.IN_PROGRESS, plan.entries[1].status)
        val approval = updates.mapNotNull { (it as? CodeUpdate.Upsert)?.event as? CodeEvent.Approval }.single()
        assertEquals("Refactor tabs", approval.title)
        assertEquals("Tighten layout.", approval.detail)
        val accept = Json.parseToJsonElement(acp.answerApproval("11", "accept")).jsonObject
        assertEquals("accepted", accept["result"]!!.jsonObject["outcome"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
        val reject = Json.parseToJsonElement(acp.answerApproval("11", "reject")).jsonObject
        assertEquals("rejected", reject["result"]!!.jsonObject["outcome"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
    }

    @Test fun cursorTodosMergeById() {
        val first = """{"jsonrpc":"2.0","method":"cursor/update_todos","params":{"sessionId":"s1","merge":false,"toolCallId":"t","todos":[{"id":"1","content":"Set up","status":"completed"},{"id":"2","content":"Auth","status":"pending"}]}}"""
        val second = """{"jsonrpc":"2.0","method":"cursor/update_todos","params":{"sessionId":"s1","merge":true,"toolCallId":"t","todos":[{"id":"2","content":"Auth","status":"in_progress"},{"id":"3","content":"Tests","status":"pending"}]}}"""
        var list = emptyList<CodeEvent>()
        listOf(first, second).flatMap { acp.decode(it) }.forEach { out ->
            if (out is AdapterOutput.Update) list = TranscriptReducer.apply(list, out.update, now = 1L)
        }
        val plan = list.filterIsInstance<CodeEvent.Plan>().last()
        assertEquals(listOf("1", "2", "3"), plan.entries.map { it.id })
        assertEquals(PlanStatus.IN_PROGRESS, plan.entries[1].status)
        assertEquals("Tests", plan.entries[2].content)
    }

    @Test fun blankSessionIdUsesTheOnlyAttachedSession() {
        assertEquals("s1", InboundSession.resolve("", setOf("s1")))
        assertEquals("s9", InboundSession.resolve("s9", setOf("s1", "s9")))
        assertEquals(null, InboundSession.resolve("", setOf("s1", "s2")))
        assertEquals(null, InboundSession.resolve("", emptySet()))
    }

    @Test fun skippableAuthErrorIsOnlyAMissingMethod() {
        assertTrue(AcpHandshake.isSkippableAuthError("Method not found"))
        assertTrue(AcpHandshake.isSkippableAuthError("Unknown method: authenticate"))
        assertTrue(AcpHandshake.isSkippableAuthError("authenticate is not implemented"))
        assertTrue(AcpHandshake.isSkippableAuthError("error -32601"))
        assertFalse(AcpHandshake.isSkippableAuthError("Sign in required"))
        assertFalse(AcpHandshake.isSkippableAuthError(""))
        assertFalse(AcpHandshake.isSkippableAuthError(null))
    }

    @Test fun cursorToolNamesSetTheCardKind() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"ws","title":"Search","kind":"other","name":"WebSearch","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ld","title":"List","kind":"other","name":"ListDir","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"nb","title":"Notebook","kind":"other","name":"EditNotebook","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"wf","title":"Write","kind":"other","name":"WriteFile","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"df","title":"Delete","kind":"other","name":"DeleteFile","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"rt","title":"Shell","kind":"other","name":"RunTerminalCmd","status":"completed"}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals(ToolKind.FETCH, byId["ws"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["ld"]?.kind)
        assertEquals(ToolKind.EDIT, byId["nb"]?.kind)
        assertEquals(ToolKind.EDIT, byId["wf"]?.kind)
        assertEquals(ToolKind.DELETE, byId["df"]?.kind)
        assertEquals(ToolKind.EXECUTE, byId["rt"]?.kind)
    }

    @Test fun bridgeSeqAcceptsAWholeNumberWrittenAsADouble() {
        val frame = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","_meta":{"seq":2.0},"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hi"}}}}"""
        val outs = acp.decode(frame)
        val update = outs.filterIsInstance<AdapterOutput.Update>().single()
        assertEquals(2L, update.seq)
        assertEquals(2L, acp.lastSeq("s1"))
    }

    @Test fun permissionIdWrittenAsADoubleStillAnswersWithANumber() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":9.0,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"toolCallId":"c1","title":"Run","kind":"execute"},"options":[{"optionId":"allow-once","name":"Allow once","kind":"allow_once"},{"optionId":"reject-once","name":"Reject","kind":"reject_once"}]}}""")
        val approval = (out.filterIsInstance<AdapterOutput.Update>().single().update as CodeUpdate.Upsert).event as CodeEvent.Approval
        assertEquals("9", approval.requestId)
        val answer = Json.parseToJsonElement(acp.answerApproval("9", "allow-once")).jsonObject
        assertEquals(9L, answer["id"]!!.jsonPrimitive.long)
        assertEquals("selected", answer["result"]!!.jsonObject["outcome"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
    }

    @Test fun rpcResultIdWrittenAsADoubleStillMatches() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":5.0,"result":{"stopReason":"end_turn"}}""").single() as AdapterOutput.Result
        assertEquals(5L, out.id)
        assertEquals(null, out.error)
    }

    @Test fun handshakeProtocolVersionWrittenAsADoubleIsHonoured() {
        val ok = Json.parseToJsonElement("""{"protocolVersion":1.0}""").jsonObject
        assertEquals(AcpHandshake.Decision.Ready, AcpHandshake.decide(ok))
        val bad = Json.parseToJsonElement("""{"protocolVersion":2.0}""").jsonObject
        val refused = AcpHandshake.decide(bad) as AcpHandshake.Decision.UnsupportedVersion
        assertEquals(2, refused.version)
    }

    @Test fun cursorAskIdWrittenAsADoubleStillAnswersWithANumber() {
        acp.decode("""{"jsonrpc":"2.0","id":9.0,"method":"cursor/ask_question","params":{"sessionId":"s1","questions":[{"id":"q1","prompt":"Go?","options":[{"id":"yes","label":"Yes"}]}]}}""")
        val answer = Json.parseToJsonElement(acp.answerApproval("9", "yes")).jsonObject
        assertEquals(9L, answer["id"]!!.jsonPrimitive.long)
    }

    @Test fun permissionResolvedRequestIdWrittenAsADoubleStillMatches() {
        val a = AcpAdapter()
        val ask = a.decode("""{"jsonrpc":"2.0","id":9.0,"method":"session/request_permission","params":{"sessionId":"s1","_meta":{"seq":1},"toolCall":{"toolCallId":"c1","title":"Run","kind":"execute"},"options":[{"optionId":"allow-once","name":"Allow once","kind":"allow_once"},{"optionId":"reject-once","name":"Reject","kind":"reject_once"}]}}""")
        val approval = ((ask.filterIsInstance<AdapterOutput.Update>().single().update as CodeUpdate.Upsert).event as CodeEvent.Approval)
        assertEquals("9", approval.requestId)
        val resolved = a.decode("""{"jsonrpc":"2.0","method":"bridge/permissionResolved","params":{"sessionId":"s1","requestId":9.0,"optionKind":"allow_once","_meta":{"seq":2}}}""")
        val answered = (resolved.filterIsInstance<AdapterOutput.Update>().single().update as CodeUpdate.ApprovalAnswered)
        assertEquals("9", answered.requestId)
        assertEquals(ApprovalOption.Kind.ALLOW_ONCE, answered.chosen)
        var list = emptyList<CodeEvent>()
        ask.filterIsInstance<AdapterOutput.Update>().forEach { list = TranscriptReducer.apply(list, it.update, now = 1L) }
        resolved.filterIsInstance<AdapterOutput.Update>().forEach { list = TranscriptReducer.apply(list, it.update, now = 2L) }
        val card = list.filterIsInstance<CodeEvent.Approval>().single()
        assertEquals(ApprovalOption.Kind.ALLOW_ONCE, card.chosen)
        assertFalse(card.pending)
    }

    @Test fun moreCursorToolNamesSetTheCardKind() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"ef","title":"Edit","kind":"other","name":"EditFile","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sr","title":"Replace","kind":"other","name":"SearchReplace","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"rf2","title":"Read","kind":"other","name":"ReadFileV2","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ld2","title":"List","kind":"other","name":"ListDirV2","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gs","title":"Glob","kind":"other","name":"GlobFileSearch","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"rl","title":"Lints","kind":"other","name":"ReadLints","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"aw","title":"Wait","kind":"other","name":"Await","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"mr","title":"MCP","kind":"other","name":"FetchMcpResource","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"re","title":"Retry","kind":"other","name":"Reapply","status":"completed"}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals(ToolKind.EDIT, byId["ef"]?.kind)
        assertEquals(ToolKind.EDIT, byId["sr"]?.kind)
        assertEquals(ToolKind.READ, byId["rf2"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["ld2"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["gs"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["rl"]?.kind)
        assertEquals(ToolKind.THINK, byId["aw"]?.kind)
        assertEquals(ToolKind.FETCH, byId["mr"]?.kind)
        assertEquals(ToolKind.EDIT, byId["re"]?.kind)
    }

    @Test fun argvWholeNumberDoublesShowAsIntegers() {
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Sleep","kind":"execute","status":"pending",
               "rawInput":{"command":["sleep",5.0]}}"""
        )))
        assertEquals("sleep 5", (list.single() as CodeEvent.ToolCall).detail)
    }

    @Test fun toolCallIdWrittenAsADoubleStillPatches() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":5.0,"title":"Sleep","kind":"execute","status":"pending",
               "rawInput":{"command":["sleep",1]}}""", seq = 1),
            update("""{"sessionUpdate":"tool_call_update","toolCallId":5,"status":"completed",
               "rawInput":{"command":["sleep",1]}}""", seq = 2),
        )
        val list = foldFresh(frames)
        val tool = list.filterIsInstance<CodeEvent.ToolCall>().single()
        assertEquals("5", tool.callId)
        assertEquals(ToolStatus.COMPLETED, tool.status)
        assertEquals("sleep 1", tool.detail)
    }

    @Test fun scalarCommandArgWholeNumberDoublesShowAsIntegers() {
        val list = fold(listOf(update(
            """{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Exit","kind":"execute","status":"pending",
               "rawInput":{"command":"exit","args":5.0}}"""
        )))
        assertEquals("exit 5", (list.single() as CodeEvent.ToolCall).detail)
    }

    @Test fun moreCursorAcpToolNamesSetTheCardKind() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"as","title":"Wait","kind":"other","name":"AwaitShell","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"tw","title":"Todos","kind":"other","name":"TodoWrite","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"tk","title":"Task","kind":"other","name":"Task","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sm","title":"Mode","kind":"other","name":"SwitchMode","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"me","title":"Multi","kind":"other","name":"MultiEdit","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"cm","title":"MCP","kind":"other","name":"CallMcpTool","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gm","title":"Tools","kind":"other","name":"GetMcpTools","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"cd","title":"Dynamic","kind":"other","name":"CallDynamicTool","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sa","title":"Sub","kind":"other","name":"Subagent","status":"completed"}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals(ToolKind.THINK, byId["as"]?.kind)
        assertEquals(ToolKind.THINK, byId["tw"]?.kind)
        assertEquals(ToolKind.THINK, byId["tk"]?.kind)
        assertEquals(ToolKind.THINK, byId["sm"]?.kind)
        assertEquals(ToolKind.EDIT, byId["me"]?.kind)
        assertEquals(ToolKind.FETCH, byId["cm"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["gm"]?.kind)
        assertEquals(ToolKind.FETCH, byId["cd"]?.kind)
        assertEquals(ToolKind.THINK, byId["sa"]?.kind)
    }


    @Test fun moreCursorNativeToolNamesSetTheCardKind() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"gi","title":"Image","kind":"other","name":"GenerateImage","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ls","title":"List","kind":"other","name":"LS","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ap","title":"Patch","kind":"other","name":"ApplyPatch","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"aq","title":"Ask","kind":"other","name":"AskQuestion","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"wt","title":"Todos","kind":"other","name":"WriteTodos","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gd","title":"Lints","kind":"other","name":"GetDiagnostics","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"mf","title":"Move","kind":"other","name":"MoveFile","status":"completed"}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals(ToolKind.EDIT, byId["gi"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["ls"]?.kind)
        assertEquals(ToolKind.EDIT, byId["ap"]?.kind)
        assertEquals(ToolKind.THINK, byId["aq"]?.kind)
        assertEquals(ToolKind.THINK, byId["wt"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["gd"]?.kind)
        assertEquals(ToolKind.MOVE, byId["mf"]?.kind)
    }

    @Test fun cursorGlobAndListDirDetailUseNativeRawInputKeys() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"g1","title":"Glob","kind":"other","name":"Glob","status":"completed",
               "rawInput":{"glob_pattern":"**/*.kt"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"l1","title":"List","kind":"other","name":"LS","status":"completed",
               "rawInput":{"target_directory":"app/src"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"w1","title":"Search","kind":"other","name":"WebSearch","status":"completed",
               "rawInput":{"search_term":"ACP seq"}}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals("**/*.kt", byId["g1"]?.detail)
        assertEquals("app/src", byId["l1"]?.detail)
        assertEquals("ACP seq", byId["w1"]?.detail)
    }


    @Test fun evenMoreCursorToolNamesSetTheCardKind() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"ws","title":"Stdin","kind":"other","name":"WriteShellStdin","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"tr","title":"Todos","kind":"other","name":"TodoRead","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ss","title":"Symbols","kind":"other","name":"SearchSymbols","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"rg","title":"Grep","kind":"other","name":"RipgrepSearch","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"rr","title":"Grep","kind":"other","name":"RipgrepRawSearch","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"fl","title":"Fix","kind":"other","name":"FixLints","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gd","title":"Def","kind":"other","name":"GoToDefinition","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"pr","title":"PR","kind":"other","name":"FetchPullRequest","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ad","title":"Diff","kind":"other","name":"ApplyAgentDiff","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"t2","title":"Task","kind":"other","name":"TaskV2","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"cd","title":"Diagram","kind":"other","name":"CreateDiagram","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"cu","title":"UI","kind":"other","name":"ComputerUse","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"kb","title":"KB","kind":"other","name":"KnowledgeBase","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"rp","title":"Project","kind":"other","name":"ReadProject","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"up","title":"Project","kind":"other","name":"UpdateProject","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sf","title":"Sem","kind":"other","name":"SemanticSearchFull","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"rs","title":"Sem","kind":"other","name":"ReadSemsearchFiles","status":"completed"}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals(ToolKind.EXECUTE, byId["ws"]?.kind)
        assertEquals(ToolKind.THINK, byId["tr"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["ss"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["rg"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["rr"]?.kind)
        assertEquals(ToolKind.EDIT, byId["fl"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["gd"]?.kind)
        assertEquals(ToolKind.FETCH, byId["pr"]?.kind)
        assertEquals(ToolKind.EDIT, byId["ad"]?.kind)
        assertEquals(ToolKind.THINK, byId["t2"]?.kind)
        assertEquals(ToolKind.EDIT, byId["cd"]?.kind)
        assertEquals(ToolKind.EXECUTE, byId["cu"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["kb"]?.kind)
        assertEquals(ToolKind.READ, byId["rp"]?.kind)
        assertEquals(ToolKind.EDIT, byId["up"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["sf"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["rs"]?.kind)
    }

    @Test fun cursorMcpDetailUsesNativeRawInputKeys() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"m1","title":"MCP","kind":"other","name":"CallMcpTool","status":"completed",
               "rawInput":{"server":"cursor-github","toolName":"get_pull_request"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"m2","title":"Resource","kind":"other","name":"FetchMcpResource","status":"completed",
               "rawInput":{"server":"docs","uri":"file:///readme.md"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"m3","title":"MCP","kind":"other","name":"CallMcpTool","status":"completed",
               "rawInput":{"server":"user-GitLab","tool_name":"list_projects"}}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals("get_pull_request", byId["m1"]?.detail)
        assertEquals("file:///readme.md", byId["m2"]?.detail)
        assertEquals("list_projects", byId["m3"]?.detail)
    }


    @Test fun cursorAskToolCallIdWrittenAsDoubleLinksTheCard() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":9,"method":"cursor/ask_question","params":{"sessionId":"s1","toolCallId":5.0,"questions":[{"id":1.0,"prompt":"Go?","options":[{"id":2.0,"label":"Yes"},{"id":"no","label":"No"}]}]}}""")
        val approval = out.filterIsInstance<AdapterOutput.Update>().map { it.update }
            .filterIsInstance<CodeUpdate.Upsert>().map { it.event }
            .filterIsInstance<CodeEvent.Approval>().single()
        assertEquals("5", approval.callId)
        assertEquals(listOf("2", "no"), approval.options.map { it.id })
        val answer = Json.parseToJsonElement(acp.answerApproval(approval.requestId, "2")).jsonObject
        val outcome = answer["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("1", outcome["answers"]!!.jsonArray.single().jsonObject["questionId"]!!.jsonPrimitive.content)
        assertEquals("2", outcome["answers"]!!.jsonArray.single().jsonObject["selectedOptionIds"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun cursorTodoIdWrittenAsDoubleMergesByDigitString() {
        val first = acp.decode("""{"jsonrpc":"2.0","method":"cursor/update_todos","params":{"sessionId":"s1","todos":[{"id":3.0,"content":"One","status":"pending"}]}}""")
        val second = acp.decode("""{"jsonrpc":"2.0","method":"cursor/update_todos","params":{"sessionId":"s1","merge":true,"todos":[{"id":"3.0","content":"One done","status":"completed"}]}}""")
        val firstPlan = first.filterIsInstance<AdapterOutput.Update>().map { it.update }
            .filterIsInstance<CodeUpdate.Upsert>().map { it.event }
            .filterIsInstance<CodeEvent.Plan>().single()
        assertEquals("3", firstPlan.entries.single().id)
        val plan = second.filterIsInstance<AdapterOutput.Update>().map { it.update }
            .filterIsInstance<CodeUpdate.Upsert>().map { it.event }
            .filterIsInstance<CodeEvent.Plan>().single()
        assertEquals(1, plan.entries.size)
        assertEquals("3", plan.entries.single().id)
        assertEquals("One done", plan.entries.single().content)
        assertEquals(PlanStatus.COMPLETED, plan.entries.single().status)
    }

    @Test fun yetMoreCursorToolNamesSetTheCardKind() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"lm","title":"Machines","kind":"other","name":"ListMachines","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"cb","title":"Copy","kind":"other","name":"CopyToBox","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"cf","title":"Copy","kind":"other","name":"CopyFromBox","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"uf","title":"Upload","kind":"other","name":"UploadFile","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"df","title":"Download","kind":"other","name":"DownloadFile","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"bf","title":"Followup","kind":"other","name":"BackgroundComposerFollowup","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ca","title":"Cloud","kind":"other","name":"CloudAgent","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"cr","title":"Create","kind":"other","name":"CreateAgent","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sa","title":"Send","kind":"other","name":"SendToAgent","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"cs","title":"Check","kind":"other","name":"CheckSubagent","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gs","title":"Status","kind":"other","name":"GetMcpServerStatus","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sp","title":"Plugins","kind":"other","name":"SearchPlugins","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"rs","title":"Record","kind":"other","name":"RecordScreen","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"mcp","title":"MCP","kind":"other","name":"Mcp","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"de","title":"Draft","kind":"other","name":"DraftExternalMessage","status":"completed"}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals(ToolKind.SEARCH, byId["lm"]?.kind)
        assertEquals(ToolKind.MOVE, byId["cb"]?.kind)
        assertEquals(ToolKind.MOVE, byId["cf"]?.kind)
        assertEquals(ToolKind.FETCH, byId["uf"]?.kind)
        assertEquals(ToolKind.FETCH, byId["df"]?.kind)
        assertEquals(ToolKind.THINK, byId["bf"]?.kind)
        assertEquals(ToolKind.THINK, byId["ca"]?.kind)
        assertEquals(ToolKind.THINK, byId["cr"]?.kind)
        assertEquals(ToolKind.THINK, byId["sa"]?.kind)
        assertEquals(ToolKind.THINK, byId["cs"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["gs"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["sp"]?.kind)
        assertEquals(ToolKind.EXECUTE, byId["rs"]?.kind)
        assertEquals(ToolKind.FETCH, byId["mcp"]?.kind)
        assertEquals(ToolKind.EDIT, byId["de"]?.kind)
    }

    @Test fun cursorListDirAndTaskDetailUseNativeRawInputKeys() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"l1","title":"List","kind":"other","name":"ListDir","status":"completed",
               "rawInput":{"directory_path":"app/src"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Task","kind":"other","name":"Task","status":"completed",
               "rawInput":{"task_description":"Fix the flaky test"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"p1","title":"PR","kind":"other","name":"FetchPullRequest","status":"completed",
               "rawInput":{"pr_url":"https://github.com/o/r/pull/1"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"s1","title":"Stdin","kind":"other","name":"WriteShellStdin","status":"completed",
               "rawInput":{"shell_id":5.0,"content":"y\n"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"g1","title":"Image","kind":"other","name":"GenerateImage","status":"completed",
               "rawInput":{"prompt":"a red cube"}}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals("app/src", byId["l1"]?.detail)
        assertEquals("Fix the flaky test", byId["t1"]?.detail)
        assertEquals("https://github.com/o/r/pull/1", byId["p1"]?.detail)
        assertEquals("5", byId["s1"]?.detail)
        assertEquals("a red cube", byId["g1"]?.detail)
    }


    @Test fun permissionOptionIdWrittenAsDoubleMatchesAnswer() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":9,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"toolCallId":"c1","title":"Run","kind":"execute"},"options":[{"optionId":5.0,"name":"Allow","kind":"allow_once"},{"optionId":"6.0","name":"Deny","kind":"reject_once"}]}}""")
        val approval = out.filterIsInstance<AdapterOutput.Update>().map { it.update }
            .filterIsInstance<CodeUpdate.Upsert>().map { it.event }
            .filterIsInstance<CodeEvent.Approval>().single()
        assertEquals(listOf("5", "6"), approval.options.map { it.id })
        val answer = Json.parseToJsonElement(acp.answerApproval(approval.requestId, "5")).jsonObject
        val outcome = answer["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("5", outcome["optionId"]!!.jsonPrimitive.content)
    }

    @Test fun shellIdWrittenAsDoubleStringShowsAsDigit() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"s1","title":"Stdin","kind":"other","name":"WriteShellStdin","status":"completed",
               "rawInput":{"shell_id":"5.0","chars":"y\n"}}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals("5", byId["s1"]?.detail)
    }

    @Test fun evenMoreCursorNativeToolNamesSetTheCardKind() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"pe","title":"Patch","kind":"other","name":"PatchEdit","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"rt","title":"Todos","kind":"other","name":"ReadTodos","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"rv","title":"Shell","kind":"other","name":"RunTerminalCommandV2","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gd","title":"Def","kind":"other","name":"Gotodef","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"nr","title":"Notebook","kind":"other","name":"NotebookRead","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sl","title":"Sleep","kind":"other","name":"Sleep","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"wt","title":"Wait","kind":"other","name":"Wait","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"wp","title":"Wake","kind":"other","name":"WakeParent","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"su","title":"Send","kind":"other","name":"SendToUser","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"bn","title":"Browse","kind":"other","name":"BrowserNavigate","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ob","title":"Browse","kind":"other","name":"OpenBrowser","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"pr","title":"PR","kind":"other","name":"CreatePullRequest","status":"completed"}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals(ToolKind.EDIT, byId["pe"]?.kind)
        assertEquals(ToolKind.THINK, byId["rt"]?.kind)
        assertEquals(ToolKind.EXECUTE, byId["rv"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["gd"]?.kind)
        assertEquals(ToolKind.READ, byId["nr"]?.kind)
        assertEquals(ToolKind.THINK, byId["sl"]?.kind)
        assertEquals(ToolKind.THINK, byId["wt"]?.kind)
        assertEquals(ToolKind.THINK, byId["wp"]?.kind)
        assertEquals(ToolKind.FETCH, byId["su"]?.kind)
        assertEquals(ToolKind.EXECUTE, byId["bn"]?.kind)
        assertEquals(ToolKind.EXECUTE, byId["ob"]?.kind)
        assertEquals(ToolKind.EDIT, byId["pr"]?.kind)
    }

    @Test fun cursorCopyAndUploadDetailUseNativeRawInputKeys() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"c1","title":"Copy","kind":"other","name":"CopyToBox","status":"completed",
               "rawInput":{"computer_path":"/home/u/a.kt","box_path":"/workspace/a.kt"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"u1","title":"Upload","kind":"other","name":"UploadFile","status":"completed",
               "rawInput":{"connection":"user-onedrive","sourcePath":"/workspace/out.pdf"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"s1","title":"Stdin","kind":"other","name":"WriteShellStdin","status":"completed",
               "rawInput":{"chars":"y\n"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"m1","title":"Machines","kind":"other","name":"ListMachines","status":"completed",
               "rawInput":{"machineId":7.0}}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals("/home/u/a.kt", byId["c1"]?.detail)
        assertEquals("user-onedrive", byId["u1"]?.detail)
        assertEquals("y", byId["s1"]?.detail?.trim())
        assertEquals("7", byId["m1"]?.detail)
    }


    @Test fun permissionOptionIdAnswerGoesOutAsANumber() {
        val out = acp.decode("""{"jsonrpc":"2.0","id":9,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"toolCallId":"c1","title":"Run","kind":"execute"},"options":[{"optionId":5.0,"name":"Allow","kind":"allow_once"},{"optionId":"6.0","name":"Deny","kind":"reject_once"}]}}""")
        val approval = out.filterIsInstance<AdapterOutput.Update>().map { it.update }
            .filterIsInstance<CodeUpdate.Upsert>().map { it.event }
            .filterIsInstance<CodeEvent.Approval>().single()
        val answer = Json.parseToJsonElement(acp.answerApproval(approval.requestId, "5")).jsonObject
        val outcome = answer["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals(5L, outcome["optionId"]!!.jsonPrimitive.long)
    }

    @Test fun cursorAskAnswerIdsGoOutAsNumbers() {
        acp.decode("""{"jsonrpc":"2.0","id":9,"method":"cursor/ask_question","params":{"sessionId":"s1","questions":[{"id":1.0,"prompt":"Go?","options":[{"id":2.0,"label":"Yes"},{"id":"no","label":"No"}]}]}}""")
        val answer = Json.parseToJsonElement(acp.answerApproval("9", "2")).jsonObject
        val row = answer["result"]!!.jsonObject["outcome"]!!.jsonObject["answers"]!!.jsonArray.single().jsonObject
        assertEquals(1L, row["questionId"]!!.jsonPrimitive.long)
        assertEquals(2L, row["selectedOptionIds"]!!.jsonArray.single().jsonPrimitive.long)
    }

    @Test fun sessionIdWrittenAsDoubleStillMatches() {
        val frame = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":5.0,"_meta":{"seq":1},"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hi"}}}}"""
        val outs = acp.decode(frame)
        val update = outs.filterIsInstance<AdapterOutput.Update>().single()
        assertEquals("5", update.sessionId)
        assertEquals(1L, acp.lastSeq("5"))
    }

    @Test fun moreBrowserAndGithubToolNamesSetTheCardKind() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"bc","title":"Click","kind":"other","name":"BrowserClick","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"bt","title":"Type","kind":"other","name":"BrowserType","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"bs","title":"Snap","kind":"other","name":"BrowserSnapshot","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"bw","title":"Wait","kind":"other","name":"BrowserWait","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ts","title":"Shot","kind":"other","name":"TakeScreenshot","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ks","title":"Kill","kind":"other","name":"KillShell","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ls","title":"Shells","kind":"other","name":"ListShells","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ci","title":"Issue","kind":"other","name":"CreateIssue","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"cb","title":"Branch","kind":"other","name":"CreateBranch","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"mp","title":"Merge","kind":"other","name":"MergePullRequest","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ac","title":"Comment","kind":"other","name":"AddIssueComment","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gp","title":"PR","kind":"other","name":"GetPullRequest","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gf","title":"File","kind":"other","name":"GetFileContents","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sc","title":"Search","kind":"other","name":"SearchCode","status":"completed"}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals(ToolKind.EXECUTE, byId["bc"]?.kind)
        assertEquals(ToolKind.EXECUTE, byId["bt"]?.kind)
        assertEquals(ToolKind.READ, byId["bs"]?.kind)
        assertEquals(ToolKind.THINK, byId["bw"]?.kind)
        assertEquals(ToolKind.EXECUTE, byId["ts"]?.kind)
        assertEquals(ToolKind.EXECUTE, byId["ks"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["ls"]?.kind)
        assertEquals(ToolKind.EDIT, byId["ci"]?.kind)
        assertEquals(ToolKind.EDIT, byId["cb"]?.kind)
        assertEquals(ToolKind.EDIT, byId["mp"]?.kind)
        assertEquals(ToolKind.EDIT, byId["ac"]?.kind)
        assertEquals(ToolKind.FETCH, byId["gp"]?.kind)
        assertEquals(ToolKind.READ, byId["gf"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["sc"]?.kind)
    }

    @Test fun cursorDownloadAndGithubDetailUseNativeRawInputKeys() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"d1","title":"Download","kind":"other","name":"DownloadFile","status":"completed",
               "rawInput":{"fileId":"abc123","connection":"user-onedrive"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"u1","title":"Upload","kind":"other","name":"UploadFile","status":"completed",
               "rawInput":{"draftId":9.0,"connection":"user-Gmail"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"c1","title":"Copy","kind":"other","name":"CopyToBox","status":"completed",
               "rawInput":{"destination_path":"/workspace/out.kt"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"g1","title":"PR","kind":"other","name":"GetPullRequest","status":"completed",
               "rawInput":{"owner":"Warexpor","repo":"GradatiON"}}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals("abc123", byId["d1"]?.detail)
        assertEquals("9", byId["u1"]?.detail)
        assertEquals("/workspace/out.kt", byId["c1"]?.detail)
        assertEquals("Warexpor", byId["g1"]?.detail)
    }


    @Test fun digitSessionIdGoesOutAsANumber() {
        val load = Json.parseToJsonElement(acp.loadSession(2, "5", "/w", afterSeq = 1)).jsonObject
        assertEquals(5L, load["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.long)
        val prompt = Json.parseToJsonElement(acp.prompt(3, "6.0", "hi")).jsonObject
        assertEquals(6L, prompt["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.long)
        val cancel = Json.parseToJsonElement(acp.cancel("7")).jsonObject
        assertEquals(7L, cancel["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.long)
        val mode = Json.parseToJsonElement(acp.setMode(4, "8", PermissionMode.ASK)).jsonObject
        assertEquals(8L, mode["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.long)
        val status = Json.parseToJsonElement(acp.gitStatus(5, "9")).jsonObject
        assertEquals(9L, status["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.long)
        val diff = Json.parseToJsonElement(acp.diff(6, "10", "a.kt")).jsonObject
        assertEquals(10L, diff["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.long)
        // Non-digit ids stay JSON strings.
        val named = Json.parseToJsonElement(acp.loadSession(7, "sess-1", "/w", null)).jsonObject
        assertEquals("sess-1", named["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
    }

    @Test fun moreGithubAndBrowserToolNamesSetTheCardKind() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"gi","title":"Issue","kind":"other","name":"GetIssue","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ui","title":"Update","kind":"other","name":"UpdateIssue","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"li","title":"Issues","kind":"other","name":"ListIssues","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"lp","title":"PRs","kind":"other","name":"ListPullRequests","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"up","title":"UpdatePR","kind":"other","name":"UpdatePullRequest","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"pd","title":"Diff","kind":"other","name":"GetPullRequestDiff","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"pf","title":"Files","kind":"other","name":"ListPullRequestFiles","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gr","title":"Repo","kind":"other","name":"GetRepository","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sr","title":"Search","kind":"other","name":"SearchRepositories","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gc","title":"Commit","kind":"other","name":"GetCommit","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"lc","title":"Commits","kind":"other","name":"ListCommits","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"lb","title":"Branches","kind":"other","name":"ListBranches","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"cl","title":"Label","kind":"other","name":"CreateLabel","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ad","title":"Discuss","kind":"other","name":"AddDiscussionComment","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gd","title":"GetDisc","kind":"other","name":"GetDiscussion","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"cr","title":"Review","kind":"other","name":"CreatePullRequestReview","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"bt","title":"Tabs","kind":"other","name":"BrowserTabs","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"be","title":"Eval","kind":"other","name":"BrowserEvaluate","status":"completed"}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals(ToolKind.FETCH, byId["gi"]?.kind)
        assertEquals(ToolKind.EDIT, byId["ui"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["li"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["lp"]?.kind)
        assertEquals(ToolKind.EDIT, byId["up"]?.kind)
        assertEquals(ToolKind.READ, byId["pd"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["pf"]?.kind)
        assertEquals(ToolKind.FETCH, byId["gr"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["sr"]?.kind)
        assertEquals(ToolKind.READ, byId["gc"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["lc"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["lb"]?.kind)
        assertEquals(ToolKind.EDIT, byId["cl"]?.kind)
        assertEquals(ToolKind.EDIT, byId["ad"]?.kind)
        assertEquals(ToolKind.FETCH, byId["gd"]?.kind)
        assertEquals(ToolKind.EDIT, byId["cr"]?.kind)
        assertEquals(ToolKind.EXECUTE, byId["bt"]?.kind)
        assertEquals(ToolKind.EXECUTE, byId["be"]?.kind)
    }

    @Test fun cursorGithubDetailUseNativeRawInputKeys() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"i1","title":"Issue","kind":"other","name":"GetIssue","status":"completed",
               "rawInput":{"issue_number":42.0,"owner":"Warexpor"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"p1","title":"PR","kind":"other","name":"UpdatePullRequest","status":"completed",
               "rawInput":{"pull_number":"9.0","head":"feat"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"c1","title":"Commit","kind":"other","name":"GetCommit","status":"completed",
               "rawInput":{"sha":"abc1234","ref":"main"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"d1","title":"Discuss","kind":"other","name":"GetDiscussion","status":"completed",
               "rawInput":{"discussion_number":3,"label":"bug"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"w1","title":"Run","kind":"other","name":"GetWorkflowRun","status":"completed",
               "rawInput":{"run_id":55.0}}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals("42", byId["i1"]?.detail)
        assertEquals("9", byId["p1"]?.detail)
        assertEquals("abc1234", byId["c1"]?.detail)
        assertEquals("3", byId["d1"]?.detail)
        assertEquals("55", byId["w1"]?.detail)
    }

    @Test fun digitMethodIdGoesOutAsANumber() {
        val auth = Json.parseToJsonElement(acp.authenticate(2, "5")).jsonObject
        assertEquals(5L, auth["params"]!!.jsonObject["methodId"]!!.jsonPrimitive.long)
        val asDouble = Json.parseToJsonElement(acp.authenticate(3, "6.0")).jsonObject
        assertEquals(6L, asDouble["params"]!!.jsonObject["methodId"]!!.jsonPrimitive.long)
        // Non-digit ids stay JSON strings.
        val named = Json.parseToJsonElement(acp.authenticate(4, "agent-login")).jsonObject
        assertEquals("agent-login", named["params"]!!.jsonObject["methodId"]!!.jsonPrimitive.content)
    }

    @Test fun handshakeAuthMethodIdWrittenAsADoubleStillMatches() {
        val numeric = Json.parseToJsonElement(
            """{"protocolVersion":1,"authMethods":[{"id":5.0,"name":"Agent","type":"agent"}]}"""
        ).jsonObject
        val decision = AcpHandshake.decide(numeric) as AcpHandshake.Decision.Authenticate
        assertEquals("5", decision.methodId)
        val stringified = Json.parseToJsonElement(
            """{"protocolVersion":1,"authMethods":[{"methodId":"7.0","type":"agent"}]}"""
        ).jsonObject
        val again = AcpHandshake.decide(stringified) as AcpHandshake.Decision.Authenticate
        assertEquals("7", again.methodId)
    }

    @Test fun moreGithubOriginToolNamesSetTheCardKind() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"dd","title":"DelDisc","kind":"other","name":"DeleteDiscussionComment","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"dl","title":"DelLabel","kind":"other","name":"DeleteLabel","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"dp","title":"DelReview","kind":"other","name":"DeletePendingPullRequestReview","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gs","title":"Status","kind":"other","name":"GetCommitCombinedStatus","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gl","title":"Label","kind":"other","name":"GetLabel","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gr","title":"Release","kind":"other","name":"GetReleaseByTag","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"lc","title":"Cats","kind":"other","name":"ListDiscussionCategories","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"lj","title":"Jobs","kind":"other","name":"ListWorkflowRunJobs","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ln","title":"NS","kind":"other","name":"ListNamespaces","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"lr","title":"Repos","kind":"other","name":"ListRepositories","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gc","title":"Grep","kind":"other","name":"GrepContents","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ma","title":"Answer","kind":"other","name":"MarkDiscussionCommentAsAnswer","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ud","title":"UpDisc","kind":"other","name":"UpdateDiscussionComment","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ul","title":"UpLabel","kind":"other","name":"UpdateLabel","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"cc","title":"Comment","kind":"other","name":"CreatePullRequestComment","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"cr","title":"Repo","kind":"other","name":"CreateRepository","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"dr","title":"Dismiss","kind":"other","name":"DismissPullRequestReview","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"rr","title":"Reviewers","kind":"other","name":"RequestPullRequestReviewers","status":"completed"}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals(ToolKind.DELETE, byId["dd"]?.kind)
        assertEquals(ToolKind.DELETE, byId["dl"]?.kind)
        assertEquals(ToolKind.DELETE, byId["dp"]?.kind)
        assertEquals(ToolKind.FETCH, byId["gs"]?.kind)
        assertEquals(ToolKind.FETCH, byId["gl"]?.kind)
        assertEquals(ToolKind.FETCH, byId["gr"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["lc"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["lj"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["ln"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["lr"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["gc"]?.kind)
        assertEquals(ToolKind.EDIT, byId["ma"]?.kind)
        assertEquals(ToolKind.EDIT, byId["ud"]?.kind)
        assertEquals(ToolKind.EDIT, byId["ul"]?.kind)
        assertEquals(ToolKind.EDIT, byId["cc"]?.kind)
        assertEquals(ToolKind.EDIT, byId["cr"]?.kind)
        assertEquals(ToolKind.EDIT, byId["dr"]?.kind)
        assertEquals(ToolKind.EDIT, byId["rr"]?.kind)
    }

    @Test fun cursorGithubOriginDetailUseNativeRawInputKeys() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Release","kind":"other","name":"GetReleaseByTag","status":"completed",
               "rawInput":{"tag":"v1.2.3","owner":"Warexpor"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"j1","title":"Jobs","kind":"other","name":"ListWorkflowRunJobs","status":"completed",
               "rawInput":{"job_id":"9.0"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"c1","title":"Comment","kind":"other","name":"UpdateDiscussionComment","status":"completed",
               "rawInput":{"comment_id":42.0}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"r1","title":"Review","kind":"other","name":"DismissPullRequestReview","status":"completed",
               "rawInput":{"review_id":7}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"u1","title":"User","kind":"other","name":"ListNamespaces","status":"completed",
               "rawInput":{"username":"warexpor"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"o1","title":"Org","kind":"other","name":"ListRepositories","status":"completed",
               "rawInput":{"org":"acme","category":"public"}}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals("v1.2.3", byId["t1"]?.detail)
        assertEquals("9", byId["j1"]?.detail)
        assertEquals("42", byId["c1"]?.detail)
        assertEquals("7", byId["r1"]?.detail)
        assertEquals("warexpor", byId["u1"]?.detail)
        assertEquals("acme", byId["o1"]?.detail)
    }

    @Test fun namedModeIdStaysAStringOnSetMode() {
        val mode = Json.parseToJsonElement(acp.setMode(4, "s1", PermissionMode.ASK)).jsonObject
        assertEquals("ask", mode["params"]!!.jsonObject["modeId"]!!.jsonPrimitive.content)
        val auto = Json.parseToJsonElement(acp.setMode(5, "s1", PermissionMode.FULL_AUTO)).jsonObject
        assertEquals("full-auto", auto["params"]!!.jsonObject["modeId"]!!.jsonPrimitive.content)
    }

    @Test fun moreGitlabAndCloudflareToolNamesSetTheCardKind() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"gm","title":"MR","kind":"other","name":"GetMergeRequest","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gp","title":"Project","kind":"other","name":"GetProject","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"gw","title":"Work","kind":"other","name":"GetWorkItem","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ga","title":"Artifact","kind":"other","name":"GetArtifactFile","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"lm","title":"MRs","kind":"other","name":"ListMergeRequests","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"lp","title":"Pipes","kind":"other","name":"ListPipelines","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"lw","title":"Items","kind":"other","name":"ListWorkItems","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"lg","title":"Groups","kind":"other","name":"ListGroups","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sm","title":"SaveMR","kind":"other","name":"SaveMergeRequest","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"am","title":"Accept","kind":"other","name":"AcceptMergeRequest","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ab","title":"Branch","kind":"other","name":"AddBranch","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sw","title":"SaveWI","kind":"other","name":"SaveWorkItem","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"wl","title":"Workers","kind":"other","name":"WorkersList","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"wg","title":"Worker","kind":"other","name":"WorkersGetWorker","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"wb","title":"Build","kind":"other","name":"WorkersBuildsGetBuild","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"wbl","title":"Builds","kind":"other","name":"WorkersBuildsListBuilds","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sc","title":"CFDocs","kind":"other","name":"SearchCloudflareDocumentation","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"qo","title":"Obs","kind":"other","name":"QueryWorkerObservability","status":"completed"}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals(ToolKind.FETCH, byId["gm"]?.kind)
        assertEquals(ToolKind.FETCH, byId["gp"]?.kind)
        assertEquals(ToolKind.FETCH, byId["gw"]?.kind)
        assertEquals(ToolKind.FETCH, byId["ga"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["lm"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["lp"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["lw"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["lg"]?.kind)
        assertEquals(ToolKind.EDIT, byId["sm"]?.kind)
        assertEquals(ToolKind.EDIT, byId["am"]?.kind)
        assertEquals(ToolKind.EDIT, byId["ab"]?.kind)
        assertEquals(ToolKind.EDIT, byId["sw"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["wl"]?.kind)
        assertEquals(ToolKind.FETCH, byId["wg"]?.kind)
        assertEquals(ToolKind.FETCH, byId["wb"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["wbl"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["sc"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["qo"]?.kind)
    }

    @Test fun cursorGitlabCloudflareDetailUseNativeRawInputKeys() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"m1","title":"MR","kind":"other","name":"GetMergeRequest","status":"completed",
               "rawInput":{"merge_request_iid":42.0,"project_id":"acme/app"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"p1","title":"Pipe","kind":"other","name":"GetPipeline","status":"completed",
               "rawInput":{"pipeline_id":"9.0"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"w1","title":"Work","kind":"other","name":"GetWorkItem","status":"completed",
               "rawInput":{"work_item_iid":7}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"g1","title":"Groups","kind":"other","name":"ListGroups","status":"completed",
               "rawInput":{"group_id":"acme"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"b1","title":"Build","kind":"other","name":"WorkersBuildsGetBuild","status":"completed",
               "rawInput":{"buildUUID":"abc-123","account_id":"acct"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"s1","title":"Worker","kind":"other","name":"WorkersGetWorker","status":"completed",
               "rawInput":{"scriptName":"api","worker_id":"55.0"}}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals("42", byId["m1"]?.detail)
        assertEquals("9", byId["p1"]?.detail)
        assertEquals("7", byId["w1"]?.detail)
        assertEquals("acme", byId["g1"]?.detail)
        assertEquals("abc-123", byId["b1"]?.detail)
        assertEquals("api", byId["s1"]?.detail)
    }


    @Test fun digitModelIdGoesOutAsANumberOnNewSession() {
        val frame = Json.parseToJsonElement(
            acp.newSession(
                1,
                NewSessionRequest("h", HarnessKind.CODEX, "/w", "hi", PermissionMode.ASK, model = "5"),
            ),
        ).jsonObject
        val meta = frame["params"]!!.jsonObject["_meta"]!!.jsonObject
        assertEquals(5L, meta["model"]!!.jsonPrimitive.long)
        val named = Json.parseToJsonElement(
            acp.newSession(
                2,
                NewSessionRequest("h", HarnessKind.CODEX, "/w", "hi", PermissionMode.ASK, model = "gpt-5"),
            ),
        ).jsonObject
        assertEquals("gpt-5", named["params"]!!.jsonObject["_meta"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        val asDouble = Json.parseToJsonElement(
            acp.newSession(
                3,
                NewSessionRequest("h", HarnessKind.CODEX, "/w", "hi", PermissionMode.ASK, model = "6.0"),
            ),
        ).jsonObject
        assertEquals(6L, asDouble["params"]!!.jsonObject["_meta"]!!.jsonObject["model"]!!.jsonPrimitive.long)
    }

    @Test fun moreGithubBrowserAndGitlabToolNamesSetTheCardKind() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"rc","title":"ReviewComments","kind":"other","name":"ListPullRequestReviewComments","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"bf","title":"Fill","kind":"other","name":"BrowserFillForm","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ba","title":"Attr","kind":"other","name":"BrowserGetAttribute","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"bi","title":"Input","kind":"other","name":"BrowserGetInputValue","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"fk","title":"Fork","kind":"other","name":"ForkRepository","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"lw","title":"Link","kind":"other","name":"LinkWorkItems","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"mp","title":"Pipe","kind":"other","name":"ManagePipeline","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ac","title":"Commit","kind":"other","name":"AddCommit","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sn","title":"Note","kind":"other","name":"SaveNote","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sp","title":"SavePipe","kind":"other","name":"SavePipeline","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sr","title":"Review","kind":"other","name":"SaveMergeRequestReview","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"lm","title":"Members","kind":"other","name":"ListProjectMembers","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"lt","title":"Tree","kind":"other","name":"ListRepositoryTree","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sl","title":"Labels","kind":"other","name":"SearchLabels","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"sv","title":"SavedView","kind":"other","name":"GetSavedViewWorkItems","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"wt","title":"Types","kind":"other","name":"GetWorkItemTypes","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"mn","title":"Notes","kind":"other","name":"GetMergeRequestNotes","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"bl","title":"BuildLogs","kind":"other","name":"WorkersBuildsGetBuildLogs","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"wc","title":"WorkerCode","kind":"other","name":"WorkersGetWorkerCode","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ok","title":"ObsKeys","kind":"other","name":"ObservabilityKeys","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"ov","title":"ObsVals","kind":"other","name":"ObservabilityValues","status":"completed"}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"mg","title":"Migrate","kind":"other","name":"MigratePagesToWorkersGuide","status":"completed"}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals(ToolKind.SEARCH, byId["rc"]?.kind)
        assertEquals(ToolKind.EXECUTE, byId["bf"]?.kind)
        assertEquals(ToolKind.READ, byId["ba"]?.kind)
        assertEquals(ToolKind.READ, byId["bi"]?.kind)
        assertEquals(ToolKind.EDIT, byId["fk"]?.kind)
        assertEquals(ToolKind.EDIT, byId["lw"]?.kind)
        assertEquals(ToolKind.EDIT, byId["mp"]?.kind)
        assertEquals(ToolKind.EDIT, byId["ac"]?.kind)
        assertEquals(ToolKind.EDIT, byId["sn"]?.kind)
        assertEquals(ToolKind.EDIT, byId["sp"]?.kind)
        assertEquals(ToolKind.EDIT, byId["sr"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["lm"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["lt"]?.kind)
        assertEquals(ToolKind.SEARCH, byId["sl"]?.kind)
        assertEquals(ToolKind.FETCH, byId["sv"]?.kind)
        assertEquals(ToolKind.FETCH, byId["wt"]?.kind)
        assertEquals(ToolKind.FETCH, byId["mn"]?.kind)
        assertEquals(ToolKind.FETCH, byId["bl"]?.kind)
        assertEquals(ToolKind.FETCH, byId["wc"]?.kind)
        assertEquals(ToolKind.FETCH, byId["ok"]?.kind)
        assertEquals(ToolKind.FETCH, byId["ov"]?.kind)
        assertEquals(ToolKind.FETCH, byId["mg"]?.kind)
    }

    @Test fun cursorGitlabGithubDetailUseMoreNativeRawInputKeys() {
        val frames = listOf(
            update("""{"sessionUpdate":"tool_call","toolCallId":"a1","title":"Artifact","kind":"other","name":"GetArtifactFile","status":"completed",
               "rawInput":{"artifact_path":"coverage/index.html"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"s1","title":"Saved","kind":"other","name":"GetSavedViewWorkItems","status":"completed",
               "rawInput":{"saved_view_id":"55.0"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"d1","title":"Note","kind":"other","name":"SaveNote","status":"completed",
               "rawInput":{"discussion_id":"42.0"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"l1","title":"Labels","kind":"other","name":"SearchLabels","status":"completed",
               "rawInput":{"full_path":"acme/app"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"m1","title":"MR","kind":"other","name":"SaveMergeRequest","status":"completed",
               "rawInput":{"milestone_id":7}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"u1","title":"List","kind":"other","name":"ListMergeRequests","status":"completed",
               "rawInput":{"author_username":"warexpor"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"b1","title":"Branch","kind":"other","name":"AddBranch","status":"completed",
               "rawInput":{"source_branch":"feat"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"q1","title":"Search","kind":"other","name":"SemanticSearch","status":"completed",
               "rawInput":{"q":"auth middleware"}}"""),
            update("""{"sessionUpdate":"tool_call","toolCallId":"i1","title":"Work","kind":"other","name":"GetWorkItem","status":"completed",
               "rawInput":{"iid":3.0}}"""),
        )
        val list = foldFresh(frames)
        val byId = list.filterIsInstance<CodeEvent.ToolCall>().associateBy { it.callId }
        assertEquals("coverage/index.html", byId["a1"]?.detail)
        assertEquals("55", byId["s1"]?.detail)
        assertEquals("42", byId["d1"]?.detail)
        assertEquals("acme/app", byId["l1"]?.detail)
        assertEquals("7", byId["m1"]?.detail)
        assertEquals("warexpor", byId["u1"]?.detail)
        assertEquals("feat", byId["b1"]?.detail)
        assertEquals("auth middleware", byId["q1"]?.detail)
        assertEquals("3", byId["i1"]?.detail)
    }

    private fun foldFresh(frames: List<String>): List<CodeEvent> {
        val fresh = AcpAdapter()
        var list = emptyList<CodeEvent>()
        frames.flatMap { fresh.decode(it) }.forEach { out ->
            if (out is AdapterOutput.Update) list = TranscriptReducer.apply(list, out.update, now = 1L)
        }
        return list
    }
}
