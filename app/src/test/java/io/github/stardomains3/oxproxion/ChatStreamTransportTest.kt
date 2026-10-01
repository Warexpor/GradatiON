package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stream transport stands alone. Parsing a chunk does not need an Activity or a ViewModel.
 */
class ChatStreamTransportTest {

    @Test
    fun badChunkIsIgnored() {
        val transport = ChatStreamTransport(UnusedHost)
        assertNull(transport.parseForTest("{not json"))
    }

    @Test
    fun citationsRenderNumberedLines() {
        val transport = ChatStreamTransport(UnusedHost)
        val text = transport.citationsForTest(
            listOf(
                Annotation(
                    type = "url_citation",
                    url_citation = UrlCitation(
                        url = "https://example.com",
                        title = "Example",
                        start_index = 0,
                        end_index = 1,
                    ),
                )
            )
        )
        assertTrue(text.contains("[1]"))
        assertTrue(text.contains("Example"))
        assertTrue(text.contains("https://example.com"))
        assertEquals("", transport.citationsForTest(null))
    }

    private fun chunk(index: Int?, id: String? = null, name: String? = null, args: String? = null) =
        ToolCallChunk(index = index, id = id, function = FunctionCallChunk(name = name, arguments = args))

    @Test
    fun splitToolCallIsAssembledFromFragments() {
        val buffer = mutableListOf<ToolCall>()
        absorbToolCallChunks(buffer, listOf(chunk(0, id = "call_1", name = "set_timer", args = "")))
        absorbToolCallChunks(buffer, listOf(chunk(0, args = "{\"minu")))
        absorbToolCallChunks(buffer, listOf(chunk(0, args = "tes\":5}")))
        assertEquals(1, buffer.size)
        assertEquals("call_1", buffer[0].id)
        assertEquals("set_timer", buffer[0].function.name)
        assertEquals("{\"minutes\":5}", buffer[0].function.arguments)
    }

    @Test
    fun idArrivingLateIsMerged() {
        val buffer = mutableListOf<ToolCall>()
        absorbToolCallChunks(buffer, listOf(chunk(0, name = "wait", args = "{")))
        absorbToolCallChunks(buffer, listOf(chunk(0, id = "call_9", args = "}")))
        assertEquals(1, buffer.size)
        assertEquals("call_9", buffer[0].id)
        assertEquals("{}", buffer[0].function.arguments)
    }

    @Test
    fun nameRepeatedOnEveryFragmentIsNotDoubled() {
        val buffer = mutableListOf<ToolCall>()
        absorbToolCallChunks(buffer, listOf(chunk(0, id = "c", name = "wait", args = "{\"a\":")))
        absorbToolCallChunks(buffer, listOf(chunk(0, name = "wait", args = "1}")))
        assertEquals("wait", buffer.single().function.name)
        assertEquals("{\"a\":1}", buffer.single().function.arguments)
    }

    @Test
    fun missingIndexDoesNotDropTheCall() {
        val buffer = mutableListOf<ToolCall>()
        absorbToolCallChunks(buffer, listOf(chunk(null, id = "a", name = "wait", args = "{}")))
        absorbToolCallChunks(buffer, listOf(chunk(null, id = "b", name = "set_timer", args = "{}")))
        assertEquals(listOf("a", "b"), buffer.map { it.id })
        assertEquals(listOf("wait", "set_timer"), buffer.map { it.function.name })
    }

    @Test
    fun serverThatReusesIndexZeroForEveryCallStillGetsSeparateCalls() {
        val buffer = mutableListOf<ToolCall>()
        absorbToolCallChunks(buffer, listOf(chunk(0, id = "a", name = "wait", args = "{}")))
        absorbToolCallChunks(buffer, listOf(chunk(0, id = "b", name = "set_timer", args = "{}")))
        assertEquals(listOf("a", "b"), buffer.map { it.id })
    }

    @Test
    fun twoCallsInOneDeltaAreBothKept() {
        val buffer = mutableListOf<ToolCall>()
        absorbToolCallChunks(buffer, listOf(chunk(0, id = "a", name = "wait", args = "{}"), chunk(1, id = "b", name = "x", args = "{}")))
        assertEquals(2, buffer.size)
    }

    @Test
    fun toolArgumentsStopGrowingPastTheCap() {
        maxToolArgumentCharsForTest = 8
        try {
            val buffer = mutableListOf<ToolCall>()
            absorbToolCallChunks(buffer, listOf(chunk(0, id = "a", name = "write", args = "12345")))
            absorbToolCallChunks(buffer, listOf(chunk(0, args = "67890EXTRA")))
            assertEquals("12345678", buffer.single().function.arguments)
        } finally {
            maxToolArgumentCharsForTest = null
        }
    }

    @Test
    fun extraToolCallsPastTheCapAreDropped() {
        maxToolCallsForTest = 2
        try {
            val buffer = mutableListOf<ToolCall>()
            absorbToolCallChunks(
                buffer,
                listOf(
                    chunk(null, id = "a", name = "one", args = "{}"),
                    chunk(null, id = "b", name = "two", args = "{}"),
                    chunk(null, id = "c", name = "three", args = "{}"),
                ),
            )
            assertEquals(listOf("a", "b"), buffer.map { it.id })
        } finally {
            maxToolCallsForTest = null
        }
    }

    @Test
    fun audioChunksStopAtTheCap() {
        val buffer = StringBuilder()
        assertTrue(appendAudioChunk(buffer, "abcd", max = 6))
        assertFalse(appendAudioChunk(buffer, "efgh", max = 6))
        assertEquals("abcdef", buffer.toString())
        assertFalse(appendAudioChunk(buffer, "z", max = 6))
        assertEquals(6, buffer.length)
    }

    @Test
    fun missingIdsAreFilled() {
        val buffer = mutableListOf<ToolCall>()
        absorbToolCallChunks(buffer, listOf(chunk(0, name = "wait", args = "{}")))
        fillMissingToolCallIds(buffer)
        assertTrue(buffer.single().id.isNotBlank())
    }

    @Test
    fun toolCallChunkDecodesWithoutAnIndex() {
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<StreamedChatResponse>(
            """{"choices":[{"delta":{"tool_calls":[{"id":"x","function":{"name":"wait","arguments":"{}"}}]}}]}"""
        )
        assertNull(parsed.choices.single().delta!!.toolCalls!!.single().index)
    }

    @Test
    fun requestOmitsUiOnlyMessageFields() {
        val messages = listOf(
            FlexibleMessage(role = "user", content = JsonPrimitive("hi"), imageUri = "content://x/1"),
            FlexibleMessage(
                role = "assistant",
                content = JsonPrimitive("hello"),
                toolsUsed = true,
                reasoning = "secret chain of thought",
                thinking = "more thoughts",
                toolCalls = listOf(ToolCall("c1", "function", FunctionCall("wait", "{}"))),
            ),
            FlexibleMessage(role = "tool", content = JsonPrimitive("ok"), toolCallId = "c1"),
        )
        val wire = Json { ignoreUnknownKeys = true }
            .encodeToString(ChatRequest(model = "m", messages = messages.toApiMessages()))
        assertFalse(wire.contains("image_uri"))
        assertFalse(wire.contains("reasoning"))
        assertFalse(wire.contains("thinking"))
        assertFalse(wire.contains("toolsUsed"))
        assertFalse(wire.contains("content://x/1"))
        assertTrue(wire.contains("tool_calls"))
        assertTrue(wire.contains("tool_call_id"))
        // The transcript itself keeps what the UI needs.
        assertEquals("content://x/1", messages[0].imageUri)
    }
}

/** Test-only entry points. Production code calls the suspend handlers. */
internal fun ChatStreamTransport.parseForTest(json: String) = parseStreamChunk(json)
internal fun ChatStreamTransport.citationsForTest(annotations: List<Annotation>?) = formatCitations(annotations)

private object UnusedHost : ChatStreamHost {
    override val application: Application get() = error("unused")
    override val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
    override val json: Json = Json { ignoreUnknownKeys = true }
    override val demoHttpClient: HttpClient get() = error("unused")
    override val httpClient: HttpClient get() = error("unused")
    override val lanHttpClient: HttpClient get() = error("unused")
    override val sharedPreferencesHelper: SharedPreferencesHelper get() = error("unused")
    override val activeChatModelState = MutableLiveData<String>()
    override val activeChatModel: LiveData<String> = activeChatModelState
    override val isReasoningEnabled = MutableLiveData(false)
    override val isToolsEnabled = MutableLiveData(false)
    override val toolUiEvent = MutableLiveData<Event<String>>()
    override val toastUiEvent = MutableLiveData<Event<String>>()
    override var activeChatUrl: String = ""
    override var activeChatApiKey: String = ""
    override var activeStreamPump: StreamUiPump? = null
    override var pendingRpSwipeAppend: Boolean = false
    override var discardableRpAssistantInFlight: Boolean = false
    override var toolCallsHandledForTurn: Boolean = false
    override fun isReasoningModel(modelIdentifier: String?) = false
    override fun isImageGenerationModel(modelIdentifier: String?) = false
    override fun isRpMode() = false
    override fun activeModelIsLan() = false
    override fun buildTools(): List<Tool> = emptyList()
    override suspend fun handleToolCalls(toolCalls: List<ToolCall>, thinkingMessage: FlexibleMessage?) = Unit
    override fun buildWebSearchPlugin(): List<Plugin>? = null
    override fun parseOpenRouterError(responseText: String) = responseText
    override fun finalizeAssistantContent(text: String) = text
    override fun getModelDisplayName(apiIdentifier: String) = apiIdentifier
    override suspend fun downloadImages(imageUrls: List<String>): List<String> = emptyList()
    override fun saveBinaryFileToDownloads(filename: String, bytes: ByteArray, mimeType: String) = Unit
    override fun updateMessages(updateBlock: (MutableList<FlexibleMessage>) -> Unit) = Unit
    override fun putAssistantMessage(
        list: MutableList<FlexibleMessage>,
        thinkingMessage: FlexibleMessage?,
        newMessage: FlexibleMessage,
    ) = Unit
    override fun removeAssistantPlaceholder(thinkingMessage: FlexibleMessage?) = Unit
    override fun restoreRpSwipeAltIfMissingAssistant() = Unit
    override fun handleError(e: Throwable, thinkingMessage: FlexibleMessage?) = Unit
}
