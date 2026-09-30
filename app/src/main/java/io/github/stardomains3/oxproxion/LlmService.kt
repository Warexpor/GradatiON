package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.SharedPreferencesHelper.Companion.LAN_PROVIDER_LLAMA_CPP
import io.github.stardomains3.oxproxion.SharedPreferencesHelper.Companion.LAN_PROVIDER_OLLAMA
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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

class LlmService(
    private val httpClient: HttpClient,
) {

    companion object {
        private const val SUGGESTION_TIMEOUT_MS = 15_000L
    }

    suspend fun getSuggestedChatTitle(
        chatContent: String,
        apiKey: String,
        modelId: String,
        endpoint: String,
        lanProvider: String? = null,
        isReasoningModel: Boolean = false,
        client: HttpClient? = null
    ): String? {
        val prompt = "Respond only with a 1 to 8 word title for a save title for this chat. Do not use Markdown in your response. Chat Contents: ```$chatContent```"
        return try {
            withTimeout(SUGGESTION_TIMEOUT_MS) {
                val response = postOneShot(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    client = client,
                    request = oneShotRequest(
                        modelId = modelId,
                        prompt = prompt,
                        maxTokens = 40,
                        temperature = 0.1,
                        lanProvider = lanProvider,
                        isReasoningModel = isReasoningModel,
                    ),
                )
                if (!response.status.isSuccess()) return@withTimeout null
                response.body<ChatResponse>().choices.firstOrNull()?.message?.content?.trim()?.ifBlank { null }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A timeout is just "no title"; any other cancellation belongs to the caller.
            if (e is TimeoutCancellationException) null else throw e
        } catch (_: Exception) {
            null
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
        return try {
            withTimeout(timeoutMs) {
                val response = postOneShot(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    client = client,
                    request = oneShotRequest(
                        modelId = modelId,
                        prompt = prompt,
                        maxTokens = maxTokens,
                        temperature = 0.2,
                        lanProvider = lanProvider,
                        isReasoningModel = isReasoningModel,
                    ),
                )
                if (!response.status.isSuccess()) return@withTimeout null
                response.body<ChatResponse>().choices.firstOrNull()?.message?.content?.trim()?.ifBlank { null }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e is TimeoutCancellationException) null else throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun oneShotRequest(
        modelId: String,
        prompt: String,
        maxTokens: Int,
        temperature: Double,
        lanProvider: String?,
        isReasoningModel: Boolean,
    ) = ChatRequest(
        model = modelId,
        messages = listOf(FlexibleMessage(role = "user", content = JsonPrimitive(prompt))),
        max_tokens = maxTokens,
        temperature = temperature,
        stream = false,
        think = if (lanProvider == LAN_PROVIDER_OLLAMA && isReasoningModel) false else null,
        chatTemplateKwargs = if (lanProvider == LAN_PROVIDER_LLAMA_CPP && isReasoningModel) {
            mapOf("enable_thinking" to JsonPrimitive(false))
        } else {
            null
        },
    )

    private suspend fun postOneShot(
        endpoint: String,
        apiKey: String,
        client: HttpClient?,
        request: ChatRequest,
    ) = (client ?: httpClient).post(endpoint) {
        header("Authorization", "Bearer $apiKey")
        contentType(ContentType.Application.Json)
        setBody(request)
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