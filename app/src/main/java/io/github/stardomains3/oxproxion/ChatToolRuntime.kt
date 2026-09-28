package io.github.stardomains3.oxproxion

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import com.google.openlocationcode.OpenLocationCode
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tool schemas and execution for Ask chat. Lives outside [ChatViewModel] so a test can
 * construct it with a [ChatToolHost] and no Activity. The ViewModel is one host; tests are another.
 * File, search, and location helpers that only the tools call live here too.
 */
internal interface ChatToolHost {
    val application: Application
    val json: Json
    val httpClient: HttpClient
    val sharedPreferencesHelper: SharedPreferencesHelper
    val chatMessages: MutableLiveData<List<FlexibleMessage>>
    val activeChatModel: MutableLiveData<String>
    val scrollToBottomEvent: MutableLiveData<Event<Unit>>
    val toolUiEvent: MutableLiveData<Event<String>>
    val toastUiEvent: MutableLiveData<Event<String>>
    var streamingAssistantIndex: Int
    var toolCallsHandledForTurn: Boolean
    var toolRecursionDepth: Int
    fun activeModelIsLan(): Boolean
    fun updateMessages(updateBlock: (MutableList<FlexibleMessage>) -> Unit)
    fun putAssistantMessage(
        list: MutableList<FlexibleMessage>,
        thinkingMessage: FlexibleMessage?,
        newMessage: FlexibleMessage,
    )
    fun removeAssistantPlaceholder(thinkingMessage: FlexibleMessage?)
    suspend fun continueConversation(messages: List<FlexibleMessage>)
}

internal class ChatToolRuntime(private val host: ChatToolHost) {
    private val application get() = host.application
    private val json get() = host.json
    private val httpClient get() = host.httpClient
    private val sharedPreferencesHelper get() = host.sharedPreferencesHelper
    private val _chatMessages get() = host.chatMessages
    private val _activeChatModel get() = host.activeChatModel
    private val _scrollToBottomEvent get() = host.scrollToBottomEvent
    private val _toolUiEvent get() = host.toolUiEvent
    private val _toastUiEvent get() = host.toastUiEvent
    private var streamingAssistantIndex
        get() = host.streamingAssistantIndex
        set(value) { host.streamingAssistantIndex = value }
    private var toolCallsHandledForTurn
        get() = host.toolCallsHandledForTurn
        set(value) { host.toolCallsHandledForTurn = value }
    private var toolRecursionDepth
        get() = host.toolRecursionDepth
        set(value) { host.toolRecursionDepth = value }

    private fun activeModelIsLan(): Boolean = host.activeModelIsLan()
    private fun updateMessages(updateBlock: (MutableList<FlexibleMessage>) -> Unit) =
        host.updateMessages(updateBlock)
    private fun putAssistantMessage(
        list: MutableList<FlexibleMessage>,
        thinkingMessage: FlexibleMessage?,
        newMessage: FlexibleMessage,
    ) = host.putAssistantMessage(list, thinkingMessage, newMessage)
    private fun removeAssistantPlaceholder(thinkingMessage: FlexibleMessage?) =
        host.removeAssistantPlaceholder(thinkingMessage)
    private suspend fun continueConversation(messages: List<FlexibleMessage>) =
        host.continueConversation(messages)

    internal fun buildTools(): List<Tool> {
        val allTools = listOf(
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "make_file",
                    description = "Creates a text file (e.g., .txt, .md, .html, .json) and saves it to the Download/gradation workspace. Content should be plain text or structured text. Content is written to disk byte-for-byte, so pass raw, unescaped text (no HTML entities). Use when the user asks for a file to be created.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("filename") {
                                put("type", "string")
                                put("description", "The name of the text file to create, including extension (e.g., summary.txt, data.json).")
                            }
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "The plain text content for the file (e.g., summary or JSON data).")
                            }
                            putJsonObject("mimetype") {
                                put("type", "string")
                                put("description", "MIME type for the text file, e.g., text/plain, application/json, text/markdown.")
                            }
                            putJsonObject("subfolder") {
                                put("type", "string")
                                put("description", "Optional subfolder inside the GradatiON workspace to save the file into (e.g., 'Skills', 'Notes'). Leave empty to save in the root gradation folder.")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("filename"))
                            add(JsonPrimitive("content"))
                            add(JsonPrimitive("mimetype"))
                        }
                    }
                )
            ),
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "set_sound_mode",
                    description = "Gets or sets the device sound mode (Ring, Vibrate, or Silent). " +
                            "If 'mode' is provided, it changes the setting. " +
                            "If 'mode' is omitted, it simply returns the current sound mode. " +
                            "Use this when the user asks to silence the phone, turn volume on, or check the ringer status.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("mode") {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("normal")
                                    add("vibrate")
                                    add("silent")
                                })
                                put("description", "The desired mode: 'normal' (ring), 'vibrate', or 'silent'. Leave empty to just check the current mode.")
                            }
                        }
                        putJsonArray("required") {} // Mode is optional
                    }
                )
            ),

            Tool(
                type = "function",
                function = FunctionTool(
                    name = "open_app",
                    description = "Launches an installed application or a specific Android Settings page. " +
                            "1. For apps: Provide 'app_name' (e.g., 'Spotify') or 'package_name'. " +
                            "2. For Settings: Provide 'settings_action' (e.g., 'android.provider.Settings.ACTION_WIFI_SETTINGS'). " +
                            "If opening a settings page, do not provide app_name.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("app_name") {
                                put("type", "string")
                                put("description", "Common name of the app to launch (e.g., 'Chrome', 'Maps').")
                            }
                            putJsonObject("package_name") {
                                put("type", "string")
                                put("description", "Optional: Specific package ID (e.g., 'com.whatsapp').")
                            }
                            putJsonObject("settings_action") {
                                put("type", "string")
                                put("description", "Optional: Android Settings Intent Action string (e.g., 'android.provider.Settings.ACTION_SECURITY_SETTINGS' for Lock Screen settings). Use this if the user asks for a specific settings page.")
                            }
                        }
                        putJsonArray("required") {} // No single required param, logic handles the combo
                    }
                )
            ),

// NEW TOOL
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "search_list_apps",
                    description = "Lists installed applications that match a search query. Returns the App Label and Package Name. Use this to find the exact package name if 'open_app' fails to find an app by name.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "Search term to filter apps (e.g., 'chrome', 'cromite'). Searches both app names and package names.")
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("query")) }
                    }
                )
            ),
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "process_plus_code",
                    description = "A utility to convert between Plus Codes and geographic coordinates. Use 'encode' to turn lat/long into a Plus Code, or 'decode' to turn a Plus Code back into coordinates.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("action") {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("encode")
                                    add("decode")
                                })
                                put("description", "The operation to perform: 'encode' or 'decode'.")
                            }
                            putJsonObject("latitude") {
                                put("type", "number")
                                put("description", "Latitude. Required for 'encode'.")
                            }
                            putJsonObject("longitude") {
                                put("type", "number")
                                put("description", "Longitude. Required for 'encode'.")
                            }
                            putJsonObject("plus_code") {
                                put("type", "string")
                                put("description", "The Plus Code string. Required for 'decode'.")
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("action")) }
                    }
                )
            ),
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "wait",
                    description = "Pauses execution for a specified number of seconds. Use this when the user asks you to wait, delay, or perform an action repeatedly over time (e.g., 'check every minute', 'wait 30 seconds', 'do this for each of the next 4 minutes'). Minimum: 10 seconds, Maximum: 600 seconds (10 minutes).",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("seconds") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Number of seconds to wait. Must be between 10 and 600 (10 minutes)."
                                )
                                put("minimum", 10)
                                put("maximum", 600)
                            }
                            putJsonObject("reason") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional: Brief description of why the wait is needed (e.g., 'Waiting before next check', 'Delay before repeating search')."
                                )
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("seconds")) }
                    }
                )
            ),

            Tool(
                type = "function",
                function = FunctionTool(
                    name = "create_folder",
                    description = "Creates a new subfolder in the Download/gradation workspace. Use this when the user explicitly asks to create a folder or organize files into a new directory.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("folder_path") {
                                put("type", "string")
                                put("description", "The name or relative path of the folder to create (e.g., 'Archives' or 'Projects/Web').")
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("folder_path")) }
                    }
                )
            ),

                    Tool(
                type = "function",
                function = FunctionTool(
                    name = "set_timer",
                    description = "Set a timer for a duration specified in minutes. Optionally provide a title to label the timer.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("minutes") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "The total duration of the timer in minutes (e.g., 5 for '5 minutes', 142 for '2 hours and 22 minutes')."
                                )
                            }
                            putJsonObject("title") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional title or label for the timer (e.g., 'Pomodoro', 'Workout'). If not provided, defaults to 'Timer'."
                                )
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("minutes")) }
                    }
                )
            ),
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "find_nearby_places",
                    description = "Finds businesses, landmarks, and points of interest using Brave Place Search. You can search near the user's current coordinates (use get_location first) OR by a location name (e.g., 'missoula mt united states', 'tokyo japan'). Use this for any geographic place lookup: restaurants, hotels, landmarks, etc. Prefer this over brave_search when the query is about finding physical places. For US locations use format: 'city state country' (e.g., 'missoula mt united states'). For non-US: 'city country' (e.g., 'tokyo japan').",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "What to look for (e.g., 'coffee shops', 'hospital', 'mcdonalds'). Omit for general area exploration.")
                            }
                            putJsonObject("location") {
                                put("type", "string")
                                put("description", "Location name as a geographic anchor. US: 'city state country' (e.g., 'missoula mt united states'). Non-US: 'city country' (e.g., 'tokyo japan'). Case-insensitive, no commas needed. Use this when you don't have coordinates.")
                            }
                            putJsonObject("latitude") {
                                put("type", "number")
                                put("description", "Latitude of the search center. Use instead of 'location' when you have coordinates (e.g., from get_location).")
                            }
                            putJsonObject("longitude") {
                                put("type", "number")
                                put("description", "Longitude of the search center. Use instead of 'location' when you have coordinates (e.g., from get_location).")
                            }
                            putJsonObject("radius") {
                                put("type", "integer")
                                put("description", "Search radius in meters. Default is 5000. Under 20000 is best for 'near me' queries. Only used with coordinates.")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("query"))
                        }
                    }
                )
            ),
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "brave_news",
                    description = "Search a dedicated news index for recent articles from trusted outlets worldwide. Returns article titles, publishers, publication ages, and summaries. Use this for breaking news, current events, recent developments, and anything time-sensitive. For general web research use brave_search instead.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "The news search query (1-400 chars, max 50 words). Be specific and concise.")
                            }
                            putJsonObject("freshness") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Time filter for articles. Valid values: 'pd' (past day), 'pw' (past week), 'pm' (past month), 'py' (past year), or a date range like '2024-01-01to2024-06-30'. Leave blank for no time filter. Strongly recommended for news queries."
                                )
                            }
                            putJsonObject("count") {
                                put("type", "integer")
                                put("description", "Number of articles to return, 1-50. Default 20.")
                            }
                            putJsonObject("safesearch") {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("off")
                                    add("moderate")
                                    add("strict")
                                })
                                put("description", "Adult content filter. Default 'moderate'.")
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("query")) }
                    }
                )
            ),
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "get_location",
                    description = "Gets the user's current precise location, including Plus Code, latitude/longitude, timestamp, accuracy, and map links (Apple, Google, OpenStreetMap). Use when the user asks where they are, to share their location, or for any task requiring their current coordinates.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                        putJsonArray("required") {}
                    }
                )
            ),
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "get_current_datetime",
                    description = "Gets the current date and time including day of week, date, time with seconds, and UTC offset. Returns (JSON string) both human-readable strings and structured numeric data, plus ISO 8601 formatted datetime with UTC offset",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            // No parameters needed - gets current device time
                        }
                        putJsonArray("required") {
                            // Empty - no required parameters
                        }
                    }
                )
            ),
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "start_navigation",
                    description = "Launches Google Maps turn-by-turn navigation to a specified destination. You can specify the mode of transportation and things to avoid (like tolls or highways).",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("destination") {
                                put("type", "string")
                                put(
                                    "description",
                                    "The exact address, place name, or latitude/longitude coordinates to navigate to."
                                )
                            }
                            putJsonObject("mode") {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("d")
                                    add("w")
                                    add("b")
                                    add("t")
                                })
                                put(
                                    "description",
                                    "Transportation mode: 'd' (driving - default), 'w' (walking), 'b' (bicycling), 't' (transit)."
                                )
                            }
                            putJsonObject("avoid") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Features to avoid, separated by a pipe character '|'. Options are 'tolls', 'highways', 'ferries' (e.g., 'tolls' or 'tolls|highways')."
                                )
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("destination"))
                        }
                    }
                )
            ),

            Tool(
                type = "function",
                function = FunctionTool(
                    name = "set_alarm",
                    description = "Sets an alarm at a 24-hour time (hour 0-23, minute 0-59). A wrong alarm is worse than a question, so if the user gave no AM/PM or time of day, ask which they mean before calling.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("hour") {
                                put("type", "integer")
                                put("description", "The hour for the alarm, in 24-hour format (0-23). Only provided after user has clarified AM/PM if it was ambiguous.")
                            }
                            putJsonObject("minutes") {
                                put("type", "integer")
                                put("description", "The minute for the alarm (0-59).")
                            }
                            putJsonObject("message") {
                                put("type", "string")
                                put("description", "An optional message for the alarm.")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("hour"))
                            add(JsonPrimitive("minutes"))
                        }
                    }
                )
            ),
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "delete_files",
                    description = "Deletes one or more files(up to 9) from the Download/gradation workspace folder. Use this when the user wants to remove files.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("filepaths") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "string")
                                }
                                put(
                                    "description",
                                    "List of file paths to delete. Always provide as an array, even if deleting just one file. " +
                                            "Paths must be relative to the workspace root (e.g. ['notes.txt', 'Skills/draft.json'])."
                                )
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("filepaths")) }
                    }
                )
            ),

            Tool(
                type = "function",
                function = FunctionTool(
                    name = "open_file",
                    description = "Opens an existing file from the Download/gradation workspace using the system's default app (e.g., opens PDFs in a PDF viewer, images in gallery). Use this when the user wants to view a file.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("filepath") {
                                put("type", "string")
                                put("description", "The relative path of the file to open (e.g., 'document.pdf' or 'Skills/image.png').")
                            }
                            putJsonObject("mimetype") {
                                put("type", "string")
                                put("description", "Optional MIME type hint (e.g., 'application/pdf', 'image/png'). If not provided, the system will infer from file extension.")
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("filepath")) }
                    }
                )
            ),
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "brave_search",
                    description = "Search the web and get pre-extracted page content optimized for AI. Returns actual text chunks from relevant pages (not just snippets), including tables, code, and structured data. Use this for factual questions, research, how-tos, technical questions, and any query where you need real content to answer from. For breaking news use brave_news. For finding physical places use brave_places.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "The search query (1-400 chars, max 50 words). Be specific and concise.")
                            }
                            putJsonObject("freshness") {
                                put("type", "string")
                                put("description", "Time filter. 'pd' (past day), 'pw' (past week), 'pm' (past month), 'py' (past year), or 'YYYY-MM-DDtoYYYY-MM-DD'. Leave blank for no filter.")
                            }
                            putJsonObject("count") {
                                put("type", "integer")
                                put("description", "Max URLs to consider, 1-50. Default 10. Use 5 for simple factual lookups, 50 for deep research.")
                            }
                            putJsonObject("max_tokens") {
                                put("type", "integer")
                                put("description", "Approximate max tokens of extracted content to return, 1024-32768. Default 4096. Use 2048 for simple factual lookups, 8192 for standard research, 16384+ for deep multi-source research. Higher values = more context but slower and more expensive.")
                            }

                            putJsonObject("threshold") {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("strict")
                                    add("balanced")
                                    add("lenient")
                                    add("disabled")
                                })
                                put("description", "Relevance threshold. 'strict' for precision (fewer, more relevant), 'lenient' for recall (more, possibly less relevant). Leave blank for API default.")
                            }
                            putJsonObject("safesearch") {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("off")
                                    add("moderate")
                                    add("strict")
                                })
                                put("description", "Adult content filter. Default 'moderate'.")
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("query")) }
                    }
                )
            ),
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "add_calendar_event",
                    description = "Adds an event to the user's calendar. Provide a title and start date/time; the AI will populate optional fields like location, description, all-day status, and end time as needed (e.g., default end to 1 hour after start for timed events, or next day for all-day). Dates/times should be in ISO 8601 format (e.g., '2023-10-05T14:30:00' for Oct 5, 2023 at 2:30 PM). Call get_current_datetime first when the event is relative to now (\"tomorrow\", \"in 2 hours\").",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("title") {
                                put("type", "string")
                                put("description", "The title of the calendar event.")
                            }
                            putJsonObject("location") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional location for the event (e.g., 'Office' or 'Online'). AI can populate if not provided."
                                )
                            }
                            putJsonObject("description") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional description or notes for the event. AI can populate if not provided."
                                )
                            }
                            putJsonObject("allDay") {
                                put("type", "boolean")
                                put(
                                    "description",
                                    "Whether the event is all-day (true) or timed (false, default). If true, ignores specific times in date/time strings."
                                )
                            }
                            putJsonObject("startDateTime") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Start date and time in ISO 8601 format (e.g., '2023-10-05T14:30:00'). Required; AI can infer/populate if user provides partial info."
                                )
                            }
                            putJsonObject("endDateTime") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional end date and time in ISO 8601 format. If not provided, defaults to 1 hour after start (timed events) or next day (all-day events). AI can populate."
                                )
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("title"))
                            add(JsonPrimitive("startDateTime"))
                        }
                    }
                )
            ),

            Tool(
                type = "function",
                function = FunctionTool(
                    name = "list_gradation_files",
                    description = "Lists all files and subfolders in the Download/gradation folder or a specified subfolder. Returns names with relative paths (e.g., 'Skills/data.json'). Use this to find files before reading them.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Optional subfolder path to list (e.g., 'Skills'). Leave empty or omit to list the root Download/gradation folder.")
                            }
                        }
                        putJsonArray("required") {} // Path is optional
                    }
                )
            ),
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "copy_file",
                    description = "Copies an existing file to a new location or renames it. You can use this to create backups. If the destination file already exists, a timestamp will be appended to the new filename to prevent overwriting (e.g., 'notes.bak' becomes 'notes_20231027_143000.bak').",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("source_filepath") {
                                put("type", "string")
                                put("description", "The relative path of the existing file to copy/rename (e.g., 'sharelocation.md').")
                            }
                            putJsonObject("destination_path") {
                                put("type", "string")
                                put("description", "The desired new path. Can be a new filename (e.g., 'sharelocation.bak') or a path with subfolder (e.g., 'Backups/sharelocation.md').")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("source_filepath"))
                            add(JsonPrimitive("destination_path"))
                        }
                    }
                )
            ),

            Tool(
                type = "function",
                function = FunctionTool(
                    name = "edit_file",
                    description = "Overwrites an existing file in the Download/gradation workspace with new content. Use this when the user wants to update, modify, or edit an existing file. The whole file is replaced, so pass its complete new content, not a diff.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("filepath") {
                                put("type", "string")
                                put("description", "The relative path of the existing file to overwrite (e.g., 'notes.txt' or 'Skills/data.json').")
                            }
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "The COMPLETE new content to write to the file.")
                            }
                            putJsonObject("mimetype") {
                                put("type", "string")
                                put("description", "Optional MIME type (e.g., 'text/plain', 'application/json'). If omitted, it attempts to keep the original type or defaults to text/plain.")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("filepath"))
                            add(JsonPrimitive("content"))
                        }
                    }
                )
            ),
            Tool(
                type = "function",
                function = FunctionTool(
                    name = "read_gradation_file",
                    description = "Reads the contents of a single text file from the Download/gradation workspace. Only reads text-based files (e.g., .txt, .md, .json). Use list_gradation_files first to see available files and their paths.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("filepath") {
                                put("type", "string")
                                put(
                                    "description",
                                    "The relative path of the file to read (e.g., 'notes.txt' or 'Skills/data.json')."
                                )
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("filepath")) }
                    }
                )
            ),
            // Add more tools here as your app grows – the filtering logic below stays the same!
        )

        // Handle prefs for enabling/disabling tools
        val hasStoredPrefs = sharedPreferencesHelper.hasEnabledToolsStored()

        if (!hasStoredPrefs) return emptyList()

        val enabled = ToolItem.effectiveEnabledTools(sharedPreferencesHelper.getEnabledTools())
        // The calendar tool needs "now" to resolve relative dates. The clock lives in its own tool, not a
        // timestamp in the description, so the tools array stays byte-identical and cacheable across turns.
        val enabledToolNames = if ("add_calendar_event" in enabled) enabled + "get_current_datetime" else enabled
        return allTools.filter { tool ->
            tool.function?.name in enabledToolNames
        }
    }

    private fun destructiveToolsBlockedMessage(): String {
        val message = "Destructive tool blocked — enable in Settings"
        _toastUiEvent.postValue(Event(message))
        return message
    }

    /** Decode a tool's JSON arguments. A bad payload becomes [onError]; the tool body stays the same. */
    private suspend inline fun withToolArgs(
        raw: String,
        onError: (Exception) -> String,
        block: suspend (JsonObject) -> String,
    ): String = try {
        block(json.decodeFromString(raw))
    } catch (e: Exception) {
        onError(e)
    }

    internal suspend fun handleToolCalls(
        toolCalls: List<ToolCall>,
        thinkingMessage: FlexibleMessage?
    ) {
        if (toolCallsHandledForTurn) {

            return  // Guard: Skip if already handled in this turn
        }
        toolCallsHandledForTurn = true
        toolRecursionDepth++

        if (toolRecursionDepth > 8) {  // Prevent infinite recursion
            withContext(Dispatchers.Main) {
                updateMessages { list ->
                    putAssistantMessage(
                        list,
                        thinkingMessage,
                        FlexibleMessage(
                            role = "assistant",
                            content = JsonPrimitive("**Error:**\n---\nTool recursion limit reached.")
                        )
                    )
                }
            }
            return
        }

        // Deduplicate tool calls: Group by name + arguments and execute only once per unique combo
        val uniqueToolCalls = toolCalls.groupBy { "${it.function.name}:${it.function.arguments}" }
            .map { it.value.first() }
        val toolResults = mutableListOf<FlexibleMessage>()

        for (toolCall in uniqueToolCalls) {  // Now looping over uniques only
            val result: String = when (toolCall.function.name) {
                "set_timer" -> withToolArgs(toolCall.function.arguments, {
                    val error = "Failed to set timer: Error parsing arguments."
                    _toastUiEvent.postValue(Event(error))
                    error
                }) { arguments ->
                    val minutes = arguments["minutes"]?.jsonPrimitive?.intOrNull
                    val title = arguments["title"]?.jsonPrimitive?.contentOrNull

                    if (minutes != null && minutes > 0) {
                        val context = application.applicationContext
                        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                            putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            putExtra(AlarmClock.EXTRA_MESSAGE, title ?: "Timer")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        val displayTitle = title ?: "Timer"
                        _toastUiEvent.postValue(Event("Timer '$displayTitle' set for $minutes minutes."))
                        "Timer '$displayTitle' was set successfully for $minutes minutes."
                    } else {
                        val error = "Failed to set timer: Invalid minutes value."
                        _toastUiEvent.postValue(Event(error))
                        error
                    }
                }
                "open_app" -> withToolArgs(toolCall.function.arguments, {
                    "Error in open_app: ${it.localizedMessage}"
                }) { arguments ->
                    val context = application.applicationContext
                    val pm = context.packageManager
                    val settingsAction = arguments["settings_action"]?.jsonPrimitive?.contentOrNull
                    val appName = arguments["app_name"]?.jsonPrimitive?.contentOrNull?.trim()
                    val packageName = arguments["package_name"]?.jsonPrimitive?.contentOrNull

                    if (!settingsAction.isNullOrBlank()) {
                        if (!ToolExecutorPolicy.isAllowedSettingsAction(settingsAction)) {
                            "Error: settings_action '$settingsAction' is not allowed."
                        } else {
                            try {
                                val intent = Intent(settingsAction).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                }
                                context.startActivity(intent)
                                "Successfully opened settings page: $settingsAction"
                            } catch (e: Exception) {
                                "Error opening settings page '$settingsAction'. It might not exist on this device. Error: ${e.message}"
                            }
                        }
                    } else {
                        var resolvedPackage: String? = null
                        if (!packageName.isNullOrBlank()) {
                            resolvedPackage = try {
                                pm.getPackageInfo(packageName, 0).packageName
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (resolvedPackage == null && !appName.isNullOrBlank()) {
                            val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
                            val apps = pm.queryIntentActivities(mainIntent, 0)
                            val bestMatch = apps.map { it to it.loadLabel(pm).toString() }
                                .filter { (_, label) -> label.contains(appName, ignoreCase = true) }
                                .minByOrNull { (_, label) ->
                                    when {
                                        label.equals(appName, ignoreCase = true) -> 0
                                        label.startsWith(appName, ignoreCase = true) -> 1
                                        else -> 2
                                    }
                                }?.first
                            resolvedPackage = bestMatch?.activityInfo?.packageName
                        }
                        if (resolvedPackage != null) {
                            val launchIntent = pm.getLaunchIntentForPackage(resolvedPackage)
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                                "Successfully launched '$appName' (Package: $resolvedPackage)."
                            } else {
                                "Found package '$resolvedPackage', but it has no launcher activity."
                            }
                        } else {
                            "Could not find an app named '$appName'. Try using 'search_list_apps' to find the package name."
                        }
                    }
                }

                "search_list_apps" -> withToolArgs(toolCall.function.arguments, { "Error listing apps: ${it.message}" }) { arguments ->
                    val query = arguments["query"]?.jsonPrimitive?.contentOrNull ?: ""
                    val context = application.applicationContext
                    val pm = context.packageManager

                    val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
                    val apps = pm.queryIntentActivities(mainIntent, 0)

                    val results = buildJsonArray {
                        apps.forEach { resolveInfo ->
                            val label = resolveInfo.loadLabel(pm).toString()
                            val pkg = resolveInfo.activityInfo.packageName
                            if (label.contains(query, ignoreCase = true) || pkg.contains(query, ignoreCase = true)) {
                                add(buildJsonObject {
                                    put("label", JsonPrimitive(label))
                                    put("package", JsonPrimitive(pkg))
                                })
                            }
                        }
                    }

                    if (results.isNotEmpty()) {
                        "Found matching apps: ${results.toString()}"
                    } else {
                        "No apps found matching '$query'."
                    }
                }
                "get_current_datetime" -> {
                    try {
                        val now = Date()
                        val calendar = Calendar.getInstance()

                        // Get day of week
                        val dayFormat = SimpleDateFormat("EEEE", Locale.getDefault())
                        val dayOfWeek = dayFormat.format(now)

                        // Get date components
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        val fullFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

                        // Get timezone info
                        val timeZone = calendar.timeZone
                        val utcOffset = timeZone.getOffset(now.time) / 1000 / 60 // offset in minutes
                        val utcOffsetHours = utcOffset / 60
                        val utcOffsetMinutes = Math.abs(utcOffset) % 60
                        val utcOffsetSign = if (utcOffset >= 0) "+" else "-"
                        val utcOffsetString = String.format(java.util.Locale.US, "%s%02d:%02d", utcOffsetSign, Math.abs(utcOffsetHours), utcOffsetMinutes)

                        // Build structured response
                        val resultJson = buildJsonObject {
                            put("day_of_week", JsonPrimitive(dayOfWeek))
                            put("date", JsonPrimitive(dateFormat.format(now)))
                            put("time", JsonPrimitive(timeFormat.format(now)))
                            put("datetime_iso", JsonPrimitive(fullFormat.format(now) + utcOffsetString))
                            put("year", JsonPrimitive(calendar.get(Calendar.YEAR)))
                            put("month", JsonPrimitive(calendar.get(Calendar.MONTH) + 1)) // Calendar months are 0-indexed
                            put("day_of_month", JsonPrimitive(calendar.get(Calendar.DAY_OF_MONTH)))
                            put("hour", JsonPrimitive(calendar.get(Calendar.HOUR_OF_DAY)))
                            put("minute", JsonPrimitive(calendar.get(Calendar.MINUTE)))
                            put("second", JsonPrimitive(calendar.get(Calendar.SECOND)))
                            put("timezone_id", JsonPrimitive(timeZone.id))
                            put("timezone_display", JsonPrimitive(timeZone.displayName))
                            put("utc_offset", JsonPrimitive(utcOffsetString))
                            put("is_daylight_time", JsonPrimitive(timeZone.inDaylightTime(now)))
                        }

                        resultJson.toString()
                    } catch (e: Exception) {
                        "Error getting date/time: ${e.message}"
                    }
                }
                "set_sound_mode" -> withToolArgs(toolCall.function.arguments, {
                    "Error changing sound mode: ${it.localizedMessage}"
                }) { arguments ->
                    val audioManager = application.applicationContext
                        .getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val targetMode = arguments["mode"]?.jsonPrimitive?.contentOrNull
                    fun currentMode() = when (audioManager.ringerMode) {
                        AudioManager.RINGER_MODE_NORMAL -> "normal"
                        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                        AudioManager.RINGER_MODE_SILENT -> "silent"
                        else -> "unknown"
                    }
                    if (targetMode.isNullOrBlank()) {
                        "Current sound mode is: ${currentMode()}."
                    } else {
                        val newMode = when (targetMode.lowercase()) {
                            "normal" -> AudioManager.RINGER_MODE_NORMAL
                            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                            "silent" -> AudioManager.RINGER_MODE_SILENT
                            else -> -1
                        }
                        if (newMode != -1) {
                            audioManager.ringerMode = newMode
                            "Sound mode changed to '${targetMode.lowercase()}'. Current mode is now: ${currentMode()}."
                        } else {
                            "Error: Invalid mode specified. Use 'normal', 'vibrate', or 'silent'."
                        }
                    }
                }
                "set_alarm" -> withToolArgs(toolCall.function.arguments, {
                    "Failed to set alarm: Error parsing arguments."
                }) { arguments ->
                    val hour = arguments["hour"]?.jsonPrimitive?.intOrNull
                    val minutes = arguments["minutes"]?.jsonPrimitive?.intOrNull
                    val message = arguments["message"]?.jsonPrimitive?.content

                    if (hour != null && hour in 0..23 && minutes != null && minutes in 0..59) {
                        val context = application.applicationContext
                        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, hour)
                            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                            message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        "Alarm was set successfully for $hour:$minutes."
                    } else {
                        "Failed to set alarm: Invalid hour or minutes."
                    }
                }
                "start_navigation" -> withToolArgs(toolCall.function.arguments, {
                    "Error launching navigation: ${it.message}"
                }) { arguments ->
                    val destination = arguments["destination"]?.jsonPrimitive?.content
                    val mode = arguments["mode"]?.jsonPrimitive?.content ?: "d"
                    val avoid = arguments["avoid"]?.jsonPrimitive?.content

                    if (destination.isNullOrBlank()) {
                        "Error: destination is required to start navigation."
                    } else {
                        val context = application.applicationContext
                        val encodedDestination = Uri.encode(destination)
                        var uriString = "google.navigation:q=$encodedDestination&mode=$mode"
                        if (!avoid.isNullOrBlank()) uriString += "&avoid=$avoid"

                        val mapIntent = Intent(Intent.ACTION_VIEW, uriString.toUri()).apply {
                            setPackage("com.google.android.apps.maps")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (mapIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(mapIntent)
                            "Navigation started to $destination."
                        } else {
                            val fallbackIntent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=$encodedDestination".toUri()).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            if (fallbackIntent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(fallbackIntent)
                                "Google Maps not found. Launched default map app for $destination."
                            } else {
                                "Error: No map application found on the device."
                            }
                        }
                    }
                }
                "edit_file" -> {
                    if (!sharedPreferencesHelper.getAllowDestructiveTools()) {
                        destructiveToolsBlockedMessage()
                    } else withToolArgs(toolCall.function.arguments, {
                        Log.e("ToolCall", "Error executing edit_file", it)
                        "Error editing file: ${it.message}"
                    }) { arguments ->
                        val filepath = arguments["filepath"]?.jsonPrimitive?.contentOrNull
                        val content = arguments["content"]?.jsonPrimitive?.contentOrNull
                        val mimeType = arguments["mimetype"]?.jsonPrimitive?.contentOrNull

                        if (filepath.isNullOrBlank() || content == null) {
                            "Error: filepath and content are required."
                        } else {
                            editFileViaSaf(filepath, content, mimeType)
                        }
                    }
                }

                "copy_file" -> withToolArgs(toolCall.function.arguments, {
                    Log.e("ToolCall", "Error executing copy_file", it)
                    "Error copying file: ${it.message}"
                }) { arguments ->
                    val sourcePath = arguments["source_filepath"]?.jsonPrimitive?.contentOrNull
                    val destPath = arguments["destination_path"]?.jsonPrimitive?.contentOrNull

                    if (sourcePath.isNullOrBlank() || destPath.isNullOrBlank()) {
                        "Error: Both source_filepath and destination_path are required."
                    } else {
                        copyFileViaSaf(sourcePath, destPath)
                    }
                }
                "process_plus_code" -> withToolArgs(toolCall.function.arguments, {
                    "Error parsing plus code arguments: ${it.message}"
                }) { arguments ->
                    val action = arguments["action"]?.jsonPrimitive?.content
                    val lat = arguments["latitude"]?.jsonPrimitive?.doubleOrNull
                    val lng = arguments["longitude"]?.jsonPrimitive?.doubleOrNull
                    val plusCode = arguments["plus_code"]?.jsonPrimitive?.contentOrNull
                    performPlusCodeConversion(action, lat, lng, plusCode)
                }
                "create_folder" -> withToolArgs(toolCall.function.arguments, {
                    "Error creating folder: ${it.message}"
                }) { arguments ->
                    val folderPath = arguments["folder_path"]?.jsonPrimitive?.content
                    if (folderPath != null) {
                        createFolderInWorkspaceViaMediaStore(folderPath)
                        "Folder '$folderPath' created successfully."
                    } else {
                        "Error: No folder_path provided."
                    }
                }

                "add_calendar_event" -> withToolArgs(toolCall.function.arguments, {
                    val error = "Failed to add calendar event: ${it.message}"
                    _toastUiEvent.postValue(Event(error))
                    error
                }) { arguments ->
                        val title = arguments["title"]?.jsonPrimitive?.content ?: ""
                        val location = arguments["location"]?.jsonPrimitive?.content ?: ""
                        val description = arguments["description"]?.jsonPrimitive?.content ?: ""
                        val allDay = arguments["allDay"]?.jsonPrimitive?.booleanOrNull ?: false
                        val startDateTimeStr =
                            arguments["startDateTime"]?.jsonPrimitive?.content ?: ""
                        val endDateTimeStr = arguments["endDateTime"]?.jsonPrimitive?.content

                        if (title.isBlank() || startDateTimeStr.isBlank()) {
                            val error =
                                "Failed to add calendar event: Title and start date/time are required."
                            _toastUiEvent.postValue(Event(error))
                            error
                        } else {
                            val dateFormat =
                                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                            val startMillis = try {
                                dateFormat.parse(startDateTimeStr)?.time
                                    ?: throw Exception("Invalid start date/time format")
                            } catch (e: Exception) {
                                throw Exception("Failed to parse start date/time: ${e.message}")
                            }

                            val calendar = Calendar.getInstance()
                            calendar.timeInMillis = startMillis

                            val (dtStart, dtEnd) = if (allDay) {
                                // For all-day: Set to start of day, end to end of day (or next day if no end provided)
                                calendar.set(Calendar.HOUR_OF_DAY, 0)
                                calendar.set(Calendar.MINUTE, 0)
                                calendar.set(Calendar.SECOND, 0)
                                calendar.set(Calendar.MILLISECOND, 0)
                                val start = calendar.timeInMillis
                                val end = if (endDateTimeStr != null) {
                                    val endMillis = try {
                                        dateFormat.parse(endDateTimeStr)?.time
                                            ?: throw Exception("Invalid end date/time format")
                                    } catch (e: Exception) {
                                        throw Exception("Failed to parse end date/time: ${e.message}")
                                    }
                                    val endCal = Calendar.getInstance()
                                    endCal.timeInMillis = endMillis
                                    endCal.set(Calendar.HOUR_OF_DAY, 0)
                                    endCal.set(Calendar.MINUTE, 0)
                                    endCal.set(Calendar.SECOND, 0)
                                    endCal.set(Calendar.MILLISECOND, 0)
                                    endCal.timeInMillis
                                } else {
                                    start + (24 * 60 * 60 * 1000)  // Next day
                                }
                                Pair(start, end)
                            } else {
                                // For timed: Use exact times, default end to 1 hour after start
                                val start = calendar.timeInMillis
                                val end = if (endDateTimeStr != null) {
                                    try {
                                        dateFormat.parse(endDateTimeStr)?.time
                                            ?: throw Exception("Invalid end date/time format")
                                    } catch (e: Exception) {
                                        throw Exception("Failed to parse end date/time: ${e.message}")
                                    }
                                } else {
                                    start + (60 * 60 * 1000)  // 1 hour later
                                }
                                Pair(start, end)
                            }

                            val context = application.applicationContext
                            val intent = Intent(Intent.ACTION_INSERT).apply {
                                data = CalendarContract.Events.CONTENT_URI
                                putExtra(CalendarContract.Events.TITLE, title)
                                if (location.isNotBlank()) putExtra(
                                    CalendarContract.Events.EVENT_LOCATION,
                                    location
                                )
                                if (description.isNotBlank()) putExtra(
                                    CalendarContract.Events.DESCRIPTION,
                                    description
                                )
                                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
                                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, dtStart)
                                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, dtEnd)

                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            val eventSummary =
                                "Event '$title' added to calendar (${if (allDay) "all-day" else "timed"})."
                            _toastUiEvent.postValue(Event(eventSummary))
                            eventSummary
                        }
                }

                "make_file" -> withToolArgs(toolCall.function.arguments, {
                    "Error creating file: ${it.message}"
                }) { args ->
                    val filename = args["filename"]?.jsonPrimitive?.content ?: ""
                    val content = args["content"]?.jsonPrimitive?.content ?: ""
                    val mimeType = args["mimetype"]?.jsonPrimitive?.content ?: "text/plain"
                    val subfolder = args["subfolder"]?.jsonPrimitive?.contentOrNull ?: ""

                    if (filename.isBlank() || content.isBlank()) {
                        "Error: filename or content empty."
                    } else {
                        saveFileToOpenChatWorkspace(filename, content, mimeType, subfolder)
                        val displayPath = WorkspacePaths.displayPath(filename, subfolder)
                        _toastUiEvent.postValue(Event("File saved to Downloads: $displayPath"))
                        "File “$filename” successfully created in Downloads/$displayPath."
                    }
                }
                "delete_files" -> {
                    if (!sharedPreferencesHelper.getAllowDestructiveTools()) {
                        destructiveToolsBlockedMessage()
                    } else withToolArgs(toolCall.function.arguments, {
                        "Error deleting files: ${it.message}"
                    }) { arguments ->
                        val filepaths = parseFilepaths(arguments["filepaths"])
                        if (filepaths.isNotEmpty()) {
                            deleteFilesViaSaf(filepaths)
                        } else {
                            "Error: No filepaths provided."
                        }
                    }
                }
                "wait" -> withToolArgs(toolCall.function.arguments, {
                    "Error executing wait: ${it.message}"
                }) { arguments ->
                    val seconds = arguments["seconds"]?.jsonPrimitive?.intOrNull
                    val reason = arguments["reason"]?.jsonPrimitive?.contentOrNull

                    if (seconds != null && seconds in 10..600) {
                        val displayReason = if (!reason.isNullOrBlank()) " ($reason)" else ""
                        _toastUiEvent.postValue(Event("Waiting for $seconds seconds$displayReason..."))
                        delay((seconds * 1000L).milliseconds)
                        "Waited for $seconds seconds successfully$displayReason. The wait has completed."
                    } else {
                        "Error: Invalid seconds value. Must be between 10 and 600."
                    }
                }
                "find_nearby_places" -> withToolArgs(toolCall.function.arguments, {
                    "Error finding nearby places: ${it.message}"
                }) { arguments ->
                    val query = arguments["query"]?.jsonPrimitive?.contentOrNull ?: ""
                    val location = arguments["location"]?.jsonPrimitive?.contentOrNull
                    val latitude = arguments["latitude"]?.jsonPrimitive?.doubleOrNull
                    val longitude = arguments["longitude"]?.jsonPrimitive?.doubleOrNull
                    val radius = arguments["radius"]?.jsonPrimitive?.intOrNull ?: 5000

                    if (location.isNullOrBlank() && (latitude == null || longitude == null)) {
                        "Error: Provide either a 'location' name or 'latitude'/'longitude' coordinates."
                    } else {
                        searchNearbyPlaces(query, latitude, longitude, radius, location)
                    }
                }
                "get_location" -> {
                    try {
                        val context = application.applicationContext
                        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                        if (!hasFine && !hasCoarse) {
                            "Permission denied: Location permission has not been granted to the app. Please ask the user to grant location permission in app settings."
                        } else {
                            fetchCurrentLocation()
                        }
                    } catch (e: Exception) {
                        "Error checking location permissions: ${e.message}"
                    }
                }
                "brave_search" -> withToolArgs(toolCall.function.arguments, {
                    "Error: Failed to search with Brave LLM Context – ${it.message}"
                }) { arguments ->
                    val query = arguments["query"]?.jsonPrimitive?.content
                    val freshness = arguments["freshness"]?.jsonPrimitive?.contentOrNull
                    val count = arguments["count"]?.jsonPrimitive?.intOrNull ?: 10
                    val maxTokens = arguments["max_tokens"]?.jsonPrimitive?.intOrNull ?: 4096
                    val threshold = arguments["threshold"]?.jsonPrimitive?.contentOrNull
                    val safesearch = arguments["safesearch"]?.jsonPrimitive?.contentOrNull ?: "moderate"

                    if (query.isNullOrBlank()) {
                        "Error: No search query provided."
                    } else {
                        searchBraveLlmContext(query, freshness, count, maxTokens, threshold, safesearch)
                    }
                }
                "brave_news" -> withToolArgs(toolCall.function.arguments, {
                    "Error: Failed to search Brave News – ${it.message}"
                }) { arguments ->
                    val query = arguments["query"]?.jsonPrimitive?.content
                    val freshness = arguments["freshness"]?.jsonPrimitive?.contentOrNull
                    val count = arguments["count"]?.jsonPrimitive?.intOrNull ?: 20
                    val safesearch = arguments["safesearch"]?.jsonPrimitive?.contentOrNull ?: "moderate"

                    if (query.isNullOrBlank()) {
                        "Error: No news query provided."
                    } else {
                        searchBraveNews(query, freshness, count, safesearch)
                    }
                }
                "open_file" -> withToolArgs(toolCall.function.arguments, {
                    "Error opening file: ${it.message}"
                }) { arguments ->
                    val filepath = arguments["filepath"]?.jsonPrimitive?.content
                    val mimeType = arguments["mimetype"]?.jsonPrimitive?.content
                    if (filepath != null) {
                        openFileViaSaf(filepath, mimeType)
                    } else {
                        "Error: No filepath provided."
                    }
                }
                "list_oxproxion_files", "list_grokion_files", "list_gradation_files" -> withToolArgs(
                    toolCall.function.arguments,
                    { "Error listing files: ${it.message}" },
                ) { arguments ->
                    listOpenChatFilesViaSaf(arguments["path"]?.jsonPrimitive?.contentOrNull ?: "")
                }
                "read_oxproxion_file", "read_grokion_file", "read_gradation_file" -> withToolArgs(
                    toolCall.function.arguments,
                    { "Error reading file: ${it.message}" },
                ) { arguments ->
                    val filepath = arguments["filepath"]?.jsonPrimitive?.content
                    if (filepath != null) {
                        readOpenChatFileViaSaf(filepath)
                    } else {
                        "Error: No filepath provided."
                    }
                }

                else -> "Error: Unknown tool call"
            }
            toolResults.add(
                FlexibleMessage(
                    role = "tool",
                    content = JsonPrimitive(result),
                    toolCallId = toolCall.id
                )
            )
        }
        withContext(Dispatchers.Main) {
            updateMessages { it.addAll(toolResults) }
        }
        // All tool calls now continue the conversation to report their status.
        val messagesForApi = _chatMessages.value?.toMutableList() ?: mutableListOf()
        val systemMessage = sharedPreferencesHelper.getSelectedSystemMessage().prompt
        if (messagesForApi.isEmpty() || messagesForApi[0].role != "system") {
            messagesForApi.add(
                0,
                FlexibleMessage(role = "system", content = JsonPrimitive(systemMessage))
            )
            // Log.d("ToolDebug", "Re-added system message to continuation payload")
        }
      //  messagesForApi.addAll(toolResults)
        continueConversation(messagesForApi)
    }

    private suspend fun fetchCurrentLocation(): String {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val context = application.applicationContext
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

                val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

                if (!isGpsEnabled && !isNetworkEnabled) {
                    continuation.resume("Location is disabled on the device. Please enable it in settings.")
                    return@suspendCancellableCoroutine
                }

                val handler = Handler(Looper.getMainLooper())
                val timeoutMillis = 30000L
                val desiredAccuracyMeters = 10f

                val locationListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (location.hasAccuracy() && location.accuracy <= desiredAccuracyMeters) {
                            cleanup()
                            continuation.resume(buildLocationResult(location))
                        }
                        // If accuracy > 10m, we just keep listening (like old app)
                    }

                    override fun onProviderDisabled(provider: String) {
                        if (provider == LocationManager.GPS_PROVIDER && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                            // GPS turned off mid-search, fallback handled by timeout
                        } else if (!locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                            cleanup()
                            continuation.resume("Location provider was disabled during search.")
                        }
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                    private fun cleanup() {
                        handler.removeCallbacksAndMessages(null)
                        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                    }
                }

                val timeoutRunnable = Runnable {
                    try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}

                    // Fallback to Network Provider (equivalent to your doLocation2())
                    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        try {
                            val lastNetLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                            if (lastNetLocation != null) {
                                continuation.resume(buildLocationResult(lastNetLocation))
                            } else {
                                continuation.resume("GPS timed out (accuracy not met) and no Network location was available.")
                            }
                        } catch (e: SecurityException) {
                            continuation.resume("GPS timed out and Network location permission was denied.")
                        }
                    } else {
                        continuation.resume("Location request timed out (accuracy not met within 40 seconds) and no fallback was available.")
                    }
                }

                handler.postDelayed(timeoutRunnable, timeoutMillis)

                try {
                    if (isGpsEnabled) {
                        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
                    } else if (isNetworkEnabled) {
                        // If GPS is off entirely, just use Network immediately
                        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener)
                    }
                } catch (e: SecurityException) {
                    handler.removeCallbacksAndMessages(null)
                    continuation.resume("Location permission denied during request.")
                }

                continuation.invokeOnCancellation {
                    handler.removeCallbacksAndMessages(null)
                    try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun buildLocationResult(location: Location): String {
        val plusCode = OpenLocationCode.encode(location.latitude, location.longitude)
        val lat = location.latitude.toString()
        val lon = location.longitude.toString()
        val lsds = location.provider.toString()
        val acc = location.accuracy.toString()

        val timeLong = try { location.time } catch (e: Exception) { System.currentTimeMillis() }
        val date = Date(timeLong)
        val dateFormat = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.getDefault())
        val formattedTime = dateFormat.format(date)

        val osm = "http://www.openstreetmap.org/?lat=$lat&lon=$lon&zoom=17"
        val apple1 = URLEncoder.encode("$lat,$lon", "UTF-8")
        val apple = "https://maps.apple.com/?q=$apple1"
        val google1 = URLEncoder.encode(plusCode, "UTF-8")
        val google = "https://maps.google.com/?q=$google1"

        return "My Location(via $lsds) @: $formattedTime:\n\nPlus Code: $plusCode\n\n$lat,$lon\n\n$apple\n\n$google\n\n$osm\n\naccuracy: $acc meters"
    }

    /**
     * Performs the conversion between Plus Codes and Coordinates.
     * Uses the explicit getter methods from the OpenLocationCode.CodeArea documentation.
     */
    private fun performPlusCodeConversion(
        action: String?,
        lat: Double?,
        lng: Double?,
        plusCode: String?
    ): String {
        return try {
            when (action) {
                "encode" -> {
                    if (lat != null && lng != null) {
                        // Static method: encode(double, double)
                        val code = OpenLocationCode.encode(lat, lng)
                        "Encoded Plus Code: $code"
                    } else {
                        "Error: 'encode' requires both 'latitude' and 'longitude' as numbers."
                    }
                }
                "decode" -> {
                    if (!plusCode.isNullOrBlank()) {
                        // Static method: decode(String) -> returns CodeArea object
                        val area = OpenLocationCode.decode(plusCode)

                        // Using the exact method names from the CodeArea documentation provided
                        val centerLat = area.centerLatitude
                        val centerLng = area.centerLongitude

                        "Decoded Coordinates: Latitude $centerLat, Longitude $centerLng"
                    } else {
                        "Error: 'decode' requires a 'plus_code' string."
                    }
                }
                else -> "Error: Invalid action. Please use 'encode' or 'decode'."
            }
        } catch (e: IllegalArgumentException) {
            // This happens if the Plus Code string is malformed
            "Error: The provided Plus Code is invalid."
        } catch (e: Exception) {
            "Error during conversion: ${e.message}"
        }
    }

    /** The granted workspace tree. A missing grant and a tree that will not open stay distinct. */
    private fun openWorkspaceRoot(): DocumentFile? {
        val uri = sharedPreferencesHelper.getSafFolderUri() ?: return null
        return DocumentFile.fromTreeUri(application.applicationContext, uri.toUri())
    }

    private fun workspaceClosed(missingPermission: String, cannotOpen: String): String =
        if (sharedPreferencesHelper.getSafFolderUri() == null) missingPermission else cannotOpen

    private fun unsafeWorkspacePath(path: String): Boolean =
        path.contains('\\') || path.contains("..")

    /** Parent of the last path segment. [second] is the directory name that was missing. */
    private fun DocumentFile.directoryHolding(parts: List<String>): Pair<DocumentFile?, String?> {
        var current = this
        for (i in 0 until parts.size - 1) {
            val name = parts[i]
            if (name.isBlank()) continue
            val next = current.findFile(name)
            if (next == null || !next.isDirectory) return null to name
            current = next
        }
        return current to null
    }

    /** File that read and open both look up, or the error those two already shared. */
    private inline fun withWorkspaceFile(filepath: String, block: (DocumentFile) -> String): String {
        if (unsafeWorkspacePath(filepath)) {
            return "Error: Invalid filepath. Backslashes and parent directories (..) are not allowed."
        }
        val root = openWorkspaceRoot() ?: return workspaceClosed(
            "Error: Folder permission not granted. Ask the user to grant folder access.",
            "Error: Could not access the workspace folder.",
        )
        val parts = filepath.split("/")
        val (dir, missing) = root.directoryHolding(parts)
        if (dir == null) return "Error: Directory '$missing' not found in path."
        val file = dir.findFile(parts.last()) ?: return "Error: File '$filepath' not found."
        if (!file.isFile) return "Error: '$filepath' is not a file."
        return block(file)
    }

    private suspend fun editFileViaSaf(filepath: String, newContent: String, mimeType: String?): String {
        return withContext(Dispatchers.IO) {
            // 1. Security Checks
            if (unsafeWorkspacePath(filepath)) {
                return@withContext "Error: Invalid filepath. Backslashes and parent directories (..) are not allowed."
            }

            try {
                val rootDocumentFile = openWorkspaceRoot()
                    ?: return@withContext workspaceClosed(
                        "Error: Folder permission not granted.",
                        "Error: Could not access workspace.",
                    )

                // 2. Locate the Existing File
                val pathParts = filepath.split("/")
                val filename = pathParts.last()
                val (currentDir, missingDir) = rootDocumentFile.directoryHolding(pathParts)
                if (currentDir == null) {
                    return@withContext "Error: Directory '$missingDir' not found in path."
                }

                val targetFile = currentDir.findFile(filename)
                if (targetFile == null || !targetFile.isFile) {
                    return@withContext "Error: File '$filepath' does not exist. Use 'make_file' to create new files."
                }

                // 3. Determine MIME Type
                // If user didn't provide one, try to keep the existing one, or guess from extension
                val finalMimeType = mimeType ?: targetFile.type ?: "text/plain"

                // 4. Overwrite Content
                // "w" mode truncates the file before writing
                application.applicationContext.contentResolver.openOutputStream(targetFile.uri, "w")?.use { outputStream ->
                    outputStream.write(newContent.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                } ?: return@withContext "Error: Could not open file for writing."

                "File '$filepath' successfully updated."

            } catch (e: Exception) {
                Log.e("EditFile", "Error editing file", e)
                "Error editing file: ${e.message}"
            }
        }
    }

    private suspend fun copyFileViaSaf(sourcePath: String, destinationPath: String): String {
        return withContext(Dispatchers.IO) {
            // 1. Security Checks
            if (unsafeWorkspacePath(sourcePath) || unsafeWorkspacePath(destinationPath)) {
                return@withContext "Error: Invalid paths. Backslashes and parent directories (..) are not allowed."
            }

            try {
                val rootDocumentFile = openWorkspaceRoot()
                    ?: return@withContext workspaceClosed(
                        "Error: Folder permission not granted. Ask the user to grant folder access.",
                        "Error: Could not access the workspace folder.",
                    )

                // 2. Locate Source File
                val sourceParts = sourcePath.split("/")
                val sourceFilename = sourceParts.last()
                val (currentSourceDir, missingSource) = rootDocumentFile.directoryHolding(sourceParts)
                if (currentSourceDir == null) {
                    return@withContext "Error: Source directory '$missingSource' not found in path '$sourcePath'."
                }

                val sourceFile = currentSourceDir.findFile(sourceFilename)
                if (sourceFile == null || !sourceFile.isFile) {
                    return@withContext "Error: Source file '$sourcePath' not found."
                }

                // 3. Prepare Destination Directory
                val destParts = destinationPath.split("/")
                val desiredDestFilename = destParts.last()

                var destParentDir = rootDocumentFile
                // Traverse/Create destination folders
                if (destParts.size > 1) {
                    for (i in 0 until destParts.size - 1) {
                        val dirName = destParts[i]
                        if (dirName.isBlank()) continue

                        var nextDir = destParentDir.findFile(dirName)
                        if (nextDir == null) {
                            nextDir = destParentDir.createDirectory(dirName)
                            if (nextDir == null) {
                                return@withContext "Error: Could not create destination directory '$dirName'."
                            }
                        } else if (!nextDir.isDirectory) {
                            return@withContext "Error: Destination path component '$dirName' exists but is not a directory."
                        }
                        destParentDir = nextDir
                    }
                }

                // 4. Handle Filename Conflicts (Non-Overwrite Logic)
                var finalDestFilename = desiredDestFilename
                val existingFile = destParentDir.findFile(desiredDestFilename)

                if (existingFile != null && existingFile.isFile) {
                    // File exists! Generate a unique name with timestamp
                    finalDestFilename = generateUniqueFilename(destParentDir, desiredDestFilename)
                }

                // 5. Create the new file and Copy Content
                // Determine MIME type from source if possible, otherwise generic
                val mimeType = sourceFile.type ?: "application/octet-stream"

                val newDestFile = destParentDir.createFile(mimeType, finalDestFilename)

                if (newDestFile == null) {
                    return@withContext "Error: Failed to create destination file '$finalDestFilename'."
                }

                // Perform the byte copy
                application.applicationContext.contentResolver.openInputStream(sourceFile.uri)?.use { input ->
                    application.applicationContext.contentResolver.openOutputStream(newDestFile.uri)?.use { output ->
                        input.copyTo(output)
                    }
                } ?: throw Exception("Failed to open streams for copy operation.")

                val actualPath = if (destParts.size > 1) {
                    // Reconstruct path for display
                    destParts.dropLast(1).joinToString("/") + "/" + finalDestFilename
                } else {
                    finalDestFilename
                }

                "Successfully copied '$sourcePath' to '$actualPath'."

            } catch (e: Exception) {
                Log.e("CopyFile", "Error copying file", e)
                "Error copying file: ${e.message}"
            }
        }
    }

    /** "notes.txt" with stamp "20231027_143000" becomes "notes_20231027_143000.txt". */
    private fun stampedName(filename: String, stamp: String): String {
        val baseName = filename.substringBeforeLast(".")
        val extension = filename.substringAfterLast(".", "")
        return if (extension.isNotBlank()) "${baseName}_$stamp.$extension" else "${baseName}_$stamp"
    }

    /**
     * Generates a unique filename by appending a timestamp if the desired name exists.
     * Example: "notes.txt" -> "notes_20231027_143000.txt"
     */
    private fun generateUniqueFilename(parentDir: androidx.documentfile.provider.DocumentFile, desiredName: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        var candidateName = stampedName(desiredName, timestamp)

        // Safety loop: In the extremely rare case of a collision within the same second
        var counter = 1
        while (parentDir.findFile(candidateName) != null) {
            candidateName = stampedName(desiredName, "${timestamp}_$counter")
            counter++
        }

        return candidateName
    }

    private suspend fun searchNearbyPlaces(
        query: String,
        latitude: Double?,
        longitude: Double?,
        radius: Int,
        location: String? // Add this parameter
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = sharedPreferencesHelper.getApiKeyFromPrefs("brave_search_api_key")

                val userCountry = Locale.getDefault().country
                val units = if (userCountry == "US" || userCountry == "LR" || userCountry == "MM") "imperial" else "metric"

                val urlBuilder = StringBuilder("https://api.search.brave.com/res/v1/local/place_search").apply {
                    append("?q=").append(java.net.URLEncoder.encode(query, "UTF-8"))

                    // Branch here: use location name OR coordinates
                    if (!location.isNullOrBlank()) {
                        append("&location=").append(java.net.URLEncoder.encode(location, "UTF-8"))
                    } else if (latitude != null && longitude != null) {
                        append("&latitude=").append(latitude)
                        append("&longitude=").append(longitude)
                        append("&radius=").append(radius)
                    }
                    append("&count=10")
                    append("&units=").append(units)
                }

                val response = httpClient.get(urlBuilder.toString()) {
                    header("Accept", "application/json")
                    header("X-Subscription-Token", apiKey)
                }

                if (!response.status.isSuccess()) {
                    val errorBody = try { response.bodyAsText() } catch (ex: Exception) { "No details" }
                    return@withContext "Brave Place Search Error: ${response.status} – $errorBody"
                }

                val data = response.body<JsonObject>()
                val resultsArray = data["results"]?.jsonArray

                if (resultsArray == null || resultsArray.isEmpty()) {
                    return@withContext "No nearby places found for: $query"
                }

                val sb = StringBuilder()
                // Dynamic header based on search type
                if (!location.isNullOrBlank()) {
                    sb.appendLine("## Places for: \"$query\" near $location")
                } else {
                    sb.appendLine("## Nearby Places for: \"$query\" (within ${radius}m)")
                }
                sb.appendLine()

                resultsArray.forEachIndexed { index, element ->
                    val result = element.jsonObject
                    val title = result["title"]?.jsonPrimitive?.content ?: "Untitled"
                    val address = result["postal_address"]?.jsonObject?.get("displayAddress")?.jsonPrimitive?.contentOrNull ?: ""

                    // --- NEW: Extract Coordinates ---
                    val coordinatesArray = result["coordinates"]?.jsonArray
                    val placeLat = coordinatesArray?.get(0)?.jsonPrimitive?.doubleOrNull
                    val placeLng = coordinatesArray?.get(1)?.jsonPrimitive?.doubleOrNull

                    // --- NEW: Generate Google Maps Link ---
                    val mapsLink = if (placeLat != null && placeLng != null) {
                        "https://www.google.com/maps/search/?api=1&query=$placeLat,$placeLng"
                    } else {
                        // Fallback to website if coordinates are missing
                        result["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                    val appleMapsLink = if (placeLat != null && placeLng != null) {
                        "https://maps.apple.com/?q=$placeLat,$placeLng"
                    } else {
                        ""
                    }
                    // ---------------------------------

                    val distance = result["distance"]?.jsonObject?.let { distObj ->
                        val value = distObj["value"]?.jsonPrimitive?.doubleOrNull
                        val distUnits = distObj["units"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (value != null) "$value $distUnits" else ""
                    } ?: ""

                    val ratingObj = result["rating"]?.jsonObject
                    val ratingValue = ratingObj?.get("ratingValue")?.jsonPrimitive?.doubleOrNull
                    val reviewCount = ratingObj?.get("reviewCount")?.jsonPrimitive?.intOrNull
                    val ratingStr = if (ratingValue != null) {
                        if (reviewCount != null) "$ratingValue/5 ($reviewCount reviews)" else "$ratingValue/5"
                    } else ""

                    val priceRange = result["price_range"]?.jsonPrimitive?.contentOrNull ?: ""
                    val phone = result["contact"]?.jsonObject?.get("telephone")?.jsonPrimitive?.contentOrNull ?: ""

                    val website = result["url"]?.jsonPrimitive?.contentOrNull ?: ""

                    // Parse today's hours simply
                    val hoursStr = result["opening_hours"]?.jsonObject?.get("current_day")?.jsonArray?.firstOrNull()?.jsonObject?.let { dayObj ->
                        val opens = dayObj["opens"]?.jsonPrimitive?.contentOrNull ?: ""
                        val closes = dayObj["closes"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (opens.isNotBlank() && closes.isNotBlank()) "Today: $opens-$closes" else ""
                    } ?: ""

                    sb.appendLine("### ${index + 1}. $title")
                    if (address.isNotBlank()) sb.appendLine("Address: $address")
                    if (distance.isNotBlank()) sb.appendLine("Distance: $distance")
                    if (phone.isNotBlank()) sb.appendLine("Phone: $phone")
                    if (website.isNotBlank()) sb.appendLine("Website: $website")
                    if (mapsLink.isNotBlank()) sb.appendLine("Google Maps: $mapsLink") // NEW
                    if (appleMapsLink.isNotBlank()) sb.appendLine("Apple Maps: $appleMapsLink")
                    if (placeLat != null && placeLng != null) {
                        sb.appendLine("Coordinates: $placeLat, $placeLng")
                    }
                    if (ratingStr.isNotBlank()) sb.appendLine("Rating: $ratingStr")
                    if (priceRange.isNotBlank()) sb.appendLine("Price: $priceRange")
                    if (hoursStr.isNotBlank()) sb.appendLine("Hours: $hoursStr")
                    sb.appendLine()

                }

                sb.toString()
            } catch (e: Exception) {
                "Error: Brave Place Search failed – ${e.message}"
            }
        }
    }

    private suspend fun searchBraveLlmContext(
        query: String,
        freshness: String?,
        count: Int,
        maxTokens: Int,
        threshold: String?,
        safesearch: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = sharedPreferencesHelper.getApiKeyFromPrefs("brave_search_api_key")

                val urlBuilder = StringBuilder("https://api.search.brave.com/res/v1/llm/context").apply {
                    append("?q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                    append("&count=").append(count.coerceIn(1, 50))
                    append("&maximum_number_of_tokens=").append(maxTokens.coerceIn(1024, 32768))

                    val safe = if (safesearch in listOf("off", "moderate", "strict")) safesearch else "moderate"
                    append("&safesearch=").append(safe)
                    if (!freshness.isNullOrBlank()) {
                        append("&freshness=").append(java.net.URLEncoder.encode(freshness, "UTF-8"))
                    }
                    if (!threshold.isNullOrBlank() && threshold in listOf("strict", "balanced", "lenient", "disabled")) {
                        append("&context_threshold_mode=").append(threshold)
                    }
                    append("&enable_source_metadata=true")
                }

                val response = httpClient.get(urlBuilder.toString()) {
                    header("Accept", "application/json")
                    header("X-Subscription-Token", apiKey)
                }

                if (!response.status.isSuccess()) {
                    val errorBody = try { response.bodyAsText() } catch (ex: Exception) { "No details" }
                    return@withContext "Brave LLM Context Error: ${response.status} – $errorBody"
                }

                val data = response.body<JsonObject>()
                val generic = data["grounding"]?.jsonObject?.get("generic")?.jsonArray
                val sources = data["sources"]?.jsonObject

                if (generic == null || generic.isEmpty()) {
                    return@withContext "No relevant content found for: $query"
                }

                val sb = StringBuilder()
                sb.appendLine("## Web Context for: \"$query\"")
                if (!freshness.isNullOrBlank()) {
                    val freshnessLabel = when (freshness) {
                        "pd" -> "Past Day"
                        "pw" -> "Past Week"
                        "pm" -> "Past Month"
                        "py" -> "Past Year"
                        else -> "Date Range: $freshness"
                    }
                    sb.appendLine("Freshness: $freshnessLabel")
                }
                sb.appendLine()

                generic.forEachIndexed { index, element ->
                    val item = element.jsonObject
                    val url = item["url"]?.jsonPrimitive?.content ?: ""
                    val title = item["title"]?.jsonPrimitive?.content ?: "Untitled"
                    val snippets = item["snippets"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList()

                    // Look up source metadata (age, hostname)
                    val sourceMeta = sources?.get(url)?.jsonObject
                    val hostname = sourceMeta?.get("hostname")?.jsonPrimitive?.contentOrNull ?: ""
                    val ageArray = sourceMeta?.get("age")?.jsonArray
                    // age array: [full date, YYYY-MM-DD, relative age, ISO 8601]
                    val age = ageArray?.getOrNull(2)?.jsonPrimitive?.contentOrNull
                        ?: ageArray?.getOrNull(1)?.jsonPrimitive?.contentOrNull
                        ?: ""

                    sb.appendLine("### ${index + 1}. $title")
                    if (hostname.isNotBlank()) sb.appendLine("Source: $hostname")
                    if (age.isNotBlank()) sb.appendLine("Age: $age")
                    sb.appendLine("URL: $url")
                    sb.appendLine()
                    snippets.forEach { snippet ->
                        sb.appendLine(snippet)
                        sb.appendLine()
                    }
                    sb.appendLine("---")
                    sb.appendLine()
                }

                sb.toString()
            } catch (e: Exception) {
                "Error: Brave LLM Context failed – ${e.message}"
            }
        }
    }

    private suspend fun searchBraveNews(
        query: String,
        freshness: String?,
        count: Int,
        safesearch: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = sharedPreferencesHelper.getApiKeyFromPrefs("brave_search_api_key")

                val urlBuilder = StringBuilder("https://api.search.brave.com/res/v1/news/search").apply {
                    append("?q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                    append("&count=").append(count.coerceIn(1, 50))

                    val safe = if (safesearch in listOf("off", "moderate", "strict")) safesearch else "moderate"
                    append("&safesearch=").append(safe)
                    if (!freshness.isNullOrBlank()) {
                        append("&freshness=").append(java.net.URLEncoder.encode(freshness, "UTF-8"))
                    }
                    append("&extra_snippets=true")
                }

                val response = httpClient.get(urlBuilder.toString()) {
                    header("Accept", "application/json")
                    header("X-Subscription-Token", apiKey)
                }

                if (!response.status.isSuccess()) {
                    val errorBody = try { response.bodyAsText() } catch (ex: Exception) { "No details" }
                    return@withContext "Brave News Error: ${response.status} – $errorBody"
                }

                val data = response.body<JsonObject>()

                // News endpoint returns results at the TOP LEVEL (not nested under "news")
                val resultsArray = data["results"]?.jsonArray ?: JsonArray(listOf())

                if (resultsArray.isEmpty()) {
                    return@withContext "No news articles found for: $query"
                }

                val sb = StringBuilder()
                sb.appendLine("## Brave News Results for: \"$query\"")
                if (!freshness.isNullOrBlank()) {
                    val freshnessLabel = when (freshness) {
                        "pd" -> "Past Day"
                        "pw" -> "Past Week"
                        "pm" -> "Past Month"
                        "py" -> "Past Year"
                        else -> "Date Range: $freshness"
                    }
                    sb.appendLine("Freshness filter: $freshnessLabel")
                }
                sb.appendLine()

                resultsArray.forEachIndexed { index, element ->
                    val result = element.jsonObject
                    val title = result["title"]?.jsonPrimitive?.content ?: "Untitled"
                    val url = result["url"]?.jsonPrimitive?.content ?: ""
                    val description = result["description"]?.jsonPrimitive?.content
                        ?: result["snippets"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                        ?: ""

                    val extraSnippets = result["extra_snippets"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList()

                    val publisher = result["meta_url"]?.jsonObject?.get("hostname")?.jsonPrimitive?.content
                        ?: result["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                        ?: ""

                    // News uses "age" (e.g. "2 hours ago"); fall back to "page_age" (ISO date)
                    val pageAge = result["age"]?.jsonPrimitive?.content
                        ?: result["page_age"]?.jsonPrimitive?.content
                        ?: ""

                    val breaking = result["breaking"]?.jsonPrimitive?.booleanOrNull == true

                    sb.appendLine("### ${index + 1}. $title${if (breaking) " 🚨 BREAKING" else ""}")
                    if (publisher.isNotBlank()) sb.appendLine("Source: $publisher")
                    if (pageAge.isNotBlank()) sb.appendLine("Published: $pageAge")
                    sb.appendLine("URL: $url")
                    if (description.isNotBlank()) sb.appendLine("Summary: $description")
                    if (extraSnippets.isNotEmpty()) {
                        extraSnippets.forEach { snippet ->
                            sb.appendLine("  • $snippet")
                        }
                    }
                    sb.appendLine()
                }

                sb.toString()
            } catch (e: Exception) {
                "Error: Brave News failed – ${e.message}"
            }
        }
    }

    private fun saveFileToOpenChatWorkspace(filename: String, content: String, mimeType: String, subfolder: String = "") {
        val context = application.applicationContext

        // Sanitize the subfolder path (remove leading slashes, prevent directory traversal)
        val safeSubfolder = subfolder.trim().removePrefix("/").removeSuffix("/")
        if (safeSubfolder.contains("..")) {
            throw Exception("Invalid path: Directory traversal not allowed.")
        }

        WorkspacePaths.ensureWorkspaceExists()
        val relativePath = WorkspacePaths.mediaStoreRelativePath(safeSubfolder)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }

        // Check if file already exists in that path to prevent MediaStore crash on duplicate names
        // MediaStore throws an error if you insert a file with the exact same name in the same folder.
        var finalFilename = filename

        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(filename, "%$relativePath%")

        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                finalFilename = stampedName(filename, timestamp)
            }
        }

        // Update the values with the final filename (in case it was renamed)
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, finalFilename)
        writeTextDownload(values, content)
    }

    private fun createFolderInWorkspaceViaMediaStore(folderPath: String) {
        val context = application.applicationContext

        // Security check
        if (unsafeWorkspacePath(folderPath)) {
            throw Exception("Invalid path: Backslashes and parent directories (..) are not allowed.")
        }

        val safeFolderPath = folderPath.trim().removePrefix("/").removeSuffix("/")
        if (safeFolderPath.isBlank()) {
            throw Exception("Folder path cannot be empty.")
        }

        val relativePath = WorkspacePaths.mediaStoreRelativePath(safeFolderPath)

        // Quirk: MediaStore only creates folders when a file is created inside them.
        // So, we create a temporary dummy file, then delete it. The folder remains!
        val dummyFilename = ".folder_placeholder_temp"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, dummyFilename)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }

        val uri = context.contentResolver
            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Failed to create folder (MediaStore insert failed)")

        // Immediately delete the dummy file
        context.contentResolver.delete(uri, null, null)
    }

    internal fun saveFileToDownloads(filename: String, content: String, mimeType: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, WorkspacePaths.mediaStoreRelativePath())
        }
        writeTextDownload(values, content)
    }

    private fun writeTextDownload(values: ContentValues, content: String) {
        val uri = application.contentResolver
            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("MediaStore insert failed")
        application.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray())
        } ?: throw Exception("Cannot open output stream")
    }

    private suspend fun listOpenChatFilesViaSaf(path: String = ""): String {
        return withContext(Dispatchers.IO) {
            try {
                var currentDir = openWorkspaceRoot()
                    ?: return@withContext workspaceClosed(
                        "Error: App does not have permission to read the folder yet. Tell the user to tap the 'Select Folder' button in the app settings to grant access.",
                        "Error: Could not access the workspace folder.",
                    )

                // Navigate to subfolder if a path was provided
                if (path.isNotBlank()) {
                    if (unsafeWorkspacePath(path)) {
                        return@withContext "Error: Invalid path characters."
                    }
                    val parts = path.trim('/').split("/")
                    for (dirName in parts) {
                        if (dirName.isBlank()) continue
                        val nextDir = currentDir.findFile(dirName)
                        if (nextDir == null || !nextDir.isDirectory) {
                            return@withContext "Error: Subfolder '$dirName' not found."
                        }
                        currentDir = nextDir
                    }
                }

                if (!currentDir.canRead()) {
                    return@withContext "Error: Lost access to the folder. Ask the user to re-select it."
                }

                val fileList = mutableListOf<String>()
                val basePath = if (path.isNotBlank()) path.trimEnd('/') + "/" else ""

                for (doc in currentDir.listFiles()) {
                    val name = doc.name ?: continue
                    if (doc.isDirectory) {
                        // Mark directories so the AI knows it can navigate into them
                        fileList.add("[Folder] $basePath$name/")
                    } else if (doc.isFile) {
                        fileList.add("$basePath$name")
                    }
                }

                if (fileList.isEmpty()) {
                    "No files or folders found in '${if (path.isBlank()) "root" else path}'."
                } else {
                    "Available items:\n${fileList.joinToString("\n")}"
                }
            } catch (e: Exception) {
                "Error accessing folder: ${e.message}"
            }
        }
    }

    private suspend fun readOpenChatFileViaSaf(filepath: String): String {
        return withContext(Dispatchers.IO) {
            try {
                withWorkspaceFile(filepath) { targetFile ->
                    if (targetFile.length() > 10 * 1024 * 1024) {
                        return@withWorkspaceFile "Error: File is too large (max 10MB)."
                    }
                    application.applicationContext.contentResolver.openInputStream(targetFile.uri)?.use { inputStream ->
                        val content = inputStream.bufferedReader().readText()
                        if (content.contains('\u0000')) {
                            return@withWorkspaceFile "Error: Binary files cannot be read. Only text files are supported."
                        }
                        "File: $filepath\n\n$content"
                    } ?: "Error: Could not open input stream."
                }
            } catch (e: Exception) {
                "Error reading file: ${e.message}"
            }
        }
    }

    private suspend fun openFileViaSaf(filepath: String, mimeType: String?): String {
        return withContext(Dispatchers.IO) {
            try {
                withWorkspaceFile(filepath) { targetFile ->
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(targetFile.uri, mimeType ?: targetFile.type ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(application.applicationContext.packageManager) != null) {
                        application.applicationContext.startActivity(intent)
                        "Opening '$filepath'..."
                    } else {
                        "Error: No app found to open this file type."
                    }
                }
            } catch (e: Exception) {
                "Error opening file: ${e.message}"
            }
        }
    }
    private fun parseFilepaths(element: JsonElement?): List<String> {
        return when (element) {
            is JsonArray -> {
                element.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                    .filter { it.isNotBlank() }
            }
            is JsonPrimitive -> {
                if (!element.isString) return emptyList()

                val content = element.content.trim()

                // Case 1: LLM sent a stringified JSON array like: ["file1.txt", "file2.txt"]
                if (content.startsWith("[") && content.endsWith("]")) {
                    try {
                        json.decodeFromString<List<String>>(content)
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                    } catch (e: Exception) {
                        // Fallback: treat as single path
                        listOf(content.removeSurrounding("\"").trim())
                    }
                }
                // Case 2: Single filepath sent as string
                else {
                    listOf(content)
                }
            }
            else -> emptyList()
        }
    }

    private suspend fun deleteFilesViaSaf(filepaths: List<String>): String {
        return withContext(Dispatchers.IO) {
            val rootDocumentFile = openWorkspaceRoot()
                ?: return@withContext workspaceClosed(
                    "Error: Folder permission not granted. Please grant workspace access first.",
                    "Error: Could not access the workspace folder.",
                )

            if (filepaths.size > 9) {
                return@withContext "Error: Too many files (maximum 9 per request)."
            }

            val results = mutableListOf<String>()
            var successCount = 0

            for (filepath in filepaths) {
                // Security checks
                if (filepath.isBlank() || unsafeWorkspacePath(filepath)) {
                    results.add("❌ '$filepath': Invalid or dangerous path")
                    continue
                }

                val pathParts = filepath.split("/").filter { it.isNotBlank() }
                if (pathParts.isEmpty()) {
                    results.add("❌ '$filepath': Invalid path")
                    continue
                }

                val filename = pathParts.last()
                val (parent, missingDir) = rootDocumentFile.directoryHolding(pathParts)
                if (parent == null) {
                    results.add("❌ '$filepath': Directory '$missingDir' not found")
                    continue
                }

                // Attempt deletion
                val targetFile = parent.findFile(filename)
                if (targetFile == null || !targetFile.isFile) {
                    results.add("❌ '$filepath': File not found")
                } else {
                    val deleted = targetFile.delete()
                    if (deleted) {
                        results.add("✅ '$filepath': Successfully deleted")
                        successCount++
                    } else {
                        results.add("❌ '$filepath': Delete operation failed")
                    }
                }
            }

            val summary = if (filepaths.size == 1) {
                results.first()
            } else {
                "Deleted $successCount of ${filepaths.size} files."
            }

            _toastUiEvent.postValue(Event(summary))
            results.joinToString("\n")
        }
    }
}
