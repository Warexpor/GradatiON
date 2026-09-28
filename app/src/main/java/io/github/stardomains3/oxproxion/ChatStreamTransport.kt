package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.github.stardomains3.oxproxion.SharedPreferencesHelper.Companion.LAN_PROVIDER_LLAMA_CPP
import io.github.stardomains3.oxproxion.SharedPreferencesHelper.Companion.LAN_PROVIDER_OLLAMA
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds

/**
 * LAN and cloud chat completion, streamed and not. Split out of [ChatViewModel] so tests can
 * construct a [ChatStreamTransport] with a [ChatStreamHost] and no Activity. The ViewModel
 * still owns the transcript; this class owns the HTTP turn.
 */
internal interface ChatStreamHost {
    val application: Application
    val scope: CoroutineScope
    val json: Json
    val demoHttpClient: HttpClient
    val httpClient: HttpClient
    val lanHttpClient: HttpClient
    val sharedPreferencesHelper: SharedPreferencesHelper
    val activeChatModelState: MutableLiveData<String>
    val activeChatModel: LiveData<String>
    val isReasoningEnabled: MutableLiveData<Boolean>
    val isToolsEnabled: MutableLiveData<Boolean>
    val toolUiEvent: MutableLiveData<Event<String>>
    val toastUiEvent: MutableLiveData<Event<String>>
    var activeChatUrl: String
    var activeChatApiKey: String
    var activeStreamPump: StreamUiPump?
    var pendingRpSwipeAppend: Boolean
    var discardableRpAssistantInFlight: Boolean
    var toolCallsHandledForTurn: Boolean
    fun isReasoningModel(modelIdentifier: String?): Boolean
    /** Worth asking for reasoning: known reasoning models, plus cloud models OpenRouter can ignore it on. */
    fun canRequestReasoning(modelIdentifier: String?): Boolean = isReasoningModel(modelIdentifier)
    fun isImageGenerationModel(modelIdentifier: String?): Boolean
    fun isRpMode(): Boolean
    fun activeModelIsLan(): Boolean
    fun buildTools(): List<Tool>
    suspend fun handleToolCalls(toolCalls: List<ToolCall>, thinkingMessage: FlexibleMessage?)
    fun buildWebSearchPlugin(): List<Plugin>?
    fun parseOpenRouterError(responseText: String): String
    fun finalizeAssistantContent(text: String): String
    fun getModelDisplayName(apiIdentifier: String): String
    suspend fun downloadImages(imageUrls: List<String>): List<String>
    fun saveBinaryFileToDownloads(filename: String, bytes: ByteArray, mimeType: String)
    fun updateMessages(updateBlock: (MutableList<FlexibleMessage>) -> Unit)
    fun putAssistantMessage(list: MutableList<FlexibleMessage>, thinkingMessage: FlexibleMessage?, newMessage: FlexibleMessage)
    fun removeAssistantPlaceholder(thinkingMessage: FlexibleMessage?)
    fun restoreRpSwipeAltIfMissingAssistant()
    fun handleError(e: Throwable, thinkingMessage: FlexibleMessage?)
}

internal class ChatStreamTransport(private val host: ChatStreamHost) {
    private val application get() = host.application
    private val viewModelScope get() = host.scope
    private val json get() = host.json
    private val demoHttpClient get() = host.demoHttpClient
    private val httpClient get() = host.httpClient
    private val lanHttpClient get() = host.lanHttpClient
    private val sharedPreferencesHelper get() = host.sharedPreferencesHelper
    private val _activeChatModel get() = host.activeChatModelState
    private val activeChatModel get() = host.activeChatModel
    private val _isReasoningEnabled get() = host.isReasoningEnabled
    private val _isToolsEnabled get() = host.isToolsEnabled
    private val _toolUiEvent get() = host.toolUiEvent
    private val _toastUiEvent get() = host.toastUiEvent
    private var activeChatUrl
        get() = host.activeChatUrl
        set(value) { host.activeChatUrl = value }
    private var activeChatApiKey
        get() = host.activeChatApiKey
        set(value) { host.activeChatApiKey = value }
    private var activeStreamPump
        get() = host.activeStreamPump
        set(value) { host.activeStreamPump = value }
    private var pendingRpSwipeAppend
        get() = host.pendingRpSwipeAppend
        set(value) { host.pendingRpSwipeAppend = value }
    private var discardableRpAssistantInFlight
        get() = host.discardableRpAssistantInFlight
        set(value) { host.discardableRpAssistantInFlight = value }
    private var toolCallsHandledForTurn
        get() = host.toolCallsHandledForTurn
        set(value) { host.toolCallsHandledForTurn = value }

    private fun isReasoningModel(modelIdentifier: String?) = host.isReasoningModel(modelIdentifier)
    private fun canRequestReasoning(modelIdentifier: String?) = host.canRequestReasoning(modelIdentifier)
    private fun isImageGenerationModel(modelIdentifier: String?) = host.isImageGenerationModel(modelIdentifier)
    private fun isRpMode() = host.isRpMode()
    private fun activeModelIsLan() = host.activeModelIsLan()
    private fun buildTools() = host.buildTools()
    private suspend fun handleToolCalls(toolCalls: List<ToolCall>, thinkingMessage: FlexibleMessage?) =
        host.handleToolCalls(toolCalls, thinkingMessage)
    private fun buildWebSearchPlugin() = host.buildWebSearchPlugin()
    private fun parseOpenRouterError(responseText: String) = host.parseOpenRouterError(responseText)
    private fun finalizeAssistantContent(text: String) = host.finalizeAssistantContent(text)
    private fun getModelDisplayName(apiIdentifier: String) = host.getModelDisplayName(apiIdentifier)
    private suspend fun downloadImages(imageUrls: List<String>) = host.downloadImages(imageUrls)
    private fun saveBinaryFileToDownloads(filename: String, bytes: ByteArray, mimeType: String) =
        host.saveBinaryFileToDownloads(filename, bytes, mimeType)
    private fun updateMessages(updateBlock: (MutableList<FlexibleMessage>) -> Unit) = host.updateMessages(updateBlock)
    private fun putAssistantMessage(list: MutableList<FlexibleMessage>, thinkingMessage: FlexibleMessage?, newMessage: FlexibleMessage) =
        host.putAssistantMessage(list, thinkingMessage, newMessage)
    private fun removeAssistantPlaceholder(thinkingMessage: FlexibleMessage?) = host.removeAssistantPlaceholder(thinkingMessage)
    private fun restoreRpSwipeAltIfMissingAssistant() = host.restoreRpSwipeAltIfMissingAssistant()
    private fun handleError(e: Throwable, thinkingMessage: FlexibleMessage?) = host.handleError(e, thinkingMessage)

    internal fun formatCitations(annotations: List<Annotation>?): String {
        if (annotations.isNullOrEmpty()) return ""

        val sb = StringBuilder("\n\n---\n**Citations:**\n\n")
        annotations.forEachIndexed { i, ann ->
            if (ann.type == "url_citation" && ann.url_citation != null) {
                val cit = ann.url_citation
                val number = "[${i + 1}]"
                val titlePart = if (cit.title.isNullOrBlank()) "" else " ${cit.title}"
                val urlPart = if (cit.url.isNullOrBlank()) "" else " ${cit.url}"
                sb.append("$number$titlePart$urlPart\n\n")
            }
        }
        return sb.toString()
    }

    /**
     * Consume OpenAI-compatible SSE (and NDJSON fallback) from a chat stream.
     * Invokes [onPayload] for each JSON event body. Skips comments/heartbeats.
     * Stops on `[DONE]` or when [shouldStop] becomes true.
     */
    private suspend fun forEachSseJsonPayload(
        channel: ByteReadChannel,
        shouldStop: (() -> Boolean)? = null,
        onPayload: suspend (String) -> Unit
    ) = SseJsonReader.forEachJsonPayload(channel, onPayload, shouldStop)

    internal fun parseStreamChunk(jsonString: String): StreamedChatResponse? {
        return try {
            json.decodeFromString<StreamedChatResponse>(jsonString)
        } catch (_: Exception) {
            null
        }
    }

    private fun configuredMaxTokens(): Int = try {
        sharedPreferencesHelper.getMaxTokens().toIntOrNull() ?: 12_000
    } catch (_: Exception) {
        12_000
    }

    /** Enabled sampling values. Disabled knobs stay unset so the provider default is used. */
    private fun ChatRequest.withSampling(): ChatRequest {
        val prefs = sharedPreferencesHelper
        fun number(enabled: Boolean, raw: String) = if (enabled) raw.toDoubleOrNull() else null
        return copy(
            temperature = number(prefs.getInferenceTempEnabled(), prefs.getInferenceTempValue()),
            topP = number(prefs.getInferenceTopPEnabled(), prefs.getInferenceTopPValue()),
            topK = if (prefs.getInferenceTopKEnabled()) prefs.getInferenceTopKValue() else null,
            minP = number(prefs.getInferenceMinPEnabled(), prefs.getInferenceMinPValue()),
            repetitionPenalty = number(
                prefs.getInferenceRepetitionPenaltyEnabled(),
                prefs.getInferenceRepetitionPenaltyValue(),
            ),
            presencePenalty = number(
                prefs.getInferencePresencePenaltyEnabled(),
                prefs.getInferencePresencePenaltyValue(),
            ),
        )
    }

    private fun toolsForTurn(): List<Tool>? =
        if (!isRpMode() && _isToolsEnabled.value == true) buildTools() else null

    private fun lanThinkingKwargs(reasoningModel: Boolean, provider: String): Map<String, JsonElement>? =
        if (provider == LAN_PROVIDER_LLAMA_CPP && reasoningModel) {
            mapOf("enable_thinking" to JsonPrimitive(_isReasoningEnabled.value == true))
        } else {
            null
        }

    private fun ollamaThink(reasoningModel: Boolean, provider: String): Boolean? =
        if (reasoningModel && provider == LAN_PROVIDER_OLLAMA) _isReasoningEnabled.value else null

    /** "none" asks Ollama's OpenAI endpoint to skip thinking. A null effort leaves it on. */
    private fun ollamaReasoningEffort(reasoningModel: Boolean, provider: String): String? =
        if (reasoningModel && provider == LAN_PROVIDER_OLLAMA) {
            if (_isReasoningEnabled.value == true) null else "none"
        } else {
            null
        }

    private fun openRouterTransforms(): List<String>? =
        if (sharedPreferencesHelper.getOpenRouterTransformsEnabled() && !activeModelIsLan()) {
            listOf("middle-out")
        } else {
            null
        }

    private fun webSearchOptions(): WebSearchOptions? =
        if (!isRpMode() && sharedPreferencesHelper.getWebSearchBoolean() && !activeModelIsLan()) {
            WebSearchOptions(searchContextSize = sharedPreferencesHelper.getWebSearchContextSize())
        } else {
            null
        }

    private fun cloudReasoning(): Reasoning? {
        val maxTokens = sharedPreferencesHelper.getReasoningMaxTokens()?.takeIf { it > 0 }
        val effort = if (maxTokens == null) sharedPreferencesHelper.getReasoningEffort() else null
        return if (_isReasoningEnabled.value == true && canRequestReasoning(_activeChatModel.value)) {
            if (sharedPreferencesHelper.getAdvancedReasoningEnabled()) {
                Reasoning(
                    enabled = true,
                    exclude = sharedPreferencesHelper.getReasoningExclude(),
                    effort = effort,
                    max_tokens = maxTokens,
                )
            } else {
                Reasoning(enabled = true, exclude = true)
            }
        } else if (_isReasoningEnabled.value == false && isReasoningModel(_activeChatModel.value)) {
            Reasoning(enabled = false, exclude = true)
        } else {
            null
        }
    }

    private fun outputModalities(model: String, includeAudio: Boolean): List<String>? {
        if (includeAudio && model.contains("google/lyria", ignoreCase = true)) return listOf("text", "audio")
        if (!isImageGenerationModel(model)) return null
        val imageOnly = model.contains("bytedance-seed", ignoreCase = true) ||
            model.contains("black-forest-labs", ignoreCase = true) ||
            model.contains("sourceful/riverflow", ignoreCase = true)
        return if (imageOnly) listOf("image") else listOf("image", "text")
    }

    private fun geminiImageConfig(model: String): ImageConfig? {
        if (!isImageGenerationModel(model) ||
            !model.contains("google", ignoreCase = true) ||
            !model.contains("gemini", ignoreCase = true) ||
            !model.contains("image", ignoreCase = true)
        ) {
            return null
        }
        return ImageConfig(aspectRatio = sharedPreferencesHelper.getGeminiAspectRatio() ?: "1:1")
    }

    internal suspend fun handleStreamedResponseLAN(
        modelForRequest: String,
        messagesForApiRequest: List<FlexibleMessage>,
        thinkingMessage: FlexibleMessage
    ) {
        withContext(Dispatchers.IO) {
            val tools = toolsForTurn()
            val reasoningModel = isReasoningModel(_activeChatModel.value)
            val lanProvider = sharedPreferencesHelper.getLanProvider()
            val chatRequest = ChatRequest(
                model = modelForRequest,
                messages = messagesForApiRequest,
                stream = true,
                max_tokens = configuredMaxTokens(),
                think = ollamaThink(reasoningModel, lanProvider),
                reasoningEffort = ollamaReasoningEffort(reasoningModel, lanProvider),
                chatTemplateKwargs = lanThinkingKwargs(reasoningModel, lanProvider),
                tools = tools,
                toolChoice = if (tools != null) "auto" else null,
            ).withSampling()

            sendStreamingTurn(
                client = lanHttpClient,
                chatRequest = chatRequest,
                thinkingMessage = thinkingMessage,
                openRouterHeaders = false,
                captureAudio = false,
            )
        }
    }

    internal suspend fun handleStreamedResponse(
        modelForRequest: String,
        messagesForApiRequest: List<FlexibleMessage>,
        thinkingMessage: FlexibleMessage
    ) {
        withContext(Dispatchers.IO) {
            val tools = toolsForTurn()
            val chatRequest = ChatRequest(
                model = modelForRequest,
                messages = messagesForApiRequest,
                transforms = openRouterTransforms(),
                stream = true,
                max_tokens = configuredMaxTokens(),
                tools = tools,
                plugins = buildWebSearchPlugin(),
                webSearchOptions = webSearchOptions(),
                toolChoice = if (tools != null) "auto" else null,
                modalities = outputModalities(modelForRequest, includeAudio = true),
                imageConfig = geminiImageConfig(modelForRequest),
                reasoning = cloudReasoning(),
            ).withSampling()

            sendStreamingTurn(
                client = if (DemoModel.isDemo(modelForRequest)) demoHttpClient else httpClient,
                chatRequest = chatRequest,
                thinkingMessage = thinkingMessage,
                openRouterHeaders = true,
                captureAudio = true,
            )
        }
    }

    internal suspend fun handleNonStreamedResponseLAN(
        modelForRequest: String,
        messagesForApiRequest: List<FlexibleMessage>,
        thinkingMessage: FlexibleMessage?
    ) {
        withTimeout((sharedPreferencesHelper.getTimeoutMinutes().toLong() * 60_000L).milliseconds) {
            withContext(Dispatchers.IO) {
                val tools = toolsForTurn()
                val reasoningModel = isReasoningModel(_activeChatModel.value)
                val lanProvider = sharedPreferencesHelper.getLanProvider()
                val chatRequest = ChatRequest(
                    model = modelForRequest,
                    messages = messagesForApiRequest,
                    think = ollamaThink(reasoningModel, lanProvider),
                    reasoningEffort = ollamaReasoningEffort(reasoningModel, lanProvider),
                    chatTemplateKwargs = lanThinkingKwargs(reasoningModel, lanProvider),
                    max_tokens = configuredMaxTokens(),
                    tools = tools,
                    toolChoice = if (tools != null) "auto" else null,
                ).withSampling()

                val response = lanHttpClient.post(activeChatUrl) {
                    header("Authorization", "Bearer $activeChatApiKey")
                    contentType(ContentType.Application.Json)
                    setBody(chatRequest)
                }

                deliverChatResponse(readChatResponse(response), thinkingMessage, stripThink = true)
            }
        }
    }

    internal suspend fun handleNonStreamedResponse(modelForRequest: String, messagesForApiRequest: List<FlexibleMessage>, thinkingMessage: FlexibleMessage?) {
        withTimeout((sharedPreferencesHelper.getTimeoutMinutes().toLong() * 60_000L).milliseconds) {
            withContext(Dispatchers.IO) {
                val tools = toolsForTurn()
                val chatRequest = ChatRequest(
                    model = modelForRequest,
                    messages = messagesForApiRequest,
                    transforms = openRouterTransforms(),
                    max_tokens = configuredMaxTokens(),
                    reasoning = cloudReasoning(),
                    tools = tools,
                    toolChoice = if (tools != null) "auto" else null,
                    plugins = buildWebSearchPlugin(),
                    webSearchOptions = webSearchOptions(),
                    modalities = outputModalities(modelForRequest, includeAudio = false),
                    imageConfig = geminiImageConfig(modelForRequest),
                ).withSampling()

                val response = httpClient.post(activeChatUrl) {
                    header("Authorization", "Bearer $activeChatApiKey")
                    header("HTTP-Referer", "https://github.com/Warexpor/oxproxion")
                    header("X-Title", "GradatiON")
                    contentType(ContentType.Application.Json)
                    setBody(chatRequest)
                }

                deliverChatResponse(readChatResponse(response), thinkingMessage, stripThink = false)
            }
        }
    }

    private suspend fun readChatResponse(response: HttpResponse): ChatResponse {
        if (response.status.isSuccess()) return response.body()
        val errorBody = try {
            response.bodyAsText()
        } catch (_: Exception) {
            "No details"
        }
        val message = parseOpenRouterError(errorBody)
        if (sharedPreferencesHelper.getNotiPreference()) {
            sharedPreferencesHelper.saveLastAiResponseForChannel(2, message)
        }
        throw Exception(message)
    }

    /**
     * Lands a finished non-streaming completion. Local models sometimes wrap the
     * visible reply in a think tag; only that path strips it.
     */
    private suspend fun deliverChatResponse(
        chatResponse: ChatResponse,
        thinkingMessage: FlexibleMessage?,
        stripThink: Boolean,
    ) {
        withContext(Dispatchers.Main) {
            val choice = chatResponse.choices.firstOrNull()
            choice?.error?.let { error ->
                handleErrorResponse(error, thinkingMessage)
                return@withContext
            }
            when (choice?.finish_reason) {
                "error" -> {
                    val errorMsg = "**Error:** The model encountered an error while generating the response. Please try again."
                    handleError(Exception(errorMsg), thinkingMessage)
                    return@withContext
                }
                "content_filter" -> {
                    val errorMsg = application.getString(R.string.error_provider_content_filter)
                    handleError(Exception(errorMsg), thinkingMessage)
                    return@withContext
                }
                "length" -> {
                    AppToast.makeText(
                        application.applicationContext,
                        application.getString(R.string.toast_response_truncated_max_tokens),
                        AppToast.LENGTH_LONG
                    ).show()
                }
                else -> Unit
            }
            if (choice?.message?.toolCalls?.isNotEmpty() == true && !toolCallsHandledForTurn && !isRpMode() && _isToolsEnabled.value == true) {
                val toolCalls = choice.message.toolCalls
                val citationsMarkdown = if (sharedPreferencesHelper.getShowCitations()) {
                    formatCitations(choice.message.annotations)
                } else {
                    ""
                }
                val rawContent = choice.message.content ?: ""
                val text = if (stripThink && rawContent.trimStart().startsWith("</think>")) {
                    rawContent.substringAfter("</think>").trimStart()
                } else {
                    rawContent
                }
                val assistantMessage = FlexibleMessage(
                    role = "assistant",
                    content = JsonPrimitive(text + citationsMarkdown),
                    toolCalls = toolCalls
                )
                updateMessages { list ->
                    if (thinkingMessage == null) list.add(assistantMessage)
                    else putAssistantMessage(list, thinkingMessage, assistantMessage)
                }
                handleToolCalls(toolCalls, thinkingMessage)
            } else {
                val downloadedUris = choice?.message?.images?.let { images ->
                    downloadImages(images.map { it.image_url.url })
                } ?: emptyList()
                handleSuccessResponse(chatResponse, thinkingMessage, downloadedUris)
            }
        }
    }

    private fun handleSuccessResponse(
        chatResponse: ChatResponse,
        thinkingMessage: FlexibleMessage?,
        downloadedUris: List<String> = emptyList()
    ) {
        val message = chatResponse.choices.firstOrNull()?.message
        if (message == null) {
            handleError(
                IllegalStateException("The model returned an empty response. Try again."),
                thinkingMessage
            )
            return
        }
        val responseText = message.content ?: "No response received."

        val reasoningForDisplay = message.reasoning_details
            ?.firstOrNull { it.type == "reasoning.text" }
            ?.let { "```\n${it.text}\n```" }
            ?: message.thinking?.let { "```\n$it\n```" }
            ?: message.reasoning?.let { "```\n$it\n```" }
            ?: ""

        val separator = if (reasoningForDisplay.isNotBlank()) "\n\n---\n\n" else ""
        val citationsMarkdown = if (sharedPreferencesHelper.getShowCitations()) {
            formatCitations(message.annotations)
        } else {
            ""
        }

        val finalContent = finalizeAssistantContent(responseText + citationsMarkdown)

        var finalAiMessage = FlexibleMessage(
            role = "assistant",
            content = JsonPrimitive(finalContent),
            toolsUsed = thinkingMessage == null,
            reasoning = reasoningForDisplay + separator
        )
        if (downloadedUris.isNotEmpty()) {
            finalAiMessage = finalAiMessage.copy(imageUri = downloadedUris.first())
        }
        updateMessages { list ->
            if (thinkingMessage == null) list.add(finalAiMessage)
            else putAssistantMessage(list, thinkingMessage, finalAiMessage)

            if (sharedPreferencesHelper.getNotiPreference()) {
                val apiIdentifier = activeChatModel.value ?: "Unknown Model"
                val displayName = getModelDisplayName(apiIdentifier)
                val truncatedResponse = if (finalContent.length > 3900) {
                    finalContent.take(3900) + "..."
                } else {
                    finalContent
                }
                sharedPreferencesHelper.saveLastAiResponseForChannel(2, truncatedResponse)
                ForegroundService.updateNotificationStatus(application, displayName, "Your answer is ready.")
            }
        }
    }

    private fun handleErrorResponse(error: ErrorResponse, thinkingMessage: FlexibleMessage?) {
        val wasRpRegen = pendingRpSwipeAppend
        pendingRpSwipeAppend = false
        // Terminal error is no longer an in-flight stream — Stop must not discard the Error bubble.
        discardableRpAssistantInFlight = false
        if (wasRpRegen) {
            // Same as handleError: restore stashed alt instead of leaving an Error bubble as the reply.
            removeAssistantPlaceholder(thinkingMessage)
            restoreRpSwipeAltIfMissingAssistant()
            val shortMsg = error.message.takeIf { it.isNotBlank() }
                ?: application.getString(R.string.rp_regen_failed)
            _toastUiEvent.postValue(Event(shortMsg))
            return
        }
        val detailedMsg = "**Error:**\n---\n(Code: ${error.code}): ${error.message}"
        val errorMessage = FlexibleMessage(role = "assistant", content = JsonPrimitive(detailedMsg))
        updateMessages { list ->
            putAssistantMessage(list, thinkingMessage, errorMessage)
        }
        if (sharedPreferencesHelper.getNotiPreference()) {
            val apiIdentifier = activeChatModel.value ?: "Unknown Model"
            val displayName = getModelDisplayName(apiIdentifier)
            sharedPreferencesHelper.saveLastAiResponseForChannel(2, detailedMsg)//#ttsnoti
            // answer-only: skip error system notifications
        }
    }

    /**
     * One SSE chat completion. Both the LAN and cloud readers build their own request,
     * then land the assistant message here. A fatal finish reason returns before images
     * are downloaded.
     */
    private suspend fun sendStreamingTurn(
        client: HttpClient,
        chatRequest: ChatRequest,
        thinkingMessage: FlexibleMessage,
        openRouterHeaders: Boolean,
        captureAudio: Boolean,
    ) {
        try {
            client.preparePost(activeChatUrl) {
                header("Authorization", "Bearer $activeChatApiKey")
                if (openRouterHeaders) {
                    header("HTTP-Referer", "https://github.com/Warexpor/oxproxion")
                    header("X-Title", "GradatiON")
                }
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(chatRequest)
            }.execute { httpResponse ->
                if (!httpResponse.status.isSuccess()) {
                    val errorBody = try {
                        httpResponse.bodyAsText()
                    } catch (_: Exception) {
                        "No details"
                    }
                    throw Exception(parseOpenRouterError(errorBody))
                }

                val channel = httpResponse.body<ByteReadChannel>()
                val fold = StreamFold()
                var finishReason: String? = null
                val toolCallBuffer = mutableListOf<ToolCall>()
                val accumulatedAnnotations = mutableListOf<Annotation>()
                val accumulatedImages = mutableListOf<String>()
                val audioBuffer = StringBuilder()
                var streamAborted = false

                val pump = StreamUiPump(viewModelScope) { partial ->
                    updateMessages { list -> putAssistantMessage(list, thinkingMessage, partial) }
                }
                activeStreamPump = pump
                pump.drive {
                    forEachSseJsonPayload(channel, shouldStop = { streamAborted }) { jsonString ->
                        if (streamAborted) return@forEachSseJsonPayload
                        val chunk = parseStreamChunk(jsonString) ?: return@forEachSseJsonPayload

                        chunk.error?.let { apiError ->
                            val rawDetails =
                                "Code: ${apiError.code ?: "unknown"} - ${apiError.message ?: "Mid-stream error"}"
                            withContext(Dispatchers.Main) {
                                pump.cancel()
                                handleError(Exception(rawDetails), thinkingMessage)
                            }
                            streamAborted = true
                            return@forEachSseJsonPayload
                        }

                        val choice = chunk.choices.firstOrNull()
                        finishReason = choice?.finish_reason ?: finishReason
                        choice?.error?.let { apiError ->
                            withContext(Dispatchers.Main) {
                                pump.cancel()
                                handleErrorResponse(apiError, thinkingMessage)
                            }
                            streamAborted = true
                            return@forEachSseJsonPayload
                        }
                        val delta = choice?.delta ?: return@forEachSseJsonPayload
                        if (captureAudio) delta.audio?.data?.let { audioBuffer.append(it) }
                        if (fold.absorb(delta)) fold.publish(pump)
                        absorbToolDelta(toolCallBuffer, delta)
                        accumulatedAnnotations.addAll(delta.annotations ?: emptyList())
                        delta.images?.forEach { accumulatedImages.add(it.image_url.url) }
                    }
                    fold.publish(pump, force = true)
                }

                if (streamAborted) return@execute
                when (finishReason) {
                    "error" -> {
                        val errorMsg = "**Error:** The model encountered an error while generating the response. Please try again."
                        withContext(Dispatchers.Main) {
                            handleError(Exception(errorMsg), thinkingMessage)
                        }
                        return@execute
                    }
                    "content_filter" -> {
                        val errorMsg = application.getString(R.string.error_provider_content_filter)
                        withContext(Dispatchers.Main) {
                            handleError(Exception(errorMsg), thinkingMessage)
                        }
                        return@execute
                    }
                    "length" -> {
                        withContext(Dispatchers.Main) {
                            AppToast.makeText(
                                application.applicationContext,
                                application.getString(R.string.toast_response_truncated_max_tokens),
                                AppToast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    else -> Unit
                }

                val downloadedUris = if (accumulatedImages.isNotEmpty()) {
                    downloadImages(accumulatedImages)
                } else {
                    emptyList()
                }
                if (audioBuffer.isNotEmpty()) {
                    try {
                        val audioBytes = Base64.getDecoder().decode(audioBuffer.toString())
                        val filename = "lyria_${System.currentTimeMillis()}.mp3"
                        saveBinaryFileToDownloads(filename, audioBytes, "audio/mpeg")
                        _toolUiEvent.postValue(Event("Music saved: $filename"))
                    } catch (e: Exception) {
                        _toolUiEvent.postValue(Event("Audio save failed: ${e.message}"))
                    }
                }

                val accumulatedResponse = fold.content()
                val accumulatedReasoning = fold.reasoning()
                val citationsMarkdown = if (sharedPreferencesHelper.getShowCitations()) {
                    formatCitations(accumulatedAnnotations)
                } else {
                    ""
                }
                val hadToolCalls = toolCallBuffer.isNotEmpty()
                var streamFinalContent: String? = null
                if (hadToolCalls && !toolCallsHandledForTurn) {
                    val assistantMessage = FlexibleMessage(
                        role = "assistant",
                        content = JsonPrimitive(accumulatedResponse + citationsMarkdown),
                        toolCalls = toolCallBuffer,
                        imageUri = downloadedUris.firstOrNull()
                    )
                    withContext(Dispatchers.Main) {
                        updateMessages { list ->
                            putAssistantMessage(list, thinkingMessage, assistantMessage)
                        }
                    }
                    handleToolCalls(toolCallBuffer, thinkingMessage)
                } else {
                    withContext(Dispatchers.Main) {
                        val rawContent = (accumulatedResponse + citationsMarkdown)
                            .takeIf { it.isNotBlank() } ?: "No response received."
                        val finalContent = finalizeAssistantContent(rawContent)
                        streamFinalContent = finalContent
                        updateMessages { list ->
                            putAssistantMessage(
                                list,
                                thinkingMessage,
                                FlexibleMessage(
                                    role = "assistant",
                                    content = JsonPrimitive(finalContent),
                                    reasoning = accumulatedReasoning.ifBlank { null },
                                    imageUri = downloadedUris.firstOrNull()
                                )
                            )
                        }
                    }
                }

                if (sharedPreferencesHelper.getNotiPreference()) {
                    val apiIdentifier = activeChatModel.value ?: "Unknown Model"
                    val displayName = getModelDisplayName(apiIdentifier)
                    val notiBody = streamFinalContent
                        ?: accumulatedResponse.ifBlank { "No response received." }
                    val truncatedResponse = if (notiBody.length > 3900) {
                        notiBody.take(3900) + "..."
                    } else {
                        notiBody
                    }
                    sharedPreferencesHelper.saveLastAiResponseForChannel(2, truncatedResponse)
                    ForegroundService.updateNotificationStatus(application, displayName, "Your answer is ready.")
                }
            }
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) {
                handleError(e, thinkingMessage)
                if (sharedPreferencesHelper.getNotiPreference()) {
                    val apiIdentifier = activeChatModel.value ?: "Unknown Model"
                    val displayName = getModelDisplayName(apiIdentifier)
                    sharedPreferencesHelper.saveLastAiResponseForChannel(2, "Error!")
                }
            }
        }
    }

    private fun absorbToolDelta(buffer: MutableList<ToolCall>, delta: StreamedDelta) {
        delta.toolCalls?.forEach { deltaTc ->
            val index = deltaTc.index
            if (index >= buffer.size) {
                buffer.add(
                    ToolCall(
                        id = deltaTc.id ?: "",
                        type = deltaTc.type ?: "function",
                        function = FunctionCall(
                            name = deltaTc.function?.name ?: "",
                            arguments = deltaTc.function?.arguments ?: ""
                        )
                    )
                )
            } else {
                val existing = buffer[index]
                buffer[index] = existing.copy(
                    function = existing.function.copy(
                        name = existing.function.name + (deltaTc.function?.name ?: ""),
                        arguments = existing.function.arguments + (deltaTc.function?.arguments ?: "")
                    )
                )
            }
        }
    }
}

/**
 * One turn's text and reasoning, folded the same way both stream readers used to append
 * strings. Snapshots go out through [StreamAccum] so fast local models do not copy the
 * whole reply on every token.
 */
private class StreamFold {
    private val text = StreamAccum()
    private var hasUsedReasoningDetails = false
    private var reasoningStarted = false

    fun content(): String = text.content()
    fun reasoning(): String = text.reasoning()

    /** True when this delta added reply or reasoning text. */
    fun absorb(delta: StreamedDelta): Boolean {
        var changed = false
        if (!delta.content.isNullOrEmpty()) {
            text.appendContent(delta.content)
            changed = true
        }
        if (delta.reasoning_details?.isNotEmpty() == true) {
            hasUsedReasoningDetails = true
            delta.reasoning_details.forEach { detail ->
                if (detail.type == "reasoning.text" && detail.text != null) {
                    if (!reasoningStarted) {
                        text.clearReasoning()
                        reasoningStarted = true
                    }
                    text.appendReasoning(detail.text)
                    changed = true
                }
            }
        } else if (!hasUsedReasoningDetails && !delta.reasoning.isNullOrEmpty()) {
            if (!reasoningStarted) {
                text.clearReasoning()
                reasoningStarted = true
            }
            text.appendReasoning(delta.reasoning)
            changed = true
        }
        return changed
    }

    fun publish(pump: StreamUiPump, force: Boolean = false) {
        text.partial(System.nanoTime(), force)?.let { pump.offer(it) }
    }
}
