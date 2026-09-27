package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
