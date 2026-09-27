package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Pipe
import okio.buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * "GradatiON: Demo": a built-in model that needs no key or network. Its replies come back as a
 * real server-sent-event stream (an OkHttp interceptor answers the request locally), so they
 * travel the exact path a real model's do: parsing, reasoning, paced reveal, autosave. Handy to
 * try the app, and to see how streaming, markdown and the message tools look.
 */
object DemoModel {

    const val ID = "gradation/demo"
    const val NAME = "GradatiON: Demo"

    fun isDemo(modelId: String?) = modelId == ID

    fun model() = LlmModel(NAME, ID, isVisionCapable = false, isReasoningCapable = true)

    /** A short local title for saved demo chats (no model call). */
    fun titleFor(firstUserMessage: String): String =
        firstUserMessage.trim().lineSequence().firstOrNull().orEmpty().take(40).ifBlank { "Demo chat" }

    private val turn = AtomicInteger(0)

    /** Multiplies every pause in the stream. Tests set it near 0 so they don't wait on theatre. */
    @Volatile
    var pace: Float = 1f

    /** Answers any request with a paced SSE stream picked from [reply]. */
    class StreamInterceptor(private val roleplay: () -> Boolean) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val body = runCatching { Buffer().also { request.body?.writeTo(it) }.readUtf8() }.getOrDefault("")
            val userText = runCatching { lastUserText(body) }.getOrDefault("")
            val streaming = runCatching {
                // `stream: false` is a default and isn't serialized, so only an explicit true streams.
                Json.parseToJsonElement(body).jsonObject["stream"]?.jsonPrimitive?.contentOrNull == "true"
            }.getOrDefault(true)
            if (!streaming) return oneShot(request, userText)
            val script = reply(userText, roleplay())
            val pipe = Pipe(64 * 1024)
            Thread({ play(script, pipe) }, "demo-stream").apply { isDaemon = true }.start()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/event-stream")
                .body(pipe.source.buffer().asResponseBody("text/event-stream".toMediaType()))
                .build()
        }
    }

    class Script(val thinking: String?, val text: String)

    /** Background chores (RP Facts upkeep) ask without streaming; answer with one JSON reply. */
    private fun oneShot(request: okhttp3.Request, userText: String): Response {
        val text = if ("fact notes" in userText) DEMO_MEMORY else "OK"
        Thread.sleep((300 * pace).toLong())
        val json = buildJsonObject {
            put("id", "demo")
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", ID)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject { put("role", "assistant"); put("content", text) })
                    put("finish_reason", "stop")
                })
            })
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "application/json")
            .body(Buffer().writeUtf8(json.toString()).asResponseBody("application/json".toMediaType()))
            .build()
    }

    const val DEMO_MEMORY = "- The river nearly took the user on the way here\n" +
        "- The map marks where it happened, circled in red\n" +
        "- They leave at first light with the rope\n" +
        "- The innkeeper must not know where they are going"

    private fun lastUserText(body: String): String {
        val messages = Json.parseToJsonElement(body).jsonObject["messages"]?.jsonArray ?: return ""
        val last = messages.lastOrNull { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "user" } ?: return ""
        return when (val c = last.jsonObject["content"]) {
            is JsonPrimitive -> c.contentOrNull.orEmpty()
            is JsonArray -> c.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString(" ")
            else -> ""
        }
    }

    /** Medium speed: small uneven chunks every ~40ms, like a real model on a good day. */
    private fun play(script: Script, pipe: Pipe) {
        val sink = pipe.sink.buffer()
        val rnd = Random(script.text.hashCode())
        fun event(delta: JsonObject, finish: String? = null) {
            val chunk = buildJsonObject {
                put("id", "demo")
                put("object", "chat.completion.chunk")
                put("model", ID)
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", delta)
                        if (finish != null) put("finish_reason", finish)
                    })
                })
            }
            sink.writeUtf8("data: ").writeUtf8(chunk.toString()).writeUtf8("\n\n")
            sink.flush()
        }
        fun stream(text: String, field: String, pause: Long) {
            var i = 0
            while (i < text.length) {
                val n = rnd.nextInt(3, 14).coerceAtMost(text.length - i)
                event(buildJsonObject { put(field, text.substring(i, i + n)) })
                i += n
                Thread.sleep(((pause + rnd.nextLong(0, 30)) * pace).toLong())
            }
        }
        try {
            Thread.sleep((450 * pace).toLong()) // time to first token
            script.thinking?.let { stream(it, "reasoning", 22) }
            stream(script.text, "content", 32)
            event(buildJsonObject { }, finish = "stop")
            sink.writeUtf8("data: [DONE]\n\n").flush()
        } catch (_: IOException) {
            // Stopped by the user: the call was cancelled and the pipe closed.
        } catch (_: InterruptedException) {
        } finally {
            runCatching { sink.close() }
        }
    }

    fun reply(userText: String, roleplay: Boolean): Script {
        val t = userText.lowercase()
        if (roleplay) return ROLEPLAY[turn.getAndIncrement() % ROLEPLAY.size]
        return when {
            "code" in t || "kotlin" in t || "function" in t -> CODE
            "table" in t || "compare" in t -> TABLE
            "list" in t || "steps" in t || "how" in t -> STEPS
            else -> ASK[turn.getAndIncrement() % ASK.size]
        }
    }

    private val WELCOME = Script(
        thinking = "The user is trying the demo. Show what a reply can hold: a heading, emphasis, a list, a bit of code and a quote, and keep it friendly.",
        text = """
            ## Hi, this is the demo model

            I'm running **on your phone**, no key or network needed. Everything you see here streams the same way a real model's reply does, so it's a good place to try things out.

            A reply can hold:

            - **Bold**, *italic* and `inline code`
            - Lists like this one
            - Code blocks with a copy button
            - Tables, quotes and links

            ```kotlin
            fun greet(name: String) = "Hello, ${'$'}name"
            ```

            > Tap the icons under this message to copy, share, read aloud, regenerate or edit.

            To talk to a real model, add your OpenRouter key in **Settings > Models & API**.
        """.trimIndent()
    )

    private val THOUGHTFUL = Script(
        thinking = "Give a short, warm answer with a little structure, then offer a follow-up.",
        text = """
            Good question. Here's the short version:

            1. **Start small.** Pick the one thing that matters most today.
            2. **Make it visible.** Write it down where you'll see it.
            3. **Close the loop.** At the end of the day, check it off or move it.

            That's it. Want me to turn this into a checklist?
        """.trimIndent()
    )

    private val ASK = listOf(WELCOME, THOUGHTFUL)

    private val STEPS = Script(
        thinking = "They asked how to do something. Answer in clear numbered steps with one tip.",
        text = """
            Here's how I'd do it:

            1. **Gather what you need** before you start.
            2. **Do the hardest part first**, while you're fresh.
            3. **Check your work** once, then stop polishing.

            *Tip:* if a step takes more than ten minutes, split it in two.
        """.trimIndent()
    )

    private val CODE = Script(
        thinking = "Show a small, idiomatic Kotlin example and explain it in one line.",
        text = """
            Here's a tiny Kotlin function that counts words:

            ```kotlin
            fun wordCount(text: String): Map<String, Int> =
                text.lowercase()
                    .split(Regex("\\W+"))
                    .filter { it.isNotBlank() }
                    .groupingBy { it }
                    .eachCount()
            ```

            It lowercases the text, splits on anything that isn't a letter or digit, and counts each word.
        """.trimIndent()
    )

    private val TABLE = Script(
        thinking = null,
        text = """
            A quick comparison:

            | | Chat | Roleplay | Code |
            |---|---|---|---|
            | For | Questions | Stories | Agents on your machines |
            | Replies | Markdown | Narration | Diffs and tools |
            | Needs | A key | A character | A paired machine |

            Swipe left or right to move between them.
        """.trimIndent()
    )

    private val ROLEPLAY = listOf(
        Script(
            thinking = null,
            text = """
                *The lantern flickers as she sets it on the table, the rain still dripping from her hood.*

                "You're late," she says, not unkindly. "I was starting to think the river took you."

                *She slides a folded map across the wood and taps a spot circled in red ink.*

                "This is where it happened. Are you still in?"
            """.trimIndent()
        ),
        Script(
            thinking = null,
            text = """
                *A slow smile. She leans back, the chair creaking under her.*

                "Good. Then we leave at first light. Bring the rope, and don't tell the innkeeper where we're going."

                *Outside, thunder rolls over the hills like something waking up.*
            """.trimIndent()
        ),
    )
}
