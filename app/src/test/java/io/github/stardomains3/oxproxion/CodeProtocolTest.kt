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

    private fun foldFresh(frames: List<String>): List<CodeEvent> {
        val fresh = AcpAdapter()
        var list = emptyList<CodeEvent>()
        frames.flatMap { fresh.decode(it) }.forEach { out ->
            if (out is AdapterOutput.Update) list = TranscriptReducer.apply(list, out.update, now = 1L)
        }
        return list
    }
}
