package io.github.stardomains3.oxproxion

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import com.google.openlocationcode.OpenLocationCode
import io.github.stardomains3.oxproxion.BuildConfig
import io.github.stardomains3.oxproxion.SharedPreferencesHelper.Companion.LAN_PROVIDER_LLAMA_CPP
import io.github.stardomains3.oxproxion.SharedPreferencesHelper.Companion.LAN_PROVIDER_OLLAMA
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.CompressionInterceptor
import okhttp3.Gzip
import okhttp3.brotli.BrotliInterceptor
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.text.TextContentRenderer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
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

    internal suspend fun handleStreamedResponseLAN(
        modelForRequest: String,
        messagesForApiRequest: List<FlexibleMessage>,
        thinkingMessage: FlexibleMessage
    ) {
        withContext(Dispatchers.IO) {
            val sharedPreferencesHelper =
                SharedPreferencesHelper(application.applicationContext)

            val maxTokens = try {
                sharedPreferencesHelper.getMaxTokens().toIntOrNull() ?: 12000
            } catch (e: Exception) {
                12000
            }
            val isReasoningModel = isReasoningModel(_activeChatModel.value)
            val lanProvider = sharedPreferencesHelper.getLanProvider()
            val llamaCppKwargs = if (
                lanProvider == LAN_PROVIDER_LLAMA_CPP &&
                isReasoningModel
            ) {
                mapOf("enable_thinking" to JsonPrimitive(_isReasoningEnabled.value == true))
            } else null

            val chatRequest = ChatRequest(
                model = modelForRequest,
                messages = messagesForApiRequest,
                stream = true,
                max_tokens = maxTokens,
                think = if (isReasoningModel && lanProvider == LAN_PROVIDER_OLLAMA) {
                    _isReasoningEnabled.value
                } else null,
                // ADD THIS: For Ollama OpenAI-compatible endpoint
                reasoningEffort = if (isReasoningModel && lanProvider == LAN_PROVIDER_OLLAMA) {
                    if (_isReasoningEnabled.value == true) null else "none"
                } else null,
                // NEW: Add the llama.cpp specific logic
                chatTemplateKwargs = llamaCppKwargs,
                tools = if (!isRpMode() && _isToolsEnabled.value == true) buildTools() else null,
                toolChoice = if (!isRpMode() && _isToolsEnabled.value == true) "auto" else null,
                // === INFERENCE PARAMETERS ===
                temperature = if (sharedPreferencesHelper.getInferenceTempEnabled()) sharedPreferencesHelper.getInferenceTempValue().toDoubleOrNull() else null,
                topP = if (sharedPreferencesHelper.getInferenceTopPEnabled()) sharedPreferencesHelper.getInferenceTopPValue().toDoubleOrNull() else null,
                topK = if (sharedPreferencesHelper.getInferenceTopKEnabled()) sharedPreferencesHelper.getInferenceTopKValue() else null,
                minP = if (sharedPreferencesHelper.getInferenceMinPEnabled()) sharedPreferencesHelper.getInferenceMinPValue().toDoubleOrNull() else null,
                repetitionPenalty = if (sharedPreferencesHelper.getInferenceRepetitionPenaltyEnabled()) sharedPreferencesHelper.getInferenceRepetitionPenaltyValue().toDoubleOrNull() else null,
                presencePenalty = if (sharedPreferencesHelper.getInferencePresencePenaltyEnabled()) sharedPreferencesHelper.getInferencePresencePenaltyValue().toDoubleOrNull() else null


            )

            try {
                lanHttpClient.preparePost(activeChatUrl) {
                    header("Authorization", "Bearer $activeChatApiKey")
                    header(HttpHeaders.Accept, "text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody(chatRequest)
                }.execute { httpResponse ->
                    if (!httpResponse.status.isSuccess()) {
                        val errorBody = try {
                            httpResponse.bodyAsText()
                        } catch (ex: Exception) {
                            "No details"
                        }
                        val openRouterError = parseOpenRouterError(errorBody)
                        throw Exception(openRouterError)
                    }

                    val channel = httpResponse.body<ByteReadChannel>()
                    var accumulatedResponse = ""
                    var accumulatedReasoning = ""
                    var hasUsedReasoningDetails = false
                    var reasoningStarted = false
                    var finish_reason: String? = null
                    var lastChoice: StreamedChoice? = null
                    val toolCallBuffer = mutableListOf<ToolCall>()
                    val accumulatedAnnotations = mutableListOf<Annotation>()
                    val accumulatedImages = mutableListOf<String>()
                    var streamAborted = false

                    val pump = StreamUiPump(viewModelScope) { partial ->
                        updateMessages { list -> putAssistantMessage(list, thinkingMessage, partial) }
                    }
                    activeStreamPump = pump
                    pump.drive {
                        forEachSseJsonPayload(
                            channel,
                            shouldStop = { streamAborted }
                        ) { jsonString ->
                            if (streamAborted) return@forEachSseJsonPayload
                            val chunk = parseStreamChunk(jsonString) ?: return@forEachSseJsonPayload

                            chunk.error?.let { apiError ->
                                val rawDetails = "Code: ${apiError.code ?: "unknown"} - ${apiError.message ?: "Mid-stream error"}"
                                withContext(Dispatchers.Main) {
                                    pump.cancel()
                                    handleError(Exception(rawDetails), thinkingMessage)
                                }
                                streamAborted = true
                                return@forEachSseJsonPayload
                            }

                            val choice = chunk.choices.firstOrNull()
                            finish_reason = choice?.finish_reason ?: finish_reason
                            lastChoice = choice
                            choice?.error?.let { apiError ->
                                withContext(Dispatchers.Main) {
                                    pump.cancel()
                                    handleErrorResponse(apiError, thinkingMessage)
                                }
                                streamAborted = true
                                return@forEachSseJsonPayload
                            }
                            val delta = choice?.delta ?: return@forEachSseJsonPayload

                            var contentChanged = false
                            var reasoningChanged = false

                            if (!delta.content.isNullOrEmpty()) {
                                accumulatedResponse += delta.content
                                contentChanged = true
                            }

                            if (delta.reasoning_details?.isNotEmpty() == true) {
                                hasUsedReasoningDetails = true
                                delta.reasoning_details.forEach { detail ->
                                    if (detail.type == "reasoning.text" && detail.text != null) {
                                        if (!reasoningStarted) {
                                            accumulatedReasoning = ""
                                            reasoningStarted = true
                                        }
                                        accumulatedReasoning += detail.text
                                        reasoningChanged = true
                                    }
                                }
                            } else if (!hasUsedReasoningDetails && !delta.reasoning.isNullOrEmpty()) {
                                if (!reasoningStarted) {
                                    accumulatedReasoning = ""
                                    reasoningStarted = true
                                }
                                accumulatedReasoning += delta.reasoning
                                reasoningChanged = true
                            }

                            if (contentChanged || reasoningChanged) {
                                pump.offer(
                                    FlexibleMessage(
                                        role = "assistant",
                                        content = JsonPrimitive(accumulatedResponse),
                                        reasoning = accumulatedReasoning.ifBlank { null }
                                    )
                                )
                            }

                            delta.toolCalls?.forEach { deltaTc ->
                                val index = deltaTc.index
                                if (index >= toolCallBuffer.size) {
                                    toolCallBuffer.add(
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
                                    val existing = toolCallBuffer[index]
                                    toolCallBuffer[index] = existing.copy(
                                        function = existing.function.copy(
                                            name = existing.function.name + (deltaTc.function?.name ?: ""),
                                            arguments = existing.function.arguments + (deltaTc.function?.arguments ?: "")
                                        )
                                    )
                                }
                            }

                            accumulatedAnnotations.addAll(delta.annotations ?: emptyList())
                            delta.images?.forEach { accumulatedImages.add(it.image_url.url) }
                        }
                    }

                    if (streamAborted) return@execute

                    val downloadedUris = if (accumulatedImages.isNotEmpty()) {
                        downloadImages(accumulatedImages)
                    } else emptyList()

                    when (finish_reason) {
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
                        "tool_calls", "stop", null -> {}
                        else -> {

                        }
                    }

                    if (reasoningStarted) {
                        // keep raw reasoning for expand/collapse UI
                    }

                    val hadToolCalls = toolCallBuffer.isNotEmpty()
                    val citationsMarkdown = if (sharedPreferencesHelper.getShowCitations()) {
                        formatCitations(accumulatedAnnotations)
                    } else ""
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
                            val rawContent = (accumulatedResponse + citationsMarkdown).takeIf { it.isNotBlank() } ?: "No response received."
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
                        // answer-only: skip error system notifications
                    }
                }
            }
        }
    }

    internal suspend fun handleStreamedResponse(
        modelForRequest: String,
        messagesForApiRequest: List<FlexibleMessage>,
        thinkingMessage: FlexibleMessage
    ) {
        withContext(Dispatchers.IO) {
            val sharedPreferencesHelper =
                SharedPreferencesHelper(application.applicationContext)

            // --- Detection for Lyria / Audio models ---
            val isLyria = modelForRequest.contains("google/lyria", ignoreCase = true)

            // --- Existing config ---
            val webSearchOpts = if (!isRpMode() && sharedPreferencesHelper.getWebSearchBoolean() && !activeModelIsLan()) {
                WebSearchOptions(
                    searchContextSize = sharedPreferencesHelper.getWebSearchContextSize()
                )
            } else null
            val maxTokens = try {
                sharedPreferencesHelper.getMaxTokens().toIntOrNull() ?: 12000
            } catch (e: Exception) {
                12000
            }
            val maxRTokens = sharedPreferencesHelper.getReasoningMaxTokens()?.takeIf { it > 0 }
            val effort = if (maxRTokens == null) sharedPreferencesHelper.getReasoningEffort() else null

            // --- Build ChatRequest with ALL features ---
            val chatRequest = ChatRequest(
                model = modelForRequest,
                messages = messagesForApiRequest,
                transforms = if (sharedPreferencesHelper.getOpenRouterTransformsEnabled() && !activeModelIsLan())
                    listOf("middle-out")
                else null,
                stream = true,
                max_tokens = maxTokens,
                tools = if (!isRpMode() && _isToolsEnabled.value == true) buildTools() else null,
                plugins = buildWebSearchPlugin(),
                webSearchOptions = webSearchOpts,
                toolChoice = if (!isRpMode() && _isToolsEnabled.value == true) "auto" else null,
                // === INFERENCE PARAMETERS ===
                temperature = if (sharedPreferencesHelper.getInferenceTempEnabled()) sharedPreferencesHelper.getInferenceTempValue().toDoubleOrNull() else null,
                topP = if (sharedPreferencesHelper.getInferenceTopPEnabled()) sharedPreferencesHelper.getInferenceTopPValue().toDoubleOrNull() else null,
                topK = if (sharedPreferencesHelper.getInferenceTopKEnabled()) sharedPreferencesHelper.getInferenceTopKValue() else null,
                minP = if (sharedPreferencesHelper.getInferenceMinPEnabled()) sharedPreferencesHelper.getInferenceMinPValue().toDoubleOrNull() else null,
                repetitionPenalty = if (sharedPreferencesHelper.getInferenceRepetitionPenaltyEnabled()) sharedPreferencesHelper.getInferenceRepetitionPenaltyValue().toDoubleOrNull() else null,
                presencePenalty = if (sharedPreferencesHelper.getInferencePresencePenaltyEnabled()) sharedPreferencesHelper.getInferencePresencePenaltyValue().toDoubleOrNull() else null,

                // === AUDIO modality ===
                modalities = if (isLyria) {
                    listOf("text", "audio")
                } else if (isImageGenerationModel(modelForRequest)) {
                    if (modelForRequest.contains("bytedance-seed", ignoreCase = true) ||
                        modelForRequest.contains("black-forest-labs", ignoreCase = true) ||
                        modelForRequest.contains("sourceful/riverflow", ignoreCase = true)
                    ) {
                        listOf("image")
                    } else {
                        listOf("image", "text")
                    }
                } else null,
                // === IMAGE CONFIG (Gemini image gen) ===
                imageConfig = if (isImageGenerationModel(modelForRequest) &&
                    modelForRequest.contains("google", ignoreCase = true) &&
                    modelForRequest.contains("gemini", ignoreCase = true) &&
                    modelForRequest.contains("image", ignoreCase = true)
                ) {
                    val aspectRatio = sharedPreferencesHelper.getGeminiAspectRatio() ?: "1:1"
                    ImageConfig(aspectRatio = aspectRatio)
                } else null,
                // === REASONING CONFIG ===
                reasoning = if (_isReasoningEnabled.value == true && isReasoningModel(_activeChatModel.value)) {
                    if (sharedPreferencesHelper.getAdvancedReasoningEnabled()) {
                        Reasoning(
                            enabled = true,
                            exclude = sharedPreferencesHelper.getReasoningExclude(),
                            effort = effort,
                            max_tokens = maxRTokens
                        )
                    } else {
                        Reasoning(enabled = true, exclude = true)
                    }
                } else if (_isReasoningEnabled.value == false && isReasoningModel(_activeChatModel.value)) {
                    Reasoning(enabled = false, exclude = true)
                } else {
                    null
                }
            )

            try {
                (if (DemoModel.isDemo(modelForRequest)) demoHttpClient else httpClient).preparePost(activeChatUrl) {
                    header("Authorization", "Bearer $activeChatApiKey")
                    header("HTTP-Referer", "https://github.com/Warexpor/oxproxion")
                    header("X-Title", "GradatiON")
                    header(HttpHeaders.Accept, "text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody(chatRequest)
                }.execute { httpResponse ->
                    if (!httpResponse.status.isSuccess()) {
                        val errorBody = try {
                            httpResponse.bodyAsText()
                        } catch (ex: Exception) {
                            "No details"
                        }
                        throw Exception(parseOpenRouterError(errorBody))
                    }

                    val channel = httpResponse.body<ByteReadChannel>()
                    var accumulatedResponse = ""
                    var accumulatedReasoning = ""
                    var hasUsedReasoningDetails = false
                    var reasoningStarted = false
                    var finish_reason: String? = null
                    val toolCallBuffer = mutableListOf<ToolCall>()
                    val accumulatedAnnotations = mutableListOf<Annotation>()
                    val accumulatedImages = mutableListOf<String>()

                    // --- AUDIO variables ---
                    val audioBuffer = StringBuilder()
                    var streamAborted = false

                    val pump = StreamUiPump(viewModelScope) { partial ->
                        updateMessages { list -> putAssistantMessage(list, thinkingMessage, partial) }
                    }
                    activeStreamPump = pump
                    pump.drive {
                        forEachSseJsonPayload(
                            channel,
                            shouldStop = { streamAborted }
                        ) { jsonString ->
                            if (streamAborted) return@forEachSseJsonPayload
                            val chunk = parseStreamChunk(jsonString) ?: return@forEachSseJsonPayload

                            // Handle mid-stream error
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
                            finish_reason = choice?.finish_reason ?: finish_reason
                            choice?.error?.let { apiError ->
                                withContext(Dispatchers.Main) {
                                    pump.cancel()
                                    handleErrorResponse(apiError, thinkingMessage)
                                }
                                streamAborted = true
                                return@forEachSseJsonPayload
                            }
                            val delta = choice?.delta ?: return@forEachSseJsonPayload

                            // === AUDIO ACCUMULATION ===
                            delta.audio?.let { audioDelta ->
                                audioDelta.data?.let { audioBuffer.append(it) }
                            }

                            // === TEXT ACCUMULATION ===
                            var contentChanged = false
                            if (!delta.content.isNullOrEmpty()) {
                                accumulatedResponse += delta.content
                                contentChanged = true
                            }

                            // === REASONING ACCUMULATION ===
                            var reasoningChanged = false
                            if (delta.reasoning_details?.isNotEmpty() == true) {
                                hasUsedReasoningDetails = true
                                delta.reasoning_details.forEach { detail ->
                                    if (detail.type == "reasoning.text" && detail.text != null) {
                                        if (!reasoningStarted) {
                                            accumulatedReasoning = ""
                                            reasoningStarted = true
                                        }
                                        accumulatedReasoning += detail.text
                                        reasoningChanged = true
                                    }
                                }
                            } else if (!hasUsedReasoningDetails && !delta.reasoning.isNullOrEmpty()) {
                                if (!reasoningStarted) {
                                    accumulatedReasoning = ""
                                    reasoningStarted = true
                                }
                                accumulatedReasoning += delta.reasoning
                                reasoningChanged = true
                            }

                            // === REAL-TIME UI UPDATE ===
                            if (contentChanged || reasoningChanged) {
                                pump.offer(
                                    FlexibleMessage(
                                        role = "assistant",
                                        content = JsonPrimitive(accumulatedResponse),
                                        reasoning = accumulatedReasoning.ifBlank { null }
                                    )
                                )
                            }

                            // === TOOL CALLS BUFFERING ===
                            delta.toolCalls?.forEach { deltaTc ->
                                val index = deltaTc.index
                                if (index >= toolCallBuffer.size) {
                                    toolCallBuffer.add(
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
                                    val existing = toolCallBuffer[index]
                                    toolCallBuffer[index] = existing.copy(
                                        function = existing.function.copy(
                                            name = existing.function.name + (deltaTc.function?.name ?: ""),
                                            arguments = existing.function.arguments + (deltaTc.function?.arguments ?: "")
                                        )
                                    )
                                }
                            }

                            // === ANNOTATIONS & IMAGES ===
                            accumulatedAnnotations.addAll(delta.annotations ?: emptyList())
                            delta.images?.forEach { accumulatedImages.add(it.image_url.url) }
                        }
                    }

                    // ============================================================
                    //  POST-STREAM PROCESSING
                    // ============================================================

                    // --- 1. Finish reason handling ---
                    if (streamAborted) return@execute

                    when (finish_reason) {
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

                        "tool_calls", "stop", null -> { /* Normal */ }

                        else -> {
                          //  Log.w("ChatViewModel", "Unknown finish_reason: $finish_reason")
                        }
                    }

                    // --- 2. Close reasoning code fence ---
                    if (reasoningStarted) {
                        // keep raw reasoning for expand/collapse UI
                    }

                    // --- 3. Download generated images ---
                    val downloadedUris = if (accumulatedImages.isNotEmpty()) {
                        downloadImages(accumulatedImages)
                    } else emptyList()

                    // --- 4. Save Audio if present ---
                    if (audioBuffer.isNotEmpty()) {
                        try {
                            val audioBytes = Base64.getDecoder().decode(audioBuffer.toString())
                            val filename = "lyria_${System.currentTimeMillis()}.mp3"
                            val mimeType = "audio/mpeg"
                            saveBinaryFileToDownloads(filename, audioBytes, mimeType)
                            _toolUiEvent.postValue(Event("✅ Music saved: $filename"))
                        } catch (e: Exception) {
                            _toolUiEvent.postValue(Event("❌ Audio save failed: ${e.message}"))
                        }
                    }

                    // --- 5. Citations ---
                    val citationsMarkdown = if (sharedPreferencesHelper.getShowCitations()) {
                        formatCitations(accumulatedAnnotations)
                    } else ""

                    // --- 6. Final UI Update ---
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
                        // Finalize on Main so Stop/cancel cannot race swipe state mutations on IO.
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

                    // --- 7. Notification logic ---
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
                        // answer-only: skip error system notifications
                    }
                }
            }
        }
    }

    internal suspend fun handleNonStreamedResponseLAN(
        modelForRequest: String,
        messagesForApiRequest: List<FlexibleMessage>,
        thinkingMessage: FlexibleMessage?
    ) {
        withTimeout((sharedPreferencesHelper.getTimeoutMinutes().toLong() * 60_000L).milliseconds) {
            withContext(Dispatchers.IO) {
                val sharedPreferencesHelper =
                    SharedPreferencesHelper(application.applicationContext)

                val maxTokens = try {
                    sharedPreferencesHelper.getMaxTokens().toIntOrNull() ?: 12000
                } catch (e: Exception) {
                    12000
                }
                val isReasoningModel = isReasoningModel(_activeChatModel.value)
                val lanProvider = sharedPreferencesHelper.getLanProvider()

                val llamaCppKwargs = if (
                    lanProvider == LAN_PROVIDER_LLAMA_CPP &&
                    isReasoningModel
                ) {
                    mapOf("enable_thinking" to JsonPrimitive(_isReasoningEnabled.value == true))
                } else {
                    null
                }

                val chatRequest = ChatRequest(
                    model = modelForRequest,
                    messages = messagesForApiRequest,
                    think = if (isReasoningModel && lanProvider == LAN_PROVIDER_OLLAMA) {
                        _isReasoningEnabled.value
                    } else null,
                    // ADD THIS: For Ollama OpenAI-compatible endpoint
                    reasoningEffort = if (isReasoningModel && lanProvider == LAN_PROVIDER_OLLAMA) {
                        if (_isReasoningEnabled.value == true) null else "none"
                    } else null,
                    chatTemplateKwargs = llamaCppKwargs,
                    max_tokens = maxTokens,
                    tools = if (!isRpMode() && _isToolsEnabled.value == true) buildTools() else null,
                    toolChoice = if (!isRpMode() && _isToolsEnabled.value == true) "auto" else null,
                            // === INFERENCE PARAMETERS ===
                            temperature = if (sharedPreferencesHelper.getInferenceTempEnabled()) sharedPreferencesHelper.getInferenceTempValue().toDoubleOrNull() else null,
                    topP = if (sharedPreferencesHelper.getInferenceTopPEnabled()) sharedPreferencesHelper.getInferenceTopPValue().toDoubleOrNull() else null,
                    topK = if (sharedPreferencesHelper.getInferenceTopKEnabled()) sharedPreferencesHelper.getInferenceTopKValue() else null,
                    minP = if (sharedPreferencesHelper.getInferenceMinPEnabled()) sharedPreferencesHelper.getInferenceMinPValue().toDoubleOrNull() else null,
                    repetitionPenalty = if (sharedPreferencesHelper.getInferenceRepetitionPenaltyEnabled()) sharedPreferencesHelper.getInferenceRepetitionPenaltyValue().toDoubleOrNull() else null,
                    presencePenalty = if (sharedPreferencesHelper.getInferencePresencePenaltyEnabled()) sharedPreferencesHelper.getInferencePresencePenaltyValue().toDoubleOrNull() else null

                )

                val response = lanHttpClient.post(activeChatUrl) {
                    header("Authorization", "Bearer $activeChatApiKey")
                    contentType(ContentType.Application.Json)
                    setBody(chatRequest)
                }

                if (!response.status.isSuccess()) {
                    val errorBody = try {
                        response.bodyAsText()
                    } catch (ex: Exception) {
                        "No details"
                    }

                    // You may need to adjust this parser for Ollama/LM Studio specifically
                    val lanError = parseOpenRouterError(errorBody)

                    if (sharedPreferencesHelper.getNotiPreference()) {
                        val apiIdentifier = activeChatModel.value ?: "Unknown Model"
                        val displayName = getModelDisplayName(apiIdentifier)
                        sharedPreferencesHelper.saveLastAiResponseForChannel(
                            2,
                            lanError
                        )
                        // answer-only: skip error system notifications
                    }

                    throw Exception(lanError)
                }

                response.body<ChatResponse>()
            }.let { chatResponse ->
                withContext(Dispatchers.Main) {
                val choice = chatResponse.choices.firstOrNull()
                val finishReason = choice?.finish_reason
                var errorHandled = false
                choice?.error?.let { error ->
                    handleErrorResponse(error, thinkingMessage)
                    errorHandled = true
                }
                if (errorHandled) {
                    return@withContext
                }
                when (finishReason) {
                        "error" -> {
                            val errorMsg =
                                "**Error:** The model encountered an error while generating the response. Please try again."
                            handleError(Exception(errorMsg), thinkingMessage)
                            //  return@let
                            return@withContext
                        }

                        "content_filter" -> {
                            val errorMsg = application.getString(R.string.error_provider_content_filter)
                            handleError(Exception(errorMsg), thinkingMessage)
                          //  return@let
                            return@withContext
                        }

                        "length" -> {

                                AppToast.makeText(
                                    application.applicationContext,
                                    application.getString(R.string.toast_response_truncated_max_tokens),
                                    AppToast.LENGTH_LONG
                                ).show()

                        }

                        "tool_calls", "stop", null -> {
                        }

                        else -> {

                        }
                    }

                if (choice?.message?.toolCalls?.isNotEmpty() == true && !toolCallsHandledForTurn && !isRpMode() && _isToolsEnabled.value == true) {
                    val toolCalls = choice.message.toolCalls

                    val citationsMarkdown = if (sharedPreferencesHelper.getShowCitations()) {
                        formatCitations(choice.message.annotations)
                    } else {
                        ""
                    }
                    val rawContent = choice.message.content ?: ""
                    val cleanContent = if (rawContent.trimStart().startsWith("</think>")) {
                        rawContent.substringAfter("</think>").trimStart()
                    } else {
                        rawContent
                    }
                    val assistantMessage = FlexibleMessage(
                        role = "assistant",
                        content = JsonPrimitive(cleanContent ?: ("" + citationsMarkdown)),
                        toolCalls = toolCalls
                    )
                    updateMessages { list ->
                        if (thinkingMessage == null) list.add(assistantMessage)
                        else putAssistantMessage(list, thinkingMessage, assistantMessage)
                    }
                    handleToolCalls(toolCalls, thinkingMessage)
                } else {
                    val downloadedUris = choice?.message?.images?.let { images ->
                        val imageUrls = images.map { it.image_url.url }
                        downloadImages(imageUrls)
                    } ?: emptyList()

                    handleSuccessResponse(
                        chatResponse,
                        thinkingMessage,
                        downloadedUris
                    )
                }
            }
        }
        }
    }

    internal suspend fun handleNonStreamedResponse(modelForRequest: String, messagesForApiRequest: List<FlexibleMessage>, thinkingMessage: FlexibleMessage?) {
        withTimeout((sharedPreferencesHelper.getTimeoutMinutes().toLong() * 60_000L).milliseconds) {
            withContext(Dispatchers.IO) {
                val sharedPreferencesHelper =
                    SharedPreferencesHelper(application.applicationContext)
                val webSearchOpts =
                    if (!isRpMode() && sharedPreferencesHelper.getWebSearchBoolean() && !activeModelIsLan()) {
                        WebSearchOptions(
                            searchContextSize = sharedPreferencesHelper.getWebSearchContextSize()
                        )
                    } else null
                val maxTokens = try {
                    sharedPreferencesHelper.getMaxTokens().toIntOrNull() ?: 12000
                } catch (e: Exception) {
                    12000  // Fallback on any prefs error
                }
                val maxRTokens = sharedPreferencesHelper.getReasoningMaxTokens()?.takeIf { it > 0 }
                val effort =
                    if (maxRTokens == null) sharedPreferencesHelper.getReasoningEffort() else null
                val chatRequest = ChatRequest(
                    model = modelForRequest,
                    messages = messagesForApiRequest,
                    transforms = if (sharedPreferencesHelper.getOpenRouterTransformsEnabled() && !activeModelIsLan())
                        listOf("middle-out")
                    else
                        null,
                    //logprobs = null,
                    //  usage = UsageRequest(include = true),
                    max_tokens = maxTokens,
                    reasoning = if (_isReasoningEnabled.value == true && isReasoningModel(
                            _activeChatModel.value
                        )
                    ) {
                        if (sharedPreferencesHelper.getAdvancedReasoningEnabled()) {
                            Reasoning(
                                enabled = true,
                                exclude = sharedPreferencesHelper.getReasoningExclude(),
                                effort = effort,
                                max_tokens = maxRTokens
                            )
                        } else {
                            Reasoning(enabled = true, exclude = true)
                        }
                    } else if (_isReasoningEnabled.value == false && isReasoningModel(
                            _activeChatModel.value
                        )
                    ) {
                        Reasoning(enabled = false, exclude = true)
                    } else {
                        null
                    },
                    tools = if (!isRpMode() && _isToolsEnabled.value == true) buildTools() else null,
                    toolChoice = if (!isRpMode() && _isToolsEnabled.value == true) "auto" else null,
                    plugins = buildWebSearchPlugin(),
                    webSearchOptions = webSearchOpts,
                    // === INFERENCE PARAMETERS ===
                    temperature = if (sharedPreferencesHelper.getInferenceTempEnabled()) sharedPreferencesHelper.getInferenceTempValue().toDoubleOrNull() else null,
                    topP = if (sharedPreferencesHelper.getInferenceTopPEnabled()) sharedPreferencesHelper.getInferenceTopPValue().toDoubleOrNull() else null,
                    topK = if (sharedPreferencesHelper.getInferenceTopKEnabled()) sharedPreferencesHelper.getInferenceTopKValue() else null,
                    minP = if (sharedPreferencesHelper.getInferenceMinPEnabled()) sharedPreferencesHelper.getInferenceMinPValue().toDoubleOrNull() else null,
                    repetitionPenalty = if (sharedPreferencesHelper.getInferenceRepetitionPenaltyEnabled()) sharedPreferencesHelper.getInferenceRepetitionPenaltyValue().toDoubleOrNull() else null,
                    presencePenalty = if (sharedPreferencesHelper.getInferencePresencePenaltyEnabled()) sharedPreferencesHelper.getInferencePresencePenaltyValue().toDoubleOrNull() else null,

                    modalities = if (isImageGenerationModel(modelForRequest)) {
                        if (modelForRequest.contains("bytedance-seed", ignoreCase = true) ||
                            modelForRequest.contains("black-forest-labs", ignoreCase = true) ||
                            modelForRequest.contains("sourceful/riverflow", ignoreCase = true)
                        ) {
                            listOf("image")
                        } else {
                            listOf("image", "text")
                        }
                    } else null,
                    imageConfig = if (isImageGenerationModel(modelForRequest) &&
                        modelForRequest.contains("google", ignoreCase = true) &&
                        modelForRequest.contains("gemini", ignoreCase = true) &&
                        modelForRequest.contains("image", ignoreCase = true)
                    ) {
                        val aspectRatio = sharedPreferencesHelper.getGeminiAspectRatio() ?: "1:1"
                        ImageConfig(aspectRatio = aspectRatio)
                    } else null,
                )

                val response = httpClient.post(activeChatUrl) {
                    header("Authorization", "Bearer $activeChatApiKey")
                    header("HTTP-Referer", "https://github.com/Warexpor/oxproxion")
                    header("X-Title", "GradatiON")
                    contentType(ContentType.Application.Json)
                    setBody(chatRequest)
                }

                if (!response.status.isSuccess()) {
                    val errorBody = try {
                        response.bodyAsText()
                    } catch (ex: Exception) {
                        "No details"
                    }
                    val openRouterError = parseOpenRouterError(errorBody)  // Use the parser!
                    if (sharedPreferencesHelper.getNotiPreference()) {
                        val apiIdentifier = activeChatModel.value ?: "Unknown Model"
                        val displayName = getModelDisplayName(apiIdentifier)
                        sharedPreferencesHelper.saveLastAiResponseForChannel(
                            2,
                            openRouterError
                        )//#ttsnoti
                        // answer-only: skip error system notifications
                    }
                    throw Exception(openRouterError)  // Now throws friendly message
                }

                response.body<ChatResponse>()
            }.let { chatResponse ->
                withContext(Dispatchers.Main) {

                val choice = chatResponse.choices.firstOrNull()
                val finishReason = choice?.finish_reason
                var errorHandled = false
                choice?.error?.let { error ->
                    handleErrorResponse(error, thinkingMessage)
                    errorHandled = true  // Flag to skip when block
                }
                if (errorHandled) {
                    return@withContext
                }
                when (finishReason) {
                        "error" -> {
                            val errorMsg =
                                "**Error:** The model encountered an error while generating the response. Please try again."
                            handleError(Exception(errorMsg), thinkingMessage)
                           // return@let  // or return@execute for streamed
                            return@withContext
                        }

                        "content_filter" -> {
                            val errorMsg = application.getString(R.string.error_provider_content_filter)
                            handleError(Exception(errorMsg), thinkingMessage)
                          //  return@let  // or return@execute for streamed
                            return@withContext
                        }

                        "length" -> {
                            // Show Toast for truncation

                                AppToast.makeText(
                                    application.applicationContext,
                                    application.getString(R.string.toast_response_truncated_max_tokens),
                                    AppToast.LENGTH_LONG
                                ).show()

                            // Still proceed to display the response
                        }

                        "tool_calls", "stop", null -> {
                            // Normal cases: Proceed as usual
                        }

                        else -> {
                            // Unknown reason: Log for debugging
                            //   Log.w("ChatViewModel", "Unknown finish_reason: $finishReason (native: ${choice.native_finish_reason})")
                        }
                    }

                // Trust the presence of tool calls over the finish_reason for robustness.
                if (choice?.message?.toolCalls?.isNotEmpty() == true && !toolCallsHandledForTurn && !isRpMode() && _isToolsEnabled.value == true) {
                    val toolCalls = choice.message.toolCalls
                    // Create the complete assistant message from the response
                    val citationsMarkdown = if (sharedPreferencesHelper.getShowCitations()) {
                        formatCitations(choice.message.annotations)
                    } else {
                        ""
                    }
                    val assistantMessage = FlexibleMessage(
                        role = "assistant",
                        content = JsonPrimitive(choice.message.content ?: ("" + citationsMarkdown)),
                        toolCalls = toolCalls
                    )
                    updateMessages { list ->
                        if (thinkingMessage == null) list.add(assistantMessage)
                        else putAssistantMessage(list, thinkingMessage, assistantMessage)
                    }
                    handleToolCalls(toolCalls, thinkingMessage)
                } else {
                    // Download images if present
                    val downloadedUris = choice?.message?.images?.let { images ->
                        val imageUrls = images.map { it.image_url.url }
                        downloadImages(imageUrls)
                    } ?: emptyList()

                    handleSuccessResponse(
                        chatResponse,
                        thinkingMessage,
                        downloadedUris
                    )  // NEW: Pass Uris
                }
            }
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

    // New function for detailed error handling
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
        // Optionally, include metadata if present
        error.metadata?.let { meta ->
        //    Log.e("ChatViewModel", "Error metadata: $meta")
        }

        // Update the UI with the detailed message (similar to handleError)
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
}
