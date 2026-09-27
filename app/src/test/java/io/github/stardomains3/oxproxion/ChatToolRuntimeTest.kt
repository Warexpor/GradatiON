package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tool runtime stands alone: no Activity, no ChatViewModel. The host is a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class ChatToolRuntimeTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun buildToolsIsEmptyUntilTheUserStoresASelection() {
        val runtime = runtime(SharedPreferencesHelper(app))
        assertTrue(runtime.buildTools().isEmpty())
    }

    @Test
    fun buildToolsReturnsOnlyTheEnabledNames() {
        val prefs = SharedPreferencesHelper(app)
        prefs.saveEnabledTools(setOf("make_file", "set_timer", "list_grokion_files"))
        val names = runtime(prefs).buildTools().map { it.function?.name }.toSet()
        assertTrue(names.contains("make_file"))
        assertTrue(names.contains("set_timer"))
        assertTrue(names.contains("list_gradation_files"))
        assertFalse(names.contains("delete_files"))
    }

    @Test
    fun blockedDestructiveToolDoesNotTouchTheFilesystem() {
        val prefs = SharedPreferencesHelper(app)
        val host = FakeToolHost(app, prefs)
        val runtime = ChatToolRuntime(host)
        pump {
            runtime.handleToolCalls(
                listOf(ToolCall("1", "function", FunctionCall("delete_files", """{"filepaths":["notes.txt"]}"""))),
                thinkingMessage = null,
            )
        }
        val toolText = host.messages
            .filter { it.role == "tool" }
            .map { (it.content as JsonPrimitive).contentOrNull }
            .joinToString()
        assertTrue(toolText.contains("Destructive tool blocked"))
        assertEquals("Destructive tool blocked — enable in Settings", host.toasts.single().peekContent())
        assertTrue(host.continued)
    }

    @Test
    fun duplicateToolCallsRunOnce() {
        val host = FakeToolHost(app, SharedPreferencesHelper(app))
        pump {
            ChatToolRuntime(host).handleToolCalls(
                listOf(
                    ToolCall("a", "function", FunctionCall("not_a_tool", "{}")),
                    ToolCall("b", "function", FunctionCall("not_a_tool", "{}")),
                ),
                thinkingMessage = null,
            )
        }
        assertEquals(1, host.messages.count { it.role == "tool" })
        assertEquals(
            "Error: Unknown tool call",
            (host.messages.single { it.role == "tool" }.content as JsonPrimitive).jsonPrimitive.content,
        )
    }

    @Test
    fun recursionLimitStopsBeforeAnotherToolRuns() {
        val host = FakeToolHost(app, SharedPreferencesHelper(app))
        host.toolRecursionDepth = 8
        pump {
            ChatToolRuntime(host).handleToolCalls(
                listOf(ToolCall("1", "function", FunctionCall("not_a_tool", "{}"))),
                thinkingMessage = null,
            )
        }
        val text = host.messages.joinToString { (it.content as? JsonPrimitive)?.contentOrNull.orEmpty() }
        assertTrue(text.contains("Tool recursion limit reached"))
        assertTrue(host.messages.none { it.role == "tool" })
    }

    /**
     * Tool dispatch hops to the main dispatcher. The test thread is that looper, so the
     * call runs on a worker and this thread drains the queue. Otherwise runBlocking deadlocks.
     */
    private fun pump(block: suspend () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        Thread {
            try {
                runBlocking { block() }
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!done.await(20, TimeUnit.MILLISECONDS)) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) fail("tool call did not finish")
        }
        shadowOf(Looper.getMainLooper()).idle()
        failure?.let { throw it }
    }

    private fun runtime(prefs: SharedPreferencesHelper) = ChatToolRuntime(FakeToolHost(app, prefs))
}

private class FakeToolHost(
    override val application: Application,
    override val sharedPreferencesHelper: SharedPreferencesHelper,
) : ChatToolHost {
    override val json: Json = Json { ignoreUnknownKeys = true }
    override val httpClient: HttpClient = HttpClient(OkHttp)
    override val chatMessages = MutableLiveData<List<FlexibleMessage>>(emptyList())
    override val activeChatModel = MutableLiveData("demo")
    override val scrollToBottomEvent = MutableLiveData<Event<Unit>>()
    override val toolUiEvent = MutableLiveData<Event<String>>()
    override val toastUiEvent = MutableLiveData<Event<String>>()
    override var streamingAssistantIndex: Int = -1
    override var toolCallsHandledForTurn: Boolean = false
    override var toolRecursionDepth: Int = 0
    val messages = mutableListOf<FlexibleMessage>()
    val toasts = mutableListOf<Event<String>>()
    var continued = false

    override fun activeModelIsLan(): Boolean = false

    override fun updateMessages(updateBlock: (MutableList<FlexibleMessage>) -> Unit) {
        val current = messages.toMutableList()
        updateBlock(current)
        messages.clear()
        messages.addAll(current)
        chatMessages.value = current.toList()
    }

    override fun putAssistantMessage(
        list: MutableList<FlexibleMessage>,
        thinkingMessage: FlexibleMessage?,
        newMessage: FlexibleMessage,
    ) {
        list.add(newMessage)
    }

    override fun removeAssistantPlaceholder(thinkingMessage: FlexibleMessage?) = Unit

    override suspend fun continueConversation(messages: List<FlexibleMessage>) {
        continued = true
    }

    init {
        toastUiEvent.observeForever { event -> if (event != null) toasts.add(event) }
    }
}
