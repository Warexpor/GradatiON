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

private const val OPENROUTER_REFERER = "https://github.com/Warexpor/GradatiON"

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
    suspend fun downloadImages(imageUrls: List<String>): List<ScenePhoto.GeneratedPicture>
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
    ): SseJsonReader.End = SseJsonReader.forEachJsonPayload(channel, onPayload, shouldStop)

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
        fun number(enabled: Boolean, kind: InferenceKind, raw: String) =
            if (enabled) acceptedInferenceDecimal(kind, raw)?.let(::inferenceDecimalOrNull) else null
        return copy(
            temperature = number(prefs.getInferenceTempEnabled(), InferenceKind.TEMPERATURE, prefs.getInferenceTempValue()),
            topP = number(prefs.getInferenceTopPEnabled(), InferenceKind.TOP_P, prefs.getInferenceTopPValue()),
            topK = if (prefs.getInferenceTopKEnabled()) acceptedTopK(prefs.getInferenceTopKValue().toString()) else null,
            minP = number(prefs.getInferenceMinPEnabled(), InferenceKind.MIN_P, prefs.getInferenceMinPValue()),
            repetitionPenalty = number(
                prefs.getInferenceRepetitionPenaltyEnabled(),
                InferenceKind.REPETITION,
                prefs.getInferenceRepetitionPenaltyValue(),
            ),
            presencePenalty = number(
                prefs.getInferencePresencePenaltyEnabled(),
                InferenceKind.PRESENCE,
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
                messages = messagesForApiRequest.toApiMessages(),
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
                messages = messagesForApiRequest.toApiMessages(),
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
        // The timeout covers the request and its body only: delivering the reply runs tools and
        // the follow-up turns, which bring their own timeouts and must not share this one.
        val chatResponse = withTimeout(requestTimeout()) {
            withContext(Dispatchers.IO) {
                val tools = toolsForTurn()
                val reasoningModel = isReasoningModel(_activeChatModel.value)
                val lanProvider = sharedPreferencesHelper.getLanProvider()
                val chatRequest = ChatRequest(
                    model = modelForRequest,
                    messages = messagesForApiRequest.toApiMessages(),
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

                readChatResponse(response)
            }
        }
        deliverChatResponse(chatResponse, thinkingMessage, stripThink = true)
    }

    internal suspend fun handleNonStreamedResponse(modelForRequest: String, messagesForApiRequest: List<FlexibleMessage>, thinkingMessage: FlexibleMessage?) {
        val chatResponse = withTimeout(requestTimeout()) {
            withContext(Dispatchers.IO) {
                val tools = toolsForTurn()
                val chatRequest = ChatRequest(
                    model = modelForRequest,
                    messages = messagesForApiRequest.toApiMessages(),
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
                    header("HTTP-Referer", OPENROUTER_REFERER)
                    header("X-Title", "GradatiON")
                    contentType(ContentType.Application.Json)
                    setBody(chatRequest)
                }

                readChatResponse(response)
            }
        }
        deliverChatResponse(chatResponse, thinkingMessage, stripThink = false)
    }

    /** Read on every call, so a timeout changed in Settings applies to the next request. */
    private fun requestTimeout() =
        (sharedPreferencesHelper.getTimeoutMinutes().toLong() * 60_000L).milliseconds

    private suspend fun readChatResponse(response: HttpResponse): ChatResponse {
        if (response.status.isSuccess()) return response.body()
        val errorBody = try {
            response.bodyAsText()
        } catch (_: Exception) {
            "No details"
        }
        val message = parseOpenRouterError(errorBody)
        // No shade for an error. Leave the speak line the previous shade still shows.
        AnswerShadeText.lineForShade(message, handedToTools = false, isError = true)?.let {
            sharedPreferencesHelper.saveLastAiResponseForChannel(2, it)
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
        // Tools run outside the Main block: they do file and network work, and only the
        // transcript update needs the main thread.
        val toolCalls = withContext(Dispatchers.Main) {
            val choice = chatResponse.choices.firstOrNull()
            choice?.error?.let { error ->
                handleErrorResponse(error, thinkingMessage)
                return@withContext null
            }
            when (choice?.finish_reason) {
                "error" -> {
                    val errorMsg = application.getString(R.string.error_model_generation_failed)
                    handleError(Exception(errorMsg), thinkingMessage)
                    return@withContext null
                }
                "content_filter" -> {
                    val errorMsg = application.getString(R.string.error_response_filtered)
                    handleError(Exception(errorMsg), thinkingMessage)
                    return@withContext null
                }
                "length" -> _toastUiEvent.postValue(Event(application.getString(R.string.toast_response_truncated_max_tokens)))
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
                toolCalls
            } else {
                val downloadedUris = choice?.message?.images?.let { images ->
                    downloadImages(images.map { it.image_url.url })
                } ?: emptyList()
                handleSuccessResponse(chatResponse, thinkingMessage, downloadedUris)
                null
            }
        }
        if (toolCalls != null) handleToolCalls(toolCalls, thinkingMessage)
    }

    private fun handleSuccessResponse(
        chatResponse: ChatResponse,
        thinkingMessage: FlexibleMessage?,
        downloadedUris: List<ScenePhoto.GeneratedPicture> = emptyList()
    ) {
        val message = chatResponse.choices.firstOrNull()?.message
        if (message == null) {
            handleError(
                IllegalStateException("The model returned an empty response. Try again."),
                thinkingMessage
            )
            return
        }
        val responseText = message.content ?: application.getString(R.string.error_no_response)

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
            finalAiMessage = ScenePhoto.withGeneratedPicture(finalAiMessage, downloadedUris.first())
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
                val line = AnswerShadeText.lineForShade(
                    truncatedResponse,
                    handedToTools = false,
                    isError = false,
                )
                if (line != null) {
                    sharedPreferencesHelper.saveLastAiResponseForChannel(2, line)
                    ForegroundService.updateNotificationStatus(application, displayName, application.getString(R.string.notification_answer_ready))
                }
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
        // answer-only: skip error system notifications, and do not replace the speak line
        AnswerShadeText.lineForShade(detailedMsg, handedToTools = false, isError = true)?.let {
            sharedPreferencesHelper.saveLastAiResponseForChannel(2, it)
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
                    header("HTTP-Referer", OPENROUTER_REFERER)
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
                var audioOverflow = false
                var streamAborted = false
                var overCap = false
                var streamEnd = SseJsonReader.End.CLOSED

                val pump = StreamUiPump(viewModelScope) { partial ->
                    updateMessages { list -> putAssistantMessage(list, thinkingMessage, partial) }
                }
                activeStreamPump = pump
                try {
                pump.drive {
                    streamEnd = forEachSseJsonPayload(channel, shouldStop = { streamAborted || overCap }) { jsonString ->
                        if (streamAborted || overCap) return@forEachSseJsonPayload
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
                        if (captureAudio) {
                            delta.audio?.data?.let { chunk ->
                                if (!appendAudioChunk(audioBuffer, chunk)) audioOverflow = true
                            }
                        }
                        if (fold.absorb(delta)) {
                            fold.publish(pump)
                            if (fold.capped()) overCap = true
                        }
                        absorbToolDelta(toolCallBuffer, delta)
                        accumulatedAnnotations.addAll(delta.annotations ?: emptyList())
                        delta.images?.forEach { accumulatedImages.add(it.image_url.url) }
                    }
                    fold.publish(pump, force = true)
                }
                } finally {
                    activeStreamPump = StreamTurn.retainPump(activeStreamPump, pump)
                }

                if (streamAborted) return@execute
                if (overCap) {
                    _toastUiEvent.postValue(Event(application.getString(R.string.toast_response_truncated_max_tokens)))
                }
                // Hung up with neither [DONE] nor a finish reason: what arrived may be only part of a reply.
                // Keep the text (some LAN servers just close the socket) and say so; error out only when
                // nothing came at all.
                if (streamEnd == SseJsonReader.End.CLOSED && finishReason == null) {
                    if (fold.content().isBlank() && toolCallBuffer.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            handleError(Exception(application.getString(R.string.error_reply_cut_off)), thinkingMessage)
                        }
                        return@execute
                    }
                    _toastUiEvent.postValue(Event(application.getString(R.string.notice_reply_may_be_cut_off)))
                }
                fillMissingToolCallIds(toolCallBuffer)
                when (finishReason) {
                    "error" -> {
                        val errorMsg = application.getString(R.string.error_model_generation_failed)
                        withContext(Dispatchers.Main) {
                            handleError(Exception(errorMsg), thinkingMessage)
                        }
                        return@execute
                    }
                    "content_filter" -> {
                        val errorMsg = application.getString(R.string.error_response_filtered)
                        withContext(Dispatchers.Main) {
                            handleError(Exception(errorMsg), thinkingMessage)
                        }
                        return@execute
                    }
                    "length" -> _toastUiEvent.postValue(Event(application.getString(R.string.toast_response_truncated_max_tokens)))
                    else -> Unit
                }

                val downloadedUris = if (accumulatedImages.isNotEmpty()) {
                    downloadImages(accumulatedImages)
                } else {
                    emptyList()
                }
                if (audioOverflow) {
                    _toolUiEvent.postValue(Event(application.getString(R.string.save_audio_too_large)))
                } else if (audioBuffer.isNotEmpty()) {
                    try {
                        val audioBytes = Base64.getDecoder().decode(audioBuffer.toString())
                        val filename = "lyria_${System.currentTimeMillis()}.mp3"
                        saveBinaryFileToDownloads(filename, audioBytes, "audio/mpeg")
                        _toolUiEvent.postValue(Event(application.getString(R.string.save_audio_ok, filename)))
                    } catch (e: Exception) {
                        _toolUiEvent.postValue(Event(application.getString(R.string.save_audio_failed, e.message)))
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
                val handedToTools = hadToolCalls && !toolCallsHandledForTurn
                var streamFinalContent: String? = null
                if (handedToTools) {
                    val assistantMessage = ScenePhoto.withGeneratedPicture(
                        FlexibleMessage(
                            role = "assistant",
                            content = JsonPrimitive(accumulatedResponse + citationsMarkdown),
                            toolCalls = toolCallBuffer,
                        ),
                        downloadedUris.firstOrNull(),
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
                            .takeIf { it.isNotBlank() } ?: application.getString(R.string.error_no_response)
                        val finalContent = finalizeAssistantContent(rawContent)
                        streamFinalContent = finalContent
                        updateMessages { list ->
                            putAssistantMessage(
                                list,
                                thinkingMessage,
                                ScenePhoto.withGeneratedPicture(
                                    FlexibleMessage(
                                        role = "assistant",
                                        content = JsonPrimitive(finalContent),
                                        reasoning = accumulatedReasoning.ifBlank { null },
                                    ),
                                    downloadedUris.firstOrNull(),
                                )
                            )
                        }
                    }
                }

                if (sharedPreferencesHelper.getNotiPreference()) {
                    val apiIdentifier = activeChatModel.value ?: "Unknown Model"
                    val displayName = getModelDisplayName(apiIdentifier)
                    val notiBody = streamFinalContent
                        ?: accumulatedResponse.ifBlank { application.getString(R.string.error_no_response) }
                    val truncatedResponse = if (notiBody.length > 3900) {
                        notiBody.take(3900) + "..."
                    } else {
                        notiBody
                    }
                    // Tool handoff: the follow-up already saved the finished answer.
                    val line = AnswerShadeText.lineForShade(
                        truncatedResponse,
                        handedToTools = handedToTools,
                        isError = false,
                    )
                    if (line != null) {
                        sharedPreferencesHelper.saveLastAiResponseForChannel(2, line)
                        ForegroundService.updateNotificationStatus(application, displayName, application.getString(R.string.notification_answer_ready))
                    }
                }
            }
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) {
                handleError(e, thinkingMessage)
                // A failed stream does not post a shade. "Error!" must not become what Speak reads.
                AnswerShadeText.lineForShade("Error!", handedToTools = false, isError = true)?.let {
                    sharedPreferencesHelper.saveLastAiResponseForChannel(2, it)
                }
            }
        }
    }

    private fun absorbToolDelta(buffer: MutableList<ToolCall>, delta: StreamedDelta) =
        absorbToolCallChunks(buffer, delta.toolCalls)
}

/**
 * Folds streamed tool-call fragments into whole calls. Servers differ: most send an index and
 * split the arguments, some repeat the name on every fragment, some omit the index and send each
 * call whole, and some reuse index 0 for every call. A new id (or, without an index, a second
 * name) starts a new call; the id is taken from whichever fragment carries it first.
 */
internal const val MAX_TOOL_ARGUMENT_CHARS = 2 * 1024 * 1024
internal const val MAX_TOOL_CALLS = 64

/** Tests shrink these. Null in production. */
@androidx.annotation.VisibleForTesting
internal var maxToolArgumentCharsForTest: Int? = null

@androidx.annotation.VisibleForTesting
internal var maxToolCallsForTest: Int? = null

internal fun absorbToolCallChunks(buffer: MutableList<ToolCall>, chunks: List<ToolCallChunk>?) {
    val maxArgs = maxToolArgumentCharsForTest ?: MAX_TOOL_ARGUMENT_CHARS
    val maxCalls = maxToolCallsForTest ?: MAX_TOOL_CALLS
    chunks?.forEach { chunk ->
        val fragmentName = chunk.function?.name.orEmpty()
        val fragmentArgs = chunk.function?.arguments.orEmpty()
        val fragmentId = chunk.id?.takeIf { it.isNotBlank() }
        val index: Int? = chunk.index
        var slot: Int = when {
            index == null -> buffer.lastIndex
            index in buffer.indices -> index
            else -> -1
        }
        if (slot >= 0) {
            val existing = buffer[slot]
            val idChanged = fragmentId != null && existing.id.isNotBlank() && fragmentId != existing.id
            val wholeCallWithoutIndex =
                index == null && fragmentName.isNotEmpty() && existing.function.name.isNotEmpty()
            if (idChanged || wholeCallWithoutIndex) slot = -1
        }
        if (slot < 0) {
            if (buffer.size >= maxCalls) return@forEach
            buffer.add(
                ToolCall(
                    id = fragmentId.orEmpty(),
                    type = chunk.type ?: "function",
                    function = FunctionCall(name = fragmentName, arguments = fragmentArgs.take(maxArgs)),
                )
            )
        } else {
            val existing = buffer[slot]
            // A name repeated on every fragment is not a longer name.
            val name = if (fragmentName.isEmpty() || fragmentName == existing.function.name) {
                existing.function.name
            } else {
                existing.function.name + fragmentName
            }
            val room = maxArgs - existing.function.arguments.length
            val extra = if (room <= 0) "" else fragmentArgs.take(room)
            buffer[slot] = existing.copy(
                id = existing.id.ifBlank { fragmentId.orEmpty() },
                function = existing.function.copy(
                    name = name,
                    arguments = existing.function.arguments + extra,
                ),
            )
        }
    }
}

/** Appends [chunk] until [max] characters. False once the buffer is full, so a long clip is not decoded. */
internal fun appendAudioChunk(buffer: StringBuilder, chunk: String, max: Int = MAX_AUDIO_B64_CHARS): Boolean {
    if (chunk.isEmpty()) return buffer.length < max
    if (buffer.length >= max) return false
    val room = max - buffer.length
    if (chunk.length > room) {
        buffer.append(chunk, 0, room)
        return false
    }
    buffer.append(chunk)
    return true
}

private const val MAX_AUDIO_B64_CHARS = 12 * 1024 * 1024

/** A provider that never sent an id still needs one, because each tool reply must cite its call. */
internal fun fillMissingToolCallIds(buffer: MutableList<ToolCall>) {
    buffer.forEachIndexed { i, call ->
        if (call.id.isBlank()) buffer[i] = call.copy(id = "call_${i}_${System.nanoTime()}")
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
    fun capped(): Boolean = text.capped

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
