package io.github.stardomains3.oxproxion

import android.util.Log
import io.github.stardomains3.oxproxion.SharedPreferencesHelper.Companion.LAN_PROVIDER_LLAMA_CPP
import io.github.stardomains3.oxproxion.SharedPreferencesHelper.Companion.LAN_PROVIDER_OLLAMA
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.SocketTimeoutException

class LlmService(
    private val httpClient: HttpClient,
    private val baseUrl: String
) {

    companion object {
        private const val SUGGESTION_TIMEOUT_MS = 15_000L
        private const val TAG = "LlmService"
        private const val DEFAULT_TITLE_MODEL = "google/gemma-4-26b-a4b-it"
    }

    suspend fun getSuggestedChatTitle(
        chatContent: String,
        apiKey: String,
        modelId: String,           // NEW: model to use
        endpoint: String,          // NEW: endpoint to call
        isLanModel: Boolean = false, // NEW: tells service what auth header to use
        lanProvider: String? = null,        // Pass the provider (e.g., LAN_PROVIDER_LLAMA_CPP)
        isReasoningModel: Boolean = false,  // Pass whether this specific modelId is a reasoning model
        isThinkingEnabled: Boolean = false,
        client: HttpClient? = null
    ): String? {
        val prompt = "Respond only with a 1 to 8 word title for a save title for this chat. Do not use Markdown in your response. Chat Contents: ```$chatContent```"

        val messages = listOf(
            FlexibleMessage(role = "user", content = JsonPrimitive(prompt))
        )
        val thinkParam = if (lanProvider == LAN_PROVIDER_OLLAMA && isReasoningModel) {
            false
        } else {
            null // Excluded from JSON entirely if not Ollama or not a reasoning model
        }
        val llamaCppKwargs = if (lanProvider == LAN_PROVIDER_LLAMA_CPP && isReasoningModel) {
            mapOf("enable_thinking" to JsonPrimitive(false))
        } else null

        try {
            return withTimeout(SUGGESTION_TIMEOUT_MS) {
                val authHeader =  "Bearer $apiKey"

                val chatRequest = ChatRequest(
                    model = modelId,
                    messages = messages,
                    max_tokens = 40,
                    //logprobs = null,
                    temperature = 0.1,
                    stream = false,
                    think = thinkParam,             // Added Ollama logic
                    chatTemplateKwargs = llamaCppKwargs // Added Llama.cpp logic
                )
                val activeClient = client ?: httpClient
                val response = activeClient.post(endpoint) {
                    header("Authorization", authHeader)
                    contentType(ContentType.Application.Json)
                    setBody(chatRequest)
                }

                if (!response.status.isSuccess()) {
                    val errorBody = try { response.bodyAsText() } catch (ex: Exception) { "No details available" }
                    val errorMessage = "Error: API request failed with status ${response.status.value} - $errorBody"
                    return@withTimeout errorMessage
                }


                val chatResponse = response.body<ChatResponse>()
                val message = chatResponse.choices.firstOrNull()?.message ?: return@withTimeout "Untitled Chat"
                val content = message.content ?: "Untitled Chat"

                val trimmed = content.trim()
                if (trimmed.isBlank()) {
                    return@withTimeout "Untitled Chat"
                }

                trimmed
            }
        } catch (e: Exception) {
            val errorMsg = when (e) {
                is TimeoutCancellationException, is SocketTimeoutException -> "Request timed out for title suggestion."
                is ClientRequestException -> "Client error ${e.response.status} getting title."
                is ServerResponseException -> "Server error ${e.response.status} getting title."
                is IOException -> "Network error getting title."
                else -> "Unexpected error getting title: ${e.localizedMessage ?: "Unknown"}"
            }
            return null
        }
    }

    /**
     * One quiet, non-streamed completion for background chores (RP memory upkeep). Thinking is
     * switched off where the provider allows. Returns the reply text, or null on any failure.
     */
    suspend fun completeOnce(
        prompt: String,
        apiKey: String,
        modelId: String,
        endpoint: String,
        maxTokens: Int,
        lanProvider: String? = null,
        isReasoningModel: Boolean = false,
        timeoutMs: Long = 45_000,
        client: HttpClient? = null
    ): String? {
        val think = if (lanProvider == LAN_PROVIDER_OLLAMA && isReasoningModel) false else null
        val kwargs = if (lanProvider == LAN_PROVIDER_LLAMA_CPP && isReasoningModel) {
            mapOf("enable_thinking" to JsonPrimitive(false))
        } else null
        return try {
            withTimeout(timeoutMs) {
                val response = (client ?: httpClient).post(endpoint) {
                    header("Authorization", "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(
                        ChatRequest(
                            model = modelId,
                            messages = listOf(FlexibleMessage(role = "user", content = JsonPrimitive(prompt))),
                            max_tokens = maxTokens,
                            temperature = 0.2,
                            stream = false,
                            think = think,
                            chatTemplateKwargs = kwargs
                        )
                    )
                }
                if (!response.status.isSuccess()) return@withTimeout null
                response.body<ChatResponse>().choices.firstOrNull()?.message?.content?.trim()?.ifBlank { null }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e is TimeoutCancellationException) null else throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getRemainingCredits(apiKey: String): Double? {
        if (apiKey.isBlank()) {
           // Log.e(TAG, "API key is blank. Cannot fetch credits.")
            return null
        }
        try {
            val response = httpClient.get("https://openrouter.ai/api/v1/credits") {
                header("Authorization", "Bearer $apiKey")
            }

            if (!response.status.isSuccess()) {
                val errorBody = try { response.bodyAsText() } catch (ex: Exception) { "No details" }
              //  Log.e(TAG, "API Error getting credits: ${response.status} - $errorBody")
                return null
            }

            val creditsResponse = response.body<CreditsResponse>()
            return creditsResponse.data.totalCredits - creditsResponse.data.totalUsage
        } catch (e: Exception) {
         //   Log.e(TAG, "Failed to fetch credits", e)
            return null
        }
    }
}