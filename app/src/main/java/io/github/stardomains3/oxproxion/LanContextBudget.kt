package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a local model's prompt inside its context window (unit-tested). Local servers cut the
 * FRONT of an over-long prompt, which is exactly where the system prompt and character card sit,
 * so history is dropped here first and the system messages never are.
 */
object LanContextBudget {
    const val DEFAULT_CONTEXT = 8_192

    /**
     * /api/show reports what the model supports, not the window Ollama actually runs (often 4096
     * unless OLLAMA_CONTEXT_LENGTH is set). Guessing low only trims older history; guessing high
     * lets Ollama cut the character card off the front.
     */
    const val OLLAMA_SAFE_CONTEXT = 4_096
    private const val CHARS_PER_TOKEN = 3.5

    /** Chat template tokens, role markers and estimate error. */
    private const val SLACK_TOKENS = 256

    /** Per-message framing the chat template adds. */
    private const val ROLE_TOKENS = 4

    /** A picture costs about this much on the vision models people run locally. */
    const val IMAGE_TOKENS = 768

    /** Windows seen while talking to the server. Cleared when the endpoint changes. */
    private val detected = ConcurrentHashMap<String, Int>()

    fun estimateTokens(text: String): Int = kotlin.math.ceil(text.length / CHARS_PER_TOKEN).toInt()

    /** The user's Context size wins, then what the server reported, then [DEFAULT_CONTEXT]. */
    fun effectiveContext(userValue: Int?, detected: Int?): Int =
        userValue?.takeIf { it > 0 } ?: detected?.takeIf { it > 0 } ?: DEFAULT_CONTEXT

    /** The window in force for [model] on the saved server. */
    fun contextFor(prefs: SharedPreferencesHelper, model: String): Int {
        val endpoint = prefs.getLanEndpoint().orEmpty()
        return effectiveContext(prefs.getLanContextSize(), detectedFor(endpoint, model))
    }

    /** Rough size of one API message: text at chars/3.5, a flat cost per picture. */
    fun tokensOf(message: FlexibleMessage): Int {
        val body = when (val c = message.content) {
            is JsonPrimitive -> estimateTokens(c.content)
            is JsonArray -> c.sumOf { part ->
                val obj = part as? JsonObject
                when (obj?.get("type")?.jsonPrimitive?.contentOrNull) {
                    "text" -> estimateTokens(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    "image_url" -> IMAGE_TOKENS
                    else -> estimateTokens(part.toString())
                }
            }
            else -> estimateTokens(c.toString())
        }
        val calls = message.toolCalls?.let { estimateTokens(it.toString()) } ?: 0
        return body + calls + ROLE_TOKENS
    }

    /** A reply may use at most a third of the window, so the prompt keeps the rest. */
    fun cappedMaxTokens(requested: Int, context: Int): Int =
        minOf(requested, context / 3).coerceAtLeast(64)

    /** Tokens left for chat history once the system messages and the reply are accounted for. */
    fun historyBudget(context: Int, maxTokens: Int, systemTokens: Int): Int =
        (context - maxTokens - systemTokens - SLACK_TOKENS).coerceAtLeast(0)

    /** Stores 0 when the server wouldn't say, so the probe isn't repeated every turn. */
    fun rememberDetected(endpoint: String, model: String, context: Int) {
        detected["$endpoint|$model"] = context
    }

    fun detectedFor(endpoint: String, model: String): Int? = detected["$endpoint|$model"]

    fun forgetDetected() = detected.clear()
}
