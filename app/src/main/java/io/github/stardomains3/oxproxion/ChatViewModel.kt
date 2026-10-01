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
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

@Serializable
data class OpenRouterResponse(val data: List<ModelData>)

@Serializable
data class ModelData(
    val id: String,
    val name: String,
    val architecture: Architecture,
    @SerialName("created") val created: Long,
    @SerialName("supported_parameters") val supportedParameters: List<String>? = null
)

@Serializable
data class Architecture(
    val input_modalities: List<String>,
    val output_modalities: List<String>? = null
)

enum class SortOrder {
    ALPHABETICAL,
    BY_DATE
}

/**
 * Caches saved before reasoning flags existed read every model as not reasoning.
 * Refresh that list once. A later list whose first model is not a reasoning model stays.
 */
internal fun openRouterCacheMissingReasoning(alreadyMigrated: Boolean, models: List<LlmModel>): Boolean =
    !alreadyMigrated && models.isNotEmpty() && models.none { it.isReasoningCapable }


class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage
    private val json = Json { ignoreUnknownKeys = true }
    private val _sharedText = MutableStateFlow<String?>(null)
    val sharedText: StateFlow<String?> = _sharedText
    private var shouldAutoOffWebSearch = false
    private fun getWebSearchEngine(): String = sharedPreferencesHelper.getWebSearchEngine()
    private var allOpenRouterModels: List<LlmModel> = emptyList()
    private val _openRouterModels = MutableLiveData<List<LlmModel>>()
    val openRouterModels: LiveData<List<LlmModel>> = _openRouterModels
    private val _sortOrder = MutableStateFlow(SortOrder.ALPHABETICAL)
    val sortOrder: StateFlow<SortOrder> = _sortOrder

    private val _customModelsUpdated = MutableLiveData<Event<Unit>>()
    val customModelsUpdated: LiveData<Event<Unit>> = _customModelsUpdated

    fun isVisionModel(modelIdentifier: String?): Boolean {
        if (modelIdentifier == null) return false
        val customModels = sharedPreferencesHelper.getCustomModels()
        val allModels = getBuiltInModels() + customModels
        val model = allModels.find { it.apiIdentifier == modelIdentifier }
        return model?.isVisionCapable ?: false
    }
    fun isLanModel(modelIdentifier: String?): Boolean {
        if (modelIdentifier == null) return false

        // Check Built-in models
        val builtIn = getBuiltInModels().find { it.apiIdentifier == modelIdentifier }
        if (builtIn != null) return builtIn.isLANModel

        // Check Custom models
        val customModels = sharedPreferencesHelper.getCustomModels()
        val custom = customModels.find { it.apiIdentifier == modelIdentifier }
        if (custom != null) return custom.isLANModel

        // Check OpenRouter models (if applicable)
        val openRouter = sharedPreferencesHelper.getOpenRouterModels().find { it.apiIdentifier == modelIdentifier }
        if (openRouter != null) return openRouter.isLANModel

        return false
    }
    fun isReasoningModel(modelIdentifier: String?): Boolean {
        if (modelIdentifier == null) return false

        // 1. built-ins + presets / custom models
        val customModels = sharedPreferencesHelper.getCustomModels()
        val own = (getBuiltInModels() + customModels)
            .find { it.apiIdentifier == modelIdentifier }
        if (own?.isReasoningCapable == true) return true

        // 2. fall back to the downloaded OR catalogue (in case we ever ship
        //    official reasoning models there and the user picked one)
        val fromOr = sharedPreferencesHelper.getOpenRouterModels()
            .find { it.apiIdentifier == modelIdentifier }
        return fromOr?.isReasoningCapable ?: false
    }
    /**
     * Whether a reasoning request makes sense. OpenRouter drops the parameter on models that
     * can't think, so any cloud model qualifies; local servers reject it, so they need the flag.
     */
    fun canRequestReasoning(modelIdentifier: String?): Boolean {
        if (modelIdentifier == null) return false
        return isReasoningModel(modelIdentifier) || !isLanModel(modelIdentifier)
    }
    fun isTranscriptionModel(modelIdentifier: String?): Boolean {
        if (modelIdentifier == null) return false
        val customModels = sharedPreferencesHelper.getCustomModels()
        val allModels = getBuiltInModels() + customModels
        val model = allModels.find { it.apiIdentifier == modelIdentifier }
        return model?.isTranscription ?: false
    }
    /** Answers locally with a paced stream; see [DemoModel]. */
    private val demoClientDelegate = lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            engine { addInterceptor(DemoModel.StreamInterceptor { isRpMode() }) }
        }
    }
    private val demoHttpClient: HttpClient by demoClientDelegate

    /** A timeout change takes effect on the next turn; swapping clients mid-stream would cut it off. */
    private var clientsStale = false
    private val timeoutPrefListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SharedPreferencesHelper.KEY_TIMEOUT_MINUTES) clientsStale = true
        }

    /**
     * The cloud and LAN clients differ only in their write and connect limits and in what
     * [configureOkHttp] adds, so the rest of the setup lives here once.
     */
    private fun buildChatClient(
        writeTimeoutMs: Long,
        connectTimeoutMs: Long,
        configureOkHttp: okhttp3.OkHttpClient.Builder.() -> Unit = {},
    ): HttpClient {
        val readTimeoutMs = sharedPreferencesHelper.getTimeoutMinutes().toLong() * 60_000L
        return HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(DefaultRequest) {
                header("User-Agent", "GradatiON/${BuildConfig.VERSION_NAME}")
            }
            engine {
                clientCacheSize = 0
                config {
                    pingInterval(56, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                    configureOkHttp()
                    addInterceptor(CompressionInterceptor(Gzip))
                    addInterceptor(BrotliInterceptor)
                    // The limit that matters is silence: the read timeout is how long the server may
                    // go without sending a byte. A call timeout would also cut off a reply that is
                    // still streaming, so it stays off; non-streamed requests carry their own withTimeout.
                    readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                    callTimeout(0, TimeUnit.MILLISECONDS)
                    writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
                    connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun createHttpClient(): HttpClient = buildChatClient(
        writeTimeoutMs = sharedPreferencesHelper.getTimeoutMinutes().toLong() * 60_000L,
        connectTimeoutMs = 60_000L,
    )

    private fun createLanHttpClient(): HttpClient {
        val trustSelfSignedLan = sharedPreferencesHelper.getTrustSelfSignedLan()
        return buildChatClient(writeTimeoutMs = 30_000L, connectTimeoutMs = 30_000L) {
            connectionPool(okhttp3.ConnectionPool(3, 90, TimeUnit.SECONDS))
            if (trustSelfSignedLan) {
                // The handshake has to let an unknown certificate through, or first use could never
                // happen. LanCertPinInterceptor runs before any byte of the request is written and
                // refuses everything but the certificate pinned for that host:port.
                val acceptForHandshake = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(acceptForHandshake), SecureRandom())

                sslSocketFactory(sslContext.socketFactory, acceptForHandshake)
                // Self-signed LAN certificates rarely carry the right name; the pin is the identity.
                hostnameVerifier { _, _ -> true }
                addNetworkInterceptor(
                    LanCertPinInterceptor(
                        LanCertPins(sharedPreferencesHelper.lanCertPinStore()),
                        str(R.string.error_lan_cert_changed)
                    )
                )
            }
        }
    }
    fun hasImagesInChat(): Boolean = _chatMessages.value?.any { isImageMessage(it) } ?: false

    private fun isImageMessage(message: FlexibleMessage): Boolean =
        MessageContent.hasImage(message.content) ||
            message.imageUri?.let { it.isNotEmpty() && !it.startsWith("data:") } == true

    fun getMessageText(content: JsonElement): String = MessageContent.text(content)

    // Opening the encrypted database costs real time (Keystore, key derivation, maybe a one-off
    // encrypt of an old plaintext file), so init only starts it on IO; these are first touched on
    // that thread or after [dbWarmup], never by constructing the ViewModel on Main.
    private val repository: ChatRepository by lazy {
        ChatRepository(AppDatabase.getDatabase(getApplication()).chatDao())
    }
    private val rpRepo: RpRepository by lazy {
        RpRepository(AppDatabase.getDatabase(getApplication()).rpDao())
    }
    private val rpDelegate: RpChatDelegate by lazy { RpChatDelegate(rpRepo, sharedPreferencesHelper) }
    private val dbWarmup: Job? = if (AppDatabase.isOpen()) null else viewModelScope.launch(Dispatchers.IO) {
        try {
            AppDatabase.getDatabase(getApplication())
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Chat database unavailable", e)
        }
    }
    private val rpSwipeStore: RpSwipeStore
    private var currentSessionId: Long? = null
    /** True once the cold-start restore has settled, so a later id change is a real switch. */
    private val _sessionReady = MutableLiveData(false)
    val sessionReady: LiveData<Boolean> = _sessionReady
    private val _openSessionId = MutableLiveData<Long?>(null)
    val openSessionId: LiveData<Long?> = _openSessionId
    /** The unsaved chat just received its row id. The composer text stays; only the key moves. */
    private var openSessionPromoted = false

    private val _chatMode = MutableLiveData<ChatMode>()
    val chatMode: LiveData<ChatMode> = _chatMode
    private val _activeRpCharacter = MutableLiveData<RpCharacter?>()
    val activeRpCharacter: LiveData<RpCharacter?> = _activeRpCharacter
    private var rpSwipeState: RpSwipeState = RpSwipeState()
    /** When true, the next finalized RP assistant reply is appended to swipe alts. */
    private var pendingRpSwipeAppend = false
    /** Stop mid-regen restore when the prior bubble was an error (not in swipe alts). */
    private var rpRegenRestoreFallback: String? = null
    /** Bumped to invalidate delayed swipe restores after load / new chat / mode switch. */
    private var rpSwipeRestoreToken = 0
    /** Cancels overlapping load / mode switch / character start / cold-start restore. */
    private var sessionTransitionJob: Job? = null
    /**
     * Bumped when the open session/mode identity changes. An in-flight save still writes
     * the snapshot it took; this only stops that save from attaching to the chat that replaced it.
     */
    private var sessionEpoch = 0L
    /** Older snapshots of one chat must not overwrite a newer one. */
    private val chatSaveSerial = ChatSaveSerial()
    private val chatSaveMutex = Mutex()
    private val pendingSaves = ArrayList<Job>()
    /** RP prompt-build before network starts; Stop must cancel this and clear early awaiting. */
    private var rpPrepJob: Job? = null
    /**
     * Preserves an orphaned session characterId across autosave so delete→re-import remapping
     * still finds rows (active prefs are cleared when the character row is missing).
     */
    private var preservedSessionCharacterId: Long? = null
    private val _rpSwipeNav = MutableLiveData<RpSwipeNav?>()
    val rpSwipeNav: LiveData<RpSwipeNav?> = _rpSwipeNav

    data class RpSwipeNav(
        val index: Int,
        val total: Int,
        val canPrev: Boolean,
        val canNext: Boolean
    )

    // State Management
    private val _chatMessages = MutableLiveData<List<FlexibleMessage>>(emptyList())
    val chatMessages: LiveData<List<FlexibleMessage>> = _chatMessages
    val _activeChatModel = MutableLiveData<String>()
    val activeChatModel: LiveData<String> = _activeChatModel
    private val _isAwaitingResponse = MutableLiveData<Boolean>(false)
    val isAwaitingResponse: LiveData<Boolean> = _isAwaitingResponse
    private val _modelPreferenceToSave = MutableLiveData<String?>()
    val modelPreferenceToSave: LiveData<String?> = _modelPreferenceToSave
    private val _creditsResult = MutableLiveData<Event<String>>()
    val creditsResult: LiveData<Event<String>> = _creditsResult
    val _isStreamingEnabled = MutableLiveData<Boolean>(false)
    val isStreamingEnabled: LiveData<Boolean> = _isStreamingEnabled
    private val _isExtendedTopBarEnabled = MutableLiveData<Boolean>(false)
    val isExtendedTopBarEnabled: LiveData<Boolean> = _isExtendedTopBarEnabled
    val _isReasoningEnabled = MutableLiveData(false)
    val isReasoningEnabled: LiveData<Boolean> = _isReasoningEnabled
    private val _isVolumeScrollEnabled = MutableLiveData<Boolean>()
    val isVolumeScrollEnabled: LiveData<Boolean> = _isVolumeScrollEnabled
    private val _isAdvancedReasoningOn = MutableLiveData(false)
    val isAdvancedReasoningOn: LiveData<Boolean> = _isAdvancedReasoningOn
    val _isWebSearchEnabled = MutableLiveData<Boolean>(false)
    val isWebSearchEnabled: LiveData<Boolean> = _isWebSearchEnabled
    val _isScrollersEnabled = MutableLiveData<Boolean>(false)
    val isScrollersEnabled: LiveData<Boolean> = _isScrollersEnabled
    private val _isExpandableInputEnabled = MutableLiveData<Boolean>(false)
    val isExpandableInputEnabled: LiveData<Boolean> = _isExpandableInputEnabled
    private val _scrollToBottomEvent = MutableLiveData<Event<Unit>>()
    val scrollToBottomEvent: LiveData<Event<Unit>> = _scrollToBottomEvent
    private val _toolUiEvent = MutableLiveData<Event<String>>()
    val toolUiEvent: LiveData<Event<String>> = _toolUiEvent
    /** A Roleplay thread was opened on purpose (a chat picked, a character started, a fresh chat): the chats home steps aside. */
    private val _rpThreadOpenedEvent = MutableLiveData<Event<Unit>>()
    val rpThreadOpenedEvent: LiveData<Event<Unit>> = _rpThreadOpenedEvent
    private val _toastUiEvent = MutableLiveData<Event<String>>()
    val toastUiEvent: LiveData<Event<String>> = _toastUiEvent
    private val _composerRestoreEvent = MutableLiveData<Event<String>>()
    val composerRestoreEvent: LiveData<Event<String>> = _composerRestoreEvent
    /** Fired when RP chrome must refresh even if active character LiveData is unchanged (e.g. LLM toggle). */
    private val _rpChromeRefreshEvent = MutableLiveData<Event<Unit>>()
    val rpChromeRefreshEvent: LiveData<Event<Unit>> = _rpChromeRefreshEvent
    private val _isChatLoading = MutableLiveData(false)
    val isChatLoading: LiveData<Boolean> = _isChatLoading
    private val _isExtendedDockEnabled = MutableLiveData<Boolean>()
    val isExtendedDockEnabled: LiveData<Boolean> = _isExtendedDockEnabled
    private val _isPresetsExtendedEnabled = MutableLiveData<Boolean>()
    val isPresetsExtendedEnabled: LiveData<Boolean> = _isPresetsExtendedEnabled
    /** True when an alternate message tree is stashed for this chat (edit/resend fork). */
    private val _hasChatFork = MutableLiveData(false)
    val hasChatFork: LiveData<Boolean> = _hasChatFork
    private var forkIndex: Int = -1
    private var forkAnchorAssistantIndex: Int = -1
    private var stashedForkTail: List<FlexibleMessage> = emptyList()
    private var forkDisplayVariant: Int = 1
    /**
     * Set while Edit has cut a turn and the replacement has not been sent. A regenerate
     * uses the fork too, and must not show Cancel. Keyed like [ComposerDrafts].
     */
    private var composerEditKey: String? = null
    /** The composer line from before Edit, so Cancel can put it back. */
    private val composerEditDrafts = HashMap<String, String>()

    data class ForkNavState(
        val variantIndex: Int,
        val totalVariants: Int = 2,
        val canGoPrev: Boolean,
        val canGoNext: Boolean
    )
    private var networkJob: Job? = null
    /** Main-thread coalescer for the in-flight stream; cancelled synchronously on Stop. */
    private var activeStreamPump: StreamUiPump? = null
    /**
     * A cut stopped the reply before shortening the list. The idle autosave would
     * otherwise persist the transcript from before that cut.
     */
    private var skipNextIdleAutosave = false
    /** Index of the in-flight assistant placeholder / streaming bubble in `_chatMessages`. */
    private var streamingAssistantIndex: Int = -1
    /**
     * Continue (Roleplay): the text of the reply being extended in place, else null. While set,
     * what the model streams is sewn onto it ([RpContinuation.join]) and lands in the same bubble.
     */
    private var continuationBase: String? = null
    val continuationText: String? get() = continuationBase
    /**
     * True after an RP (non-regen) send has added its thinking/stream bubble.
     * Used so Stop discards that partial without wiping a finished prior reply during prep.
     */
    private var discardableRpAssistantInFlight = false
    private val _autosendEvent = MutableLiveData<Event<String>>()
    val autosendEvent: LiveData<Event<String>> = _autosendEvent
    private val _userScrolledDuringStream = MutableLiveData(false)
    val userScrolledDuringStream: LiveData<Boolean> = _userScrolledDuringStream
    val _isToolsEnabled = MutableLiveData(false)
    val isToolsEnabled: LiveData<Boolean> = _isToolsEnabled
    private val _presetAppliedEvent = MutableLiveData<Event<Unit>>()
    val presetAppliedEvent: LiveData<Event<Unit>> = _presetAppliedEvent
    private val _isScrollProgressEnabled = MutableLiveData<Boolean>()
    val isScrollProgressEnabled: LiveData<Boolean> = _isScrollProgressEnabled
    private val _lanModels = MutableLiveData<List<LlmModel>>()
    val lanModels: LiveData<List<LlmModel>> = _lanModels
    private var lanFetchJob: Job? = null

    private fun isAssistantPlaceholder(message: FlexibleMessage): Boolean {
        if (message.role != "assistant") return false
        val text = (message.content as? JsonPrimitive)?.contentOrNull ?: return false
        return ThinkingPlaceholder.matches(text) || text.isBlank()
    }

    private fun resolveAssistantSlot(list: List<FlexibleMessage>, thinkingMessage: FlexibleMessage?): Int {
        if (thinkingMessage != null) {
            val byIdentity = list.indexOf(thinkingMessage)
            if (byIdentity != -1) return byIdentity
        }
        if (streamingAssistantIndex in list.indices && list[streamingAssistantIndex].role == "assistant") {
            return streamingAssistantIndex
        }
        for (i in list.lastIndex downTo 0) {
            if (isAssistantPlaceholder(list[i])) return i
        }
        return -1
    }

    private fun putAssistantMessage(list: MutableList<FlexibleMessage>, thinkingMessage: FlexibleMessage?, message: FlexibleMessage) {
        val merged = continuationBase?.let { mergeContinuation(it, message) } ?: message
        val index = resolveAssistantSlot(list, thinkingMessage)
        val newMessage = if (index != -1 && continuationBase != null) {
            RpContinuation.keepPicture(list[index].imageUri, merged, list[index].content)
        } else {
            merged
        }
        if (index != -1) {
            list[index] = newMessage
            streamingAssistantIndex = index
        } else {
            list.add(newMessage)
            streamingAssistantIndex = list.lastIndex
        }
        noteRpSwipePicture(newMessage)
    }

    /**
     * A streamed piece of a Continue, as the whole reply: the old text plus the new. A failure or
     * an empty answer leaves the reply as it was (and says so in a toast) instead of writing an
     * error bubble into the middle of it.
     */
    private fun mergeContinuation(base: String, piece: FlexibleMessage): FlexibleMessage {
        if (piece.role != "assistant" || piece.toolCalls != null) return piece
        val text = (piece.content as? JsonPrimitive)?.contentOrNull ?: return piece
        if (text.startsWith("**Error:**") || text == str(R.string.error_no_response)) {
            _toastUiEvent.postValue(Event(text.removePrefix("**Error:**").trim().trimStart('-').trim().ifBlank { text }))
            return piece.copy(content = JsonPrimitive(base))
        }
        if (text.isBlank()) return piece.copy(content = JsonPrimitive(base))
        // The story actually moved. A failed Continue never reaches here, so its swipe alts stay.
        clearRpSwipeAlts()
        return piece.copy(content = JsonPrimitive(RpContinuation.join(base, text)))
    }

    private fun clearRpSwipeAlts() {
        if (rpSwipeState.alts.isEmpty() && rpSwipeState.pictureUris.isEmpty()) return
        dropUnreferencedSwipePictures()
        rpSwipeState = RpSwipeState()
        currentSessionId?.let { rpSwipeStore.clear(it) }
        _rpSwipeNav.value = null
    }

    /**
     * Drop versions this transcript no longer has. A picture that only lived on one of them
     * goes too, unless the reply still on screen, or another chat, still names that file.
     */
    private fun forgetRpSwipeVersions() {
        dropUnreferencedSwipePictures()
        clearRpSwipeMemory()
        currentSessionId?.let { rpSwipeStore.clear(it) }
    }

    /** File names remembered on swipe versions, so a save of a different version does not delete them. */
    private fun swipePictureNames(swipe: RpSwipeState?): Set<String> =
        swipe?.pictureUris.orEmpty().mapNotNull { ScenePhoto.sceneFileName(it) }.toSet()

    /**
     * A version that is no longer kept. The file on the reply still showing stays; one that
     * is only named here, and not in any saved message, is removed.
     */
    private fun dropUnreferencedSwipePictures() {
        val names = swipePictureNames(rpSwipeState)
        if (names.isEmpty()) return
        val live = _chatMessages.value.orEmpty().mapNotNull { ScenePhoto.sceneFileName(it.imageUri) }.toSet()
        val held = synchronized(scenePhotosHeld) { scenePhotosHeld.toSet() }
        val pending = ScenePhoto.sceneFileName(pendingUserImageUri)
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val unused = names.filter {
                it !in live && it !in held && it != pending && !repository.scenePhotoStillUsed(it)
            }
            ScenePhoto.deleteSceneFiles(app, unused)
        }
    }

    /** The reply just landed with a file. Remember it on the version now showing. */
    private fun noteRpSwipePicture(message: FlexibleMessage) {
        if (!isRpMode() || message.role != "assistant") return
        val picture = RpSwipeRules.pictureUriOf(message.imageUri)
        val pictures = RpSwipeRules.notePicture(
            rpSwipeState.alts,
            rpSwipeState.pictureUris,
            rpSwipeState.index,
            picture,
        )
        if (pictures === rpSwipeState.pictureUris) return
        rpSwipeState = rpSwipeState.copy(pictureUris = pictures)
        persistRpSwipeState()
    }

    private fun removeAssistantPlaceholder(thinkingMessage: FlexibleMessage?) {
        updateMessages { list ->
            val index = resolveAssistantSlot(list, thinkingMessage)
            if (index != -1 && isAssistantPlaceholder(list[index])) {
                list.removeAt(index)
            }
            streamingAssistantIndex = -1
        }
    }

    private fun startNetworkJob(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        networkJob?.cancel()
        activeStreamPump?.cancel()
        if (clientsStale) {
            clientsStale = false
            refreshHttpClient()
        }
        val job = viewModelScope.launch {
            try {
                block()
            } finally {
                if (networkJob === coroutineContext[Job]) {
                    networkJob = null
                }
            }
        }
        networkJob = job
    }

    fun signalPresetApplied() {
        _presetAppliedEvent.value = Event(Unit)
    }

    fun toggleStreaming() {
        val newStremingState = !(_isStreamingEnabled.value ?: false)
        _isStreamingEnabled.value = newStremingState
        sharedPreferencesHelper.saveStreamingPreference(newStremingState)
    }
    fun toggleExtendedTopBar() {
        val newValue = !(_isExtendedTopBarEnabled.value ?: false)
        _isExtendedTopBarEnabled.value = newValue
        sharedPreferencesHelper.saveExtendedTopBarEnabled(newValue)
    }
    fun toggleScrollProgress() {
        val newValue = !(_isScrollProgressEnabled.value ?: false)
        _isScrollProgressEnabled.value = newValue
        sharedPreferencesHelper.saveScrollProgressEnabled(newValue)
    }
    fun toggleVolumeScroll() {
        val newValue = !(_isVolumeScrollEnabled.value ?: false)
        _isVolumeScrollEnabled.value = newValue
        sharedPreferencesHelper.saveVolumeScrollEnabled(newValue)
    }
    fun toggleExtendedDock() {
        val newValue = !(_isExtendedDockEnabled.value ?: false)
        _isExtendedDockEnabled.value = newValue
        sharedPreferencesHelper.saveExtPreference(newValue)
    }
    fun togglePresetsExtended() {
        val newValue = !(_isPresetsExtendedEnabled.value ?: false)
        _isPresetsExtendedEnabled.value = newValue
        sharedPreferencesHelper.saveExtPreference2(newValue)
    }
    fun toggleWebSearch() {
        if (isRpMode()) return
        val newNotiState = !(_isWebSearchEnabled.value ?: false)
        _isWebSearchEnabled.value = newNotiState
        sharedPreferencesHelper.saveWebSearchEnabled(newNotiState)

    }
    fun toggleScrollers(){
        val newValue = !(_isScrollersEnabled.value ?: false)
        _isScrollersEnabled.value = newValue
        sharedPreferencesHelper.saveScrollersPreference(newValue)
    }
    fun toggleExpandableInput() {
        val newValue = !(_isExpandableInputEnabled.value ?: false)
        _isExpandableInputEnabled.value = newValue
        sharedPreferencesHelper.saveExpandableInput(newValue)
    }
    fun toggleToolsEnabled() {
        if (isRpMode()) return
        val newValue = !(_isToolsEnabled.value ?: false)
        _isToolsEnabled.value = newValue
        sharedPreferencesHelper.saveToolsPreference(newValue)
    }

    fun toggleReasoning() {
        val newValue = !(_isReasoningEnabled.value ?: false)
        _isReasoningEnabled.value = newValue
        sharedPreferencesHelper.saveReasoningPreference(newValue)
    }

    var activeChatUrl: String = "https://openrouter.ai/api/v1/chat/completions"
    var activeChatApiKey: String = ""
    // var runningCost: Double = 0.0 // Updated on successful responses

    companion object {
        val THINKING_MESSAGE = FlexibleMessage(
            role = "assistant",
            content = JsonPrimitive(ThinkingPlaceholder.TOKEN)
        )

        /** Starts every error reply; the adapter and the fragment recognise an error bubble by it. */
        const val ERROR_BUBBLE_PREFIX = "**Error:**\n---\n"
        private const val MAX_GENERATED_IMAGE_BYTES = 25L * 1024 * 1024
        private const val TITLE_SOURCE_MESSAGES = 4
        private const val TITLE_SOURCE_MESSAGE_CHARS = 700
        private const val TITLE_SOURCE_CHARS = 2000
    }

    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any?): String =
        getApplication<Application>().getString(id, *args)
    //val generatedImages = mutableMapOf<Int, String>()
    private var pendingUserImageUri: String? = null  // String (toString())
    /** Scene photos an edit has cut out of the transcript but still has to put back. */
    private val scenePhotosHeld = mutableSetOf<String>()
    private var httpClient: HttpClient
    private var lanHttpClient: HttpClient
    private var llmService: LlmService
    private val sharedPreferencesHelper: SharedPreferencesHelper = SharedPreferencesHelper(application)

    init {
        rpSwipeStore = RpSwipeStore(sharedPreferencesHelper)
        // Drop any instruct left by a killed mid-regen process.
        sharedPreferencesHelper.saveRpPendingInstruct(null)
        // A relaunch starts blank. These ids only remember a thread while this process is alive.
        sharedPreferencesHelper.saveRpDraftSessionId(ChatMode.ASK, null)
        sharedPreferencesHelper.saveRpDraftSessionId(ChatMode.RP, null)
        sharedPreferencesHelper.saveComposerDraft(ChatMode.ASK, "")
        sharedPreferencesHelper.saveComposerDraft(ChatMode.RP, "")
        _chatMode.value = sharedPreferencesHelper.getChatMode()
        viewModelScope.launch(Dispatchers.IO) {
            DemoCharacter.seedOnce(rpRepo, sharedPreferencesHelper, getApplication())
        }
        // Tell the user once if the chat database had to be replaced (see AppDatabase.build).
        viewModelScope.launch {
            dbWarmup?.join()
            if (sharedPreferencesHelper.consumeChatDbRecovered()) {
                _toastUiEvent.postValue(Event(str(R.string.notice_chat_db_recovered)))
            }
        }
        val launchEpoch = sessionEpoch
        sessionTransitionJob = viewModelScope.launch {
            try {
                dbWarmup?.join()
                refreshActiveRpCharacter()
                restoreDraftOrNewChat(_chatMode.value ?: ChatMode.ASK)
            } finally {
                // A new chat or a mode switch that cancelled this launch publishes its own id.
                if (launchEpoch == sessionEpoch) markSessionReady()
            }
        }
        httpClient = createHttpClient()
        lanHttpClient = createLanHttpClient()
        // The Settings dialog writes through its own SharedPreferencesHelper, so the change has to
        // be heard on the shared preferences file itself. The listener is kept in a field because
        // SharedPreferences holds it weakly.
        sharedPreferencesHelper.mainPrefs.registerOnSharedPreferenceChangeListener(timeoutPrefListener)
        migrateOpenRouterModels()
        allOpenRouterModels = sharedPreferencesHelper.getOpenRouterModels()
        _activeChatModel.value = sharedPreferencesHelper.getPreferenceModelnew()
        _isStreamingEnabled.value = sharedPreferencesHelper.getStreamingPreference()
        _isReasoningEnabled.value = sharedPreferencesHelper.getReasoningPreference()
        _isAdvancedReasoningOn.value = sharedPreferencesHelper.getAdvancedReasoningEnabled()
        _isScrollersEnabled.value = sharedPreferencesHelper.getScrollersPreference()
        _isVolumeScrollEnabled.value = sharedPreferencesHelper.getVolumeScrollEnabled()
        _isToolsEnabled.value = sharedPreferencesHelper.getToolsPreference()
        sharedPreferencesHelper.retireWebSearchToggleOnce()
        _isWebSearchEnabled.value = sharedPreferencesHelper.getWebSearchBoolean()
        _isExtendedDockEnabled.value = sharedPreferencesHelper.getExtPreference()
        _isExtendedTopBarEnabled.value = sharedPreferencesHelper.getExtendedTopBarEnabled()
        _isExpandableInputEnabled.value =  sharedPreferencesHelper.getExpandableInput()
        _isPresetsExtendedEnabled.value = sharedPreferencesHelper.getExtPreference2()
        _isScrollProgressEnabled.value = sharedPreferencesHelper.getScrollProgressEnabled()
        llmService = LlmService(httpClient)
        activeChatApiKey = sharedPreferencesHelper.getApiKeyFromPrefs("openrouter_api_key")
        _sortOrder.value = sharedPreferencesHelper.getSortOrder()
    }

    override fun onCleared() {
        super.onCleared()
        sharedPreferencesHelper.mainPrefs.unregisterOnSharedPreferenceChangeListener(timeoutPrefListener)
        httpClient.close()
        lanHttpClient.close()
        if (demoClientDelegate.isInitialized()) demoHttpClient.close()
    }

    fun setModel(model: String) {
        _activeChatModel.value = model
        _modelPreferenceToSave.value = model
    }

    fun getCurrentSessionId(): Long? = currentSessionId

    /**
     * The open row changed. Before [sessionReady], callers still write [currentSessionId]
     * but the composer waits: the first id is the restored thread, not a switch.
     * [promoted] is an unsaved chat receiving the id the database just minted.
     */
    private fun assignOpenSession(id: Long?, promoted: Boolean = false) {
        // An edit started before the first save. The unsaved slot and the new id are the same chat.
        if (promoted && id != null && composerEditKey == ComposerDrafts.NEW) {
            val draft = composerEditDrafts.remove(ComposerDrafts.NEW)
            composerEditKey = ComposerDrafts.key(id)
            if (draft != null) composerEditDrafts[composerEditKey!!] = draft
            sharedPreferencesHelper.setChatForkEditing(id, true, draft.orEmpty())
        }
        currentSessionId = id
        if (_sessionReady.value != true) return
        val publish = {
            if (promoted) openSessionPromoted = true
            _openSessionId.value = id
        }
        if (Looper.myLooper() == Looper.getMainLooper()) publish()
        else viewModelScope.launch(Dispatchers.Main.immediate) { publish() }
    }

    private fun markSessionReady() {
        if (_sessionReady.value == true) return
        _openSessionId.value = currentSessionId
        _sessionReady.value = true
    }

    /** True once, when the open chat just gained its first saved id. */
    fun consumeOpenSessionPromoted(): Boolean {
        val was = openSessionPromoted
        openSessionPromoted = false
        return was
    }

    /** Cancel overlapping session transitions (load / mode switch / character start / cold restore). */
    private fun beginSessionTransition(block: suspend () -> Unit): Job {
        // Restore mid-regen first so a truncated hole isn't autosaved into the old session.
        cancelCurrentRequest(restoreSwipeAlt = true)
        // The snapshot is taken here. The epoch bump must not drop it: the load waits until it lands.
        autoSaveChat(allowNetworkTitle = false)
        sessionEpoch++
        val epoch = sessionEpoch
        rpMemoryJob?.cancel() // Its note was written for the chat being left.
        sessionTransitionJob?.cancel()
        val job = viewModelScope.launch {
            try {
                // The leaving chat's snapshot is already queued. Land it before this block
                // changes mode, character, or the open transcript.
                awaitPendingSaves()
                block()
            } finally {
                // The cold-start launch skips ready when a transition cancels it.
                if (epoch == sessionEpoch) markSessionReady()
            }
        }
        sessionTransitionJob = job
        return job
    }

    /**
     * Clear the open transcript without cancelling [sessionTransitionJob] or bumping [sessionEpoch].
     * Used inside an active transition so nested clears don't cancel the parent job.
     *
     * @param clearDraft when false, leave the mode's draft session id alone (keeps a parked
     * keepDraftId from LLM-mismatch ephemeral UI, or a just-parked prior RP session).
     */
    private fun clearOpenTranscript(clearDraft: Boolean = true) {
        cancelCurrentRequest(restoreSwipeAlt = false, clearAwaiting = false)
        if (clearDraft) {
            val mode = _chatMode.value ?: ChatMode.ASK
            sharedPreferencesHelper.saveRpDraftSessionId(mode, null)
        }
        _isChatLoading.value = false
        _chatMessages.value = emptyList()
        pendingUserImageUri = null
        assignOpenSession(null)
        preservedSessionCharacterId = null
        clearForkMemory()
        clearRpSwipeMemory()
    }

    suspend fun getCurrentSessionTitle(): String? {
        val sessionId = currentSessionId ?: return null
        val session = repository.getSessionById(sessionId) ?: return null
        return session.title
    }

    /**
     * Transcript and identity captured when a save is scheduled. Later code must not re-read
     * the open chat: by then the user may be looking at a different one.
     */
    /** The other branch, captured with the transcript. A later save must not read the live one. */
    private data class CapturedFork(
        val index: Int,
        val anchor: Int,
        val messages: List<FlexibleMessage>,
    )

    private data class ChatPersistSnapshot(
        val epoch: Long,
        val sessionId: Long?,
        val mode: String,
        val characterId: Long?,
        val isLlm: Boolean,
        val model: String,
        val messages: List<FlexibleMessage>,
        val draftFacts: String?,
        /** Which chat this mode would reopen, at the moment the snapshot was taken. */
        val draftAtCapture: Long?,
        val fork: CapturedFork?,
        val swipe: RpSwipeState?,
        /**
         * The line already in the field when Edit cut the turn. Null when this save is not
         * that edit. Empty is a blank field, which Cancel still has to put back.
         */
        val editDraft: String?,
    )

    private fun captureSnapshot(): ChatPersistSnapshot {
        val id = currentSessionId
        val mode = sessionModeValue()
        return ChatPersistSnapshot(
            epoch = sessionEpoch,
            sessionId = id,
            mode = mode,
            characterId = sessionCharacterId(),
            isLlm = sessionIsLlm(),
            model = _activeChatModel.value ?: "",
            messages = (_chatMessages.value ?: emptyList()).map { it.copy() },
            draftFacts = if (id == null) draftRpFacts else null,
            draftAtCapture = sharedPreferencesHelper.getRpDraftSessionId(ChatMode.fromStorage(mode)),
            fork = if (stashedForkTail.isEmpty() || forkIndex < 0) null else CapturedFork(
                index = forkIndex,
                anchor = forkAnchorAssistantIndex,
                messages = stashedForkTail.map { it.copy() },
            ),
            swipe = rpSwipeState.takeIf { it.alts.isNotEmpty() },
            editDraft = composerEditDrafts[ComposerDrafts.key(id)]
                .takeIf { composerEditKey == ComposerDrafts.key(id) },
        )
    }

    fun saveCurrentChat(title: String, saveAsNew: Boolean = false) {
        launchChatSave(captureSnapshot(), saveAsNew, title)
    }

    /**
     * Writes [snap] even if the open chat has moved on. A newer snapshot of the same chat
     * supersedes this one. The row is attached to the screen only when this is still that chat.
     * Queued on the main looper, not started inline, so a leave that bumps the epoch in this
     * same call is visible before a title request starts.
     */
    private fun launchChatSave(snap: ChatPersistSnapshot, saveAsNew: Boolean, preparedTitle: String?) {
        val ticket = chatSaveSerial.claim(snap.sessionId, snap.epoch, saveAsNew)
        val job = viewModelScope.launch(Dispatchers.Main) {
            try {
                val title = preparedTitle ?: titleForSnapshot(snap, allowNetworkTitle = true)
                if (title.isBlank()) return@launch
                chatSaveMutex.withLock {
                    writeSnapshot(ticket, snap, saveAsNew, title)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Could not save the open chat", e)
                _toastUiEvent.postValue(Event(str(R.string.notice_chat_save_failed)))
            }
        }
        synchronized(pendingSaves) { pendingSaves.add(job) }
        job.invokeOnCompletion { synchronized(pendingSaves) { pendingSaves.remove(job) } }
    }

    /** Opening a chat waits until scheduled saves have landed, so it cannot read a stale row. */
    private suspend fun awaitPendingSaves() {
        while (true) {
            val job = synchronized(pendingSaves) { pendingSaves.firstOrNull { it.isActive } } ?: return
            job.join()
        }
    }

    private suspend fun writeSnapshot(
        ticket: ChatSaveSerial.Ticket,
        snap: ChatPersistSnapshot,
        saveAsNew: Boolean,
        title: String
    ) {
        if (!chatSaveSerial.isCurrent(ticket)) return
        val existing = if (saveAsNew) null else snap.sessionId ?: chatSaveSerial.mintedId(ticket)
        val rowExists = existing != null && repository.getSessionById(existing) != null
        val outcome = ChatSaveGate.persist(
            ticketCurrent = true,
            saveAsNew = saveAsNew,
            existingId = existing,
            rowExists = rowExists
        )
        val existingId = when (outcome) {
            ChatSaveGate.Outcome.Abort -> return
            ChatSaveGate.Outcome.ProceedExisting -> existing
            ChatSaveGate.Outcome.ProceedAllocateNew -> null
        }
        // Encoding a long transcript is the heavy part, and autosave fires as a reply lands.
        // The JPEG stays in the row: a reopened chat has no file URI until this puts it back.
        val messagesToSave = withContext(Dispatchers.Default) {
            snap.messages.map { message ->
                message.copy(content = MessageContent.forStorage(message.content, message.imageUri))
            }
        }
        if (!chatSaveSerial.isCurrent(ticket)) return
        val session = ChatSession(
            id = existingId ?: 0L,
            title = title,
            modelUsed = snap.model,
            mode = snap.mode,
            characterId = snap.characterId,
            isLlm = snap.isLlm
        )
        val chatMessages = withContext(Dispatchers.Default) {
            messagesToSave.map {
                ChatMessage(
                    sessionId = existingId ?: 0L,
                    role = it.role,
                    content = json.encodeToString(JsonElement.serializer(), it.content)
                )
            }
        }
        if (!chatSaveSerial.isCurrent(ticket)) return
        // Names before the write, so a picture this save dropped can be removed afterwards.
        val previousPhotos = if (existingId != null) repository.scenePhotoNames(existingId) else emptyList()
        // Null: the open chat was deleted while this save waited. Nothing was written.
        val sessionId = ChatSessionSaver.save(repository, existingId, session, chatMessages)
            ?: return
        if (existingId == null) chatSaveSerial.noteMinted(ticket, sessionId)
        // A newer snapshot may already be waiting, and it holds the mutex next. The row
        // just written is still this snapshot. Its notes have to land before we return,
        // or a kill in between keeps the transcript and drops the fork.
        if (ChatSaveGate.recordSideData(rowWritten = true)) {
            snap.draftFacts?.let { facts ->
                sharedPreferencesHelper.saveRpFacts(sessionId, facts)
                if (sessionEpoch == snap.epoch) draftRpFacts = null
            }
            // The row did not exist when this snapshot was taken, so the fork and the other
            // reply versions had nowhere to be written. Leaving the chat must not drop them,
            // and the mode the user left should reopen this chat rather than the one before it.
            if (snap.sessionId == null) {
                persistCapturedFork(sessionId, snap.fork)
                persistCapturedSwipe(sessionId, snap.swipe)
                parkMintedDraft(sessionId, snap)
                // Edit was cut before this chat had a row. The flag has to land with the fork,
                // or coming back shows the other branch with no Cancel.
                if (snap.editDraft != null) {
                    sharedPreferencesHelper.setChatForkEditing(sessionId, true, snap.editDraft)
                }
            }
        }
        if (!chatSaveSerial.isCurrent(ticket)) return
        releaseDroppedScenePhotos(previousPhotos, messagesToSave, snap.swipe)
        if (
            ChatSaveGate.decide(
                epochAtSchedule = snap.epoch,
                currentEpoch = sessionEpoch,
                openSessionId = snap.sessionId,
                liveSessionId = currentSessionId,
                rowExists = true,
                saveAsNew = saveAsNew
            ) == ChatSaveGate.Outcome.Abort
        ) {
            return
        }
        assignOpenSession(sessionId, promoted = snap.sessionId == null)
        sharedPreferencesHelper.saveRpDraftSessionId(
            ChatMode.fromStorage(snap.mode),
            sessionId
        )
        // First autosave often mints the id after swipe alts were seeded in-memory only.
        persistRpSwipeState()
        persistForkToPrefs()
    }

    fun autoSaveChat() = autoSaveChat(allowNetworkTitle = true)

    /**
     * @param allowNetworkTitle false when the user is leaving this chat. The snapshot still
     * writes; a title request must not hold the next chat's load on the network.
     */
    private fun autoSaveChat(allowNetworkTitle: Boolean) {
        val snap = captureSnapshot()
        val hasAssistant = snap.messages.any { it.role == "assistant" }
        when (
            ChatSaveGate.autoSaveKind(
                sessionId = snap.sessionId,
                hasAssistant = hasAssistant,
                messagesEmpty = snap.messages.isEmpty()
            )
        ) {
            ChatSaveGate.AutoSaveKind.Skip -> return
            ChatSaveGate.AutoSaveKind.ReuseExisting,
            ChatSaveGate.AutoSaveKind.FirstSaveNeedsAssistant -> Unit
        }
        val ticket = chatSaveSerial.claim(snap.sessionId, snap.epoch, saveAsNew = false)
        val job = viewModelScope.launch(Dispatchers.Main) {
            try {
                val title = titleForSnapshot(snap, allowNetworkTitle)
                if (title.isBlank()) return@launch
                chatSaveMutex.withLock {
                    writeSnapshot(ticket, snap, saveAsNew = false, title)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Could not save the open chat", e)
                _toastUiEvent.postValue(Event(str(R.string.notice_chat_save_failed)))
            }
        }
        synchronized(pendingSaves) { pendingSaves.add(job) }
        job.invokeOnCompletion { synchronized(pendingSaves) { pendingSaves.remove(job) } }
    }

    private suspend fun titleForSnapshot(snap: ChatPersistSnapshot, allowNetworkTitle: Boolean): String {
        val sessionId = snap.sessionId
        if (sessionId != null) {
            val existing = repository.getSessionById(sessionId) ?: return ""
            val reused = existing.title
            if (reused.isNotBlank()) {
                return if (snap.mode == ChatMode.RP.storageValue) {
                    maybeUpgradeRpSessionTitle(reused, snap.messages, snap.isLlm)
                } else {
                    reused
                }
            }
        }
        if (snap.messages.isEmpty() || snap.messages.none { it.role == "assistant" }) return ""
        if (snap.mode == ChatMode.RP.storageValue) {
            return buildRpAutosaveTitle(snap.messages, snap.isLlm)
        }
        // The chat was left, or this flush is only so the next screen can load. A local title
        // is enough; the name can be edited later.
        if (!allowNetworkTitle || sessionEpoch != snap.epoch) return localChatTitle(snap.messages)
        val title = try {
            getSuggestedChatTitle(snap.messages)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return if (title.isNullOrBlank()) localChatTitle(snap.messages) else title
    }

    private fun localChatTitle(messages: List<FlexibleMessage>): String {
        val firstUserMsg = messages.firstOrNull { it.role == "user" }
        if (firstUserMsg != null) {
            val raw = getMessageText(firstUserMsg.content).trim()
            if (raw.isNotEmpty()) return if (raw.length > 60) raw.take(57) + "..." else raw
        }
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date())
    }

    /** RP session title from character/LLM label plus optional first user snippet. */
    private suspend fun buildRpAutosaveTitle(
        messages: List<FlexibleMessage>,
        isLlm: Boolean
    ): String {
        val firstUserMsg = messages.firstOrNull { it.role == "user" }
        val raw = firstUserMsg?.let { getMessageText(it.content).trim() }.orEmpty()
        if (isLlm) {
            return when {
                raw.length > 60 -> raw.take(57) + "..."
                raw.isNotBlank() -> raw
                else -> rpDelegate.sessionTitle(null)
            }
        }
        val base = rpDelegate.sessionTitle(rpDelegate.getActiveCharacter())
        return when {
            raw.isBlank() -> base
            raw.length > 40 -> "$base — ${raw.take(37)}..."
            else -> "$base — $raw"
        }
    }

    /**
     * After greeting autosave locked the bare character/LLM name, upgrade once the first user
     * turn exists — but never overwrite a user-renamed title.
     */
    private suspend fun maybeUpgradeRpSessionTitle(
        currentTitle: String,
        messages: List<FlexibleMessage>,
        isLlm: Boolean
    ): String {
        val bare = if (isLlm) {
            rpDelegate.sessionTitle(null)
        } else {
            rpDelegate.sessionTitle(rpDelegate.getActiveCharacter())
        }
        if (currentTitle != bare) return currentTitle
        return buildRpAutosaveTitle(messages, isLlm)
    }

    fun loadChat(sessionId: Long) {
        _rpThreadOpenedEvent.value = Event(Unit)
        beginSessionTransition {
            loadChatInternal(sessionId)
        }
    }

    private suspend fun loadChatInternal(sessionId: Long) {
        awaitPendingSaves()
        if (networkJob?.isActive == true) {
            _isChatLoading.value = false
            return
        }
        _isChatLoading.value = true
        try {
            val previousMode = _chatMode.value ?: ChatMode.ASK
            val previousSessionId = currentSessionId

            // Parallel fetch for efficiency
            val (session, messages) = coroutineScope {
                val sessionDeferred = async { repository.getSessionById(sessionId) }
                val messagesDeferred = async { repository.getMessagesForSession(sessionId) }
                sessionDeferred.await() to messagesDeferred.await()
            }

            assignOpenSession(sessionId)
            draftRpFacts = null
            val parsedMessages = messages.map {
                val parsed = try {
                    json.parseToJsonElement(it.content)
                } catch (e: Exception) {
                    JsonPrimitive(it.content)
                }
                val kept = MessageContent.unwrap(parsed)
                FlexibleMessage(
                    role = it.role,
                    content = kept.body,
                    imageUri = kept.fileUri
                )
            }
            val healed = withContext(Dispatchers.IO) { healPhotoLinks(parsedMessages) }
            _chatMessages.value = healed

            session?.let {
                val loadedMode = it.chatMode()
                if (previousSessionId != null && previousSessionId != sessionId) {
                    sharedPreferencesHelper.saveRpDraftSessionId(previousMode, previousSessionId)
                }
                sharedPreferencesHelper.saveRpDraftSessionId(loadedMode, sessionId)
                if (loadedMode == ChatMode.RP) {
                    _chatMode.value = ChatMode.RP
                    sharedPreferencesHelper.saveChatMode(ChatMode.RP)
                    sharedPreferencesHelper.saveRpLlmMode(it.isLlm)
                    if (it.isLlm) {
                        // LLM session rows store no characterId — keep any parked id for LLM-off restore.
                        preservedSessionCharacterId = null
                    } else {
                        val sessionCharId = it.characterId
                        val validCharId = sessionCharId?.let { cid ->
                            if (rpRepo.getCharacterById(cid) != null) cid else null
                        }
                        preservedSessionCharacterId =
                            if (sessionCharId != null && validCharId == null) sessionCharId else null
                        sharedPreferencesHelper.saveRpActiveCharacterId(validCharId)
                        if (sessionCharId != null && validCharId == null) {
                            _toastUiEvent.value =
                                Event(getApplication<Application>().getString(R.string.rp_orphan_character))
                        }
                    }
                } else {
                    preservedSessionCharacterId = null
                    _chatMode.value = ChatMode.ASK
                    sharedPreferencesHelper.saveChatMode(ChatMode.ASK)
                }
                refreshActiveRpCharacter()
                _activeChatModel.value = it.modelUsed
                _modelPreferenceToSave.value = it.modelUsed
            }
            loadForkFromPrefs(sessionId)
            loadRpSwipeForSession(sessionId)
            // A cache or Downloads link was copied into app files. Save that, or the next open
            // repeats the copy and a backup still points at the dead link.
            session?.title?.takeIf { it.isNotBlank() && healed !== parsedMessages }?.let {
                saveCurrentChat(it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Could not open chat $sessionId", e)
            _toastUiEvent.value = Event(str(R.string.notice_chat_open_failed))
        } finally {
            _isChatLoading.value = false
        }
    }

    /**
     * Point each picture at a file we still have. A JPEG already in the message rebuilds the
     * file after the cache copy, or the Downloads copy, is gone.
     */
    private fun healPhotoLinks(messages: List<FlexibleMessage>): List<FlexibleMessage> {
        val app = getApplication<Application>()
        var changed = false
        val out = messages.map { message ->
            val embedded = ScenePhoto.bytesFromDataUrl(MessageContent.imageUrl(message.content).orEmpty())
            val settled = ScenePhoto.settle(app, message.imageUri, embedded)
            if (settled == message.imageUri) message
            else {
                changed = true
                message.copy(imageUri = settled)
            }
        }
        return if (changed) out else messages
    }

    /** A picture this save no longer names. Another chat, or another version of this reply, keeps it. */
    private suspend fun releaseDroppedScenePhotos(
        previous: List<String>,
        messages: List<FlexibleMessage>,
        swipe: RpSwipeState?,
    ) {
        if (previous.isEmpty()) return
        val kept = messages.mapNotNull { ScenePhoto.sceneFileName(it.imageUri) }.toSet() +
            swipePictureNames(swipe)
        val dropped = previous.filter { it !in kept }
        if (dropped.isEmpty()) return
        val app = getApplication<Application>()
        val held = synchronized(scenePhotosHeld) { scenePhotosHeld.toSet() }
        val unused = ScenePhoto.scenePhotosSafeToDelete(
            dropped,
            held,
            ScenePhoto.sceneFileName(pendingUserImageUri),
        ).filter { !repository.scenePhotoStillUsed(it) }
        ScenePhoto.deleteSceneFiles(app, unused)
    }

    /**
     * Keep [uri]'s file through the save that drops the message being edited. Roleplay cuts
     * the turn before the picture is staged, and that save used to delete the JPEG.
     */
    fun holdScenePhoto(uri: String?) {
        val name = ScenePhoto.sceneFileName(uri) ?: return
        synchronized(scenePhotosHeld) { scenePhotosHeld.add(name) }
    }

    /** The staged photo is this same file. A later save may drop it once the composer lets go. */
    fun releaseHeldScenePhoto(uri: String?) {
        val name = ScenePhoto.sceneFileName(uri) ?: return
        synchronized(scenePhotosHeld) { scenePhotosHeld.remove(name) }
    }

    /**
     * The edit left before the photo was staged, or a new file replaced this one.
     * A message that still names it, or the composer, keeps the file.
     */
    fun discardHeldScenePhoto(uri: String?) {
        val name = ScenePhoto.sceneFileName(uri) ?: return
        synchronized(scenePhotosHeld) { scenePhotosHeld.remove(name) }
        if (name == ScenePhoto.sceneFileName(pendingUserImageUri)) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            if (!repository.scenePhotoStillUsed(name)) ScenePhoto.deleteSceneFiles(app, listOf(name))
        }
    }

    /** Bumped when the open chat changes, so a photo read for one edit cannot land on the next. */
    fun openChatEpoch(): Long = sessionEpoch

    /** True when some saved message still points at this scene photo. */
    suspend fun scenePhotoStillUsed(name: String): Boolean = repository.scenePhotoStillUsed(name)

    fun onModelPreferenceSaved() {
        _modelPreferenceToSave.value = null
    }

    /** Messages worth writing out. Image-only turns stay when [includeImages] is set. */
    private fun messagesForExport(includeImages: Boolean): List<FlexibleMessage>? {
        val messages = _chatMessages.value ?: return null
        return messages.filter { message ->
            val contentText = getMessageText(message.content).trim()
            val hasText = contentText.isNotEmpty() && !ThinkingPlaceholder.matches(contentText)
            if (!includeImages) return@filter hasText
            val hasImage = when (message.role) {
                "user" -> MessageContent.hasImage(message.content)
                "assistant" -> !message.imageUri.isNullOrEmpty()
                else -> false
            }
            hasText || hasImage
        }
    }

    fun getFormattedChatHistoryTxt(): String {
        val messages = messagesForExport(includeImages = false) ?: return ""

        val currentModel = _activeChatModel.value ?: "Unknown"

        return buildString {
            append("Chat with $currentModel")
            append("\n\n")

            messages.forEachIndexed { index, message ->
                val rawText = getMessageText(message.content).trim()
                val contentText = stripMarkdown(rawText)  // Converts MD tables/images/etc. to plain text

                when (message.role) {
                    "user" -> {
                        append("👤 User:\n\n")
                        append(contentText)
                    }
                    "assistant" -> {
                        append("🤖 Assistant:\n\n")
                        append(contentText)
                    }
                }

                if (index < messages.size - 1) {
                    append("\n\n---\n\n")
                }
            }
        }
    }

    fun saveTxtToDownloads(rawTxt: String) = writeDownload(
        success = str(R.string.save_txt_ok),
        failure = { str(R.string.save_txt_failed, it.message) },
    ) {
        saveFileToDownloads("chat-${System.currentTimeMillis()}.txt", rawTxt, "text/plain")
    }
    fun getFormattedChatHistory(): String = formatRoleTranscript(stripMarkdown = false)

    fun getFormattedChatHistoryPlainText(): String = formatRoleTranscript(stripMarkdown = true)

    private fun formatRoleTranscript(stripMarkdown: Boolean): String {
        return messagesForExport(includeImages = false)?.mapNotNull { message ->
            val text = getMessageText(message.content).trim().let {
                if (stripMarkdown) stripMarkdown(it) else it
            }
            when (message.role) {
                "user" -> "User: $text"
                "assistant" -> "AI: $text"
                else -> null
            }
        }?.joinToString("\n\n") ?: ""
    }

    // Helper function to strip Markdown using CommonMark
    private fun stripMarkdown(text: String): String {
        val parser = Parser.builder().build()
        val document = parser.parse(text)
        val renderer = TextContentRenderer.builder().build()
        return renderer.render(document).trim()
    }

    fun cancelCurrentRequest(restoreSwipeAlt: Boolean = true, clearAwaiting: Boolean = true) {
        // Capture before clearing pending — mode may already be flipping away from RP.
        val wasRpRegen = pendingRpSwipeAppend
        val discardPartial = discardableRpAssistantInFlight
        pendingRpSwipeAppend = false
        discardableRpAssistantInFlight = false
        rpPrepJob?.cancel()
        rpPrepJob = null
        rpRewriteJob?.cancel()
        rpRewriteJob = null
        networkJob?.cancel()
        // Drop any partial still queued for the main thread so it can't land after Stop.
        activeStreamPump?.cancel()
        activeStreamPump = null
        lanFetchJob?.cancel()
        sharedPreferencesHelper.saveRpPendingInstruct(null)
        if (restoreSwipeAlt && wasRpRegen) {
            // Restore immediately so Fragment autosave-on-awaiting-clear cannot persist a hole.
            restoreRpSwipeAltIfMissingAssistant()
            rpSwipeRestoreToken++
        } else if (restoreSwipeAlt && discardPartial) {
            // Normal RP send Stop mid-stream: drop the partial so it isn't autosaved as canon.
            discardIncompleteRpAssistantAfterLastUser()
            rpSwipeRestoreToken++
        } else {
            rpSwipeRestoreToken++
        }
        // Skip when clearing an already-idle transcript (clearOpenTranscript) so we don't
        // re-fire Fragment autosave into an emptied / null sessionId mid-transition.
        if (clearAwaiting && _isAwaitingResponse.value == true) {
            _isAwaitingResponse.value = false
        }
    }

    /**
     * After cancel/error mid-regen, put the selected swipe alt back as the reply.
     * Covers both pre-token (placeholder) and mid-stream (partial) cases so Stop does not
     * leave a truncated bubble that later gets stashed as a new alt.
     * Does not require [isRpMode] so Ask↔RP mid-regen can restore before the transcript is swapped.
     */
    private fun restoreRpSwipeAltIfMissingAssistant() {
        val messages = _chatMessages.value?.toMutableList() ?: return
        while (messages.isNotEmpty() && isAssistantPlaceholder(messages.last())) {
            messages.removeAt(messages.lastIndex)
        }
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        if (lastUserIndex < 0) return
        val alt = rpSwipeState.alts.getOrNull(rpSwipeState.index)
            ?: rpRegenRestoreFallback
            ?: return
        rpRegenRestoreFallback = null
        val lastAssistantIndex = messages.indexOfLast { it.role == "assistant" && !isAssistantPlaceholder(it) }
        val picture = RpSwipeRules.pictureForAlt(
            rpSwipeState.pictureUris,
            rpSwipeState.alts.size,
            rpSwipeState.index,
        )
        if (lastAssistantIndex > lastUserIndex) {
            // Mid-stream regen left a partial — replace with the stashed full alt.
            messages[lastAssistantIndex] = RpContinuation.withVersion(
                messages[lastAssistantIndex],
                alt,
                picture,
            )
        } else {
            messages.add(
                FlexibleMessage(
                    role = "assistant",
                    content = JsonPrimitive(alt),
                    imageUri = picture?.takeIf { it.isNotEmpty() },
                )
            )
        }
        streamingAssistantIndex = -1
        _chatMessages.value = messages
        updateRpSwipeNav()
        autoSaveChat()
    }

    /** Drop a partial/thinking assistant after the last user turn (Stop mid-stream on a normal send). */
    private fun discardIncompleteRpAssistantAfterLastUser() {
        val messages = _chatMessages.value?.toMutableList() ?: return
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        if (lastUserIndex < 0) return
        var changed = false
        while (messages.lastIndex > lastUserIndex) {
            val last = messages.last()
            if (last.role != "assistant") break
            messages.removeAt(messages.lastIndex)
            changed = true
        }
        if (!changed) return
        streamingAssistantIndex = -1
        _chatMessages.value = messages
    }
    private var toolCallsHandledForTurn = false
    private var toolRecursionDepth = 0
    fun sendUserMessage(
        userContent: JsonElement,
        systemMessage: String? = null,
        clearRpSwipeOnStart: Boolean = false,
        /** Roleplay's Continue: [userContent] is a hidden prompt, and the reply grows the last bubble. */
        continueInPlace: Boolean = false,
        /**
         * The staged file at the moment send was asked for. Roleplay clears [pendingUserImageUri]
         * before this runs; [useCapturedImageUri] keeps that file on the message anyway.
         */
        capturedImageUri: String? = null,
        useCapturedImageUri: Boolean = false,
    ): Boolean {
        rpRewriteJob?.cancel()
        rpRewriteJob = null
        // The field still belongs to the chat on screen. Waiting, then sending, used to
        // report success first (the composer cleared, and an open edit closed) and append
        // the line to whichever chat finished loading. Stop could not cancel that turn.
        if (ChatSend.decide(sessionTransitionJob?.isActive == true) == ChatSend.Outcome.Keep) {
            _toastUiEvent.postValue(Event(str(R.string.notice_chat_still_opening)))
            return false
        }
        toolCallsHandledForTurn = false
        toolRecursionDepth = 0
        var userMessage = FlexibleMessage(role = "user", content = userContent)

        // Keep the URI if the send is refused, so the picture is still staged.
        val attachedUri = ScenePhoto.uriForTurn(useCapturedImageUri, capturedImageUri, pendingUserImageUri)
        if (attachedUri != null) userMessage = userMessage.copy(imageUri = attachedUri)

        if (!bindChatEndpoint()) {
            _isAwaitingResponse.value = false
            return false
        }
        // A photo captured for this turn must not clear a different picture staged since.
        if (!useCapturedImageUri || pendingUserImageUri == attachedUri) {
            pendingUserImageUri = null
        }

        // Only wipe alts once the send is known to proceed (after early returns above).
        if (clearRpSwipeOnStart && isRpMode()) {
            pendingRpSwipeAppend = false
            clearRpSwipeAlts()
        }

        val thinkingMessage = THINKING_MESSAGE
        val messagesForApiRequest = mutableListOf<FlexibleMessage>()

        if (systemMessage != null) {
            messagesForApiRequest.add(
                FlexibleMessage(
                    role = "system",
                    content = JsonPrimitive(systemMessage)
                )
            )
        }

        _chatMessages.value?.let { history ->
            messagesForApiRequest.addAll(history)
        }

        // Continue: the model sees the prompt, the transcript doesn't. When the thread ends on a
        // reply the new words go into it; when it ends on the user's turn there is nothing to
        // extend, so the character simply answers that turn (no prompt needed).
        val history = _chatMessages.value.orEmpty()
        val lastReply = history.lastOrNull()?.takeIf { it.role == "assistant" && !isAssistantPlaceholder(it) }
        val inPlace = continueInPlace && lastReply != null
        // The transcript keeps a bare photo. toApiMessage adds the scene line on the wire,
        // including when this picture comes back on a later turn or a rewrite.
        if (!continueInPlace || inPlace) {
            messagesForApiRequest.add(userMessage)
        }
        trimMessagesForApiMemory(messagesForApiRequest)

        val uiMessages = history.toMutableList()
        if (inPlace) {
            continuationBase = getMessageText(lastReply!!.content)
            streamingAssistantIndex = uiMessages.lastIndex
        } else {
            continuationBase = null
            if (!continueInPlace) uiMessages.add(userMessage)
            uiMessages.add(thinkingMessage)
            streamingAssistantIndex = uiMessages.lastIndex
            markForkAnchorIfPending(streamingAssistantIndex)
            _chatMessages.value = uiMessages
        }
        _isAwaitingResponse.value = true
        _userScrolledDuringStream.value = false
        // Mark only non-regen RP streams so Stop can discard the partial (not a finished prior reply).
        // A Continue keeps whatever it wrote before Stop: the earlier text is not the partial.
        discardableRpAssistantInFlight = isRpMode() && !pendingRpSwipeAppend && !inPlace

        startChatTurn(messagesForApiRequest, thinkingMessage)
        return true
    }
    /**
     * Voice input when the user picked Cloud, Grok, or Local in Settings > Voice (or Phone fell
     * back): OpenRouter transcription, xAI `POST /v1/stt`, or the LAN server's OpenAI-style
     * `/v1/audio/transcriptions`. Failure carries a message ready for a toast.
     */
    suspend fun transcribeAudioForInput(audioBytes: ByteArray, audioFormat: String, fileName: String): Result<String> {
        val engine = VoiceEngine.fromKey(sharedPreferencesHelper.getVoiceInputProvider()).let { picked ->
            // Phone with no recognizer may resolve to Grok/Cloud at tap time; honor prefs + fallbacks.
            if (picked == VoiceEngine.DEVICE) {
                VoiceInput.resolve(getApplication(), sharedPreferencesHelper) ?: picked
            } else {
                picked
            }
        }
        return withContext(Dispatchers.IO) {
            // Not runCatching: that would turn the caller's cancellation into a failed transcription.
            try {
                val response = when (engine) {
                    VoiceEngine.GROK -> {
                        val xaiKey = sharedPreferencesHelper.getApiKeyFromPrefs(SharedPreferencesHelper.XAI_API_KEY_ALIAS)
                        if (xaiKey.isBlank()) error("Set an xAI API key in Settings > Voice")
                        val language = java.util.Locale.getDefault().language.ifBlank { "en" }
                        // Options before file — xAI ignores fields after `file`.
                        httpClient.submitFormWithBinaryData(
                            url = "https://api.x.ai/v1/stt",
                            formData = formData {
                                append("model", VoiceEngine.GROK_STT_MODEL)
                                append("format", "true")
                                append("language", language)
                                append("file", audioBytes, Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                    append(HttpHeaders.ContentType, "audio/$audioFormat")
                                })
                            }
                        ) {
                            header("Authorization", "Bearer $xaiKey")
                        }
                    }
                    VoiceEngine.LAN -> {
                        val modelId = sharedPreferencesHelper.getVoiceInputModel()
                        if (modelId.isBlank()) error("Set a voice model in Settings > Voice")
                        val lanEndpoint = sharedPreferencesHelper.getLanEndpoint()
                        if (lanEndpoint.isNullOrBlank()) error("Local server not configured")
                        lanHttpClient.submitFormWithBinaryData(
                            url = "$lanEndpoint/v1/audio/transcriptions",
                            formData = formData {
                                append("file", audioBytes, Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                    append(HttpHeaders.ContentType, "audio/$audioFormat")
                                })
                                append("model", modelId)
                            }
                        ) {
                            header("Authorization", "Bearer ${sharedPreferencesHelper.getLanApiKeyForRequest()}")
                        }
                    }
                    else -> {
                        val modelId = sharedPreferencesHelper.getVoiceInputModel()
                        if (modelId.isBlank()) error("Set a voice model in Settings > Voice")
                        if (activeChatApiKey.isBlank()) error("OpenRouter API key not set")
                        httpClient.post("https://openrouter.ai/api/v1/audio/transcriptions") {
                            header("Authorization", "Bearer $activeChatApiKey")
                            contentType(ContentType.Application.Json)
                            setBody(buildJsonObject {
                                put("model", JsonPrimitive(modelId))
                                putJsonObject("input_audio") {
                                    put("data", JsonPrimitive(Base64.getEncoder().encodeToString(audioBytes)))
                                    put("format", JsonPrimitive(audioFormat))
                                }
                            })
                        }
                    }
                }
                if (!response.status.isSuccess()) error("Transcription failed: ${response.status.value}")
                Result.success(response.body<JsonObject>()["text"]?.jsonPrimitive?.content?.trim().orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
    }
    fun sendTranscriptionOpenRouter(audioBytes: ByteArray, audioFormat: String) {
        val modelId = _activeChatModel.value ?: return
        deliverTranscription {
            val response = httpClient.post("https://openrouter.ai/api/v1/audio/transcriptions") {
                header("Authorization", "Bearer $activeChatApiKey")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("model", JsonPrimitive(modelId))
                    putJsonObject("input_audio") {
                        put("data", JsonPrimitive(Base64.getEncoder().encodeToString(audioBytes)))
                        put("format", JsonPrimitive(audioFormat))
                    }
                })
            }
            transcriptionText(response, R.string.transcription_failed)
        }
    }

    fun sendTranscriptionLan(audioBytes: ByteArray, audioFormat: String, fileName: String) {
        val modelId = _activeChatModel.value ?: return
        val lanEndpoint = sharedPreferencesHelper.getLanEndpoint() ?: return
        deliverTranscription {
            val response = lanHttpClient.submitFormWithBinaryData(
                url = "$lanEndpoint/v1/audio/transcriptions",
                formData = formData {
                    append("file", audioBytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        append(HttpHeaders.ContentType, "audio/$audioFormat")
                    })
                    append("model", modelId)
                }
            ) {
                header("Authorization", "Bearer ${sharedPreferencesHelper.getLanApiKeyForRequest()}")
            }
            transcriptionText(response, R.string.transcription_lan_failed)
        }
    }

    /** Thinking bubble, then the transcribed text, or the same cancel and error path both calls used. */
    private fun deliverTranscription(fetch: suspend () -> String) {
        val thinkingMessage = THINKING_MESSAGE
        val uiMessages = _chatMessages.value?.toMutableList() ?: mutableListOf()
        uiMessages.add(thinkingMessage)
        streamingAssistantIndex = uiMessages.lastIndex
        markForkAnchorIfPending(streamingAssistantIndex)
        _chatMessages.value = uiMessages
        _isAwaitingResponse.value = true

        startNetworkJob {
            try {
                val transcribedText = fetch()
                withContext(Dispatchers.Main) {
                    updateMessages { list ->
                        putAssistantMessage(
                            list,
                            thinkingMessage,
                            FlexibleMessage(
                                role = "assistant",
                                content = JsonPrimitive(transcribedText)
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    removeAssistantPlaceholder(thinkingMessage)
                }
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    handleError(e, thinkingMessage)
                }
            } finally {
                if (networkJob === coroutineContext[Job]) {
                    _isAwaitingResponse.postValue(false)
                    _scrollToBottomEvent.postValue(Event(Unit))
                }
            }
        }
    }

    private suspend fun transcriptionText(
        response: io.ktor.client.statement.HttpResponse,
        @androidx.annotation.StringRes failureText: Int,
    ): String {
        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (e: CancellationException) { throw e } catch (_: Exception) { "No details" }
            throw Exception(str(failureText, response.status, errorBody))
        }
        return response.body<JsonObject>()["text"]?.jsonPrimitive?.content ?: str(R.string.transcription_empty)
    }
    fun updateMessageAt(position: Int, newContent: String) {
        val currentList = _chatMessages.value ?: return
        if (position < 0 || position >= currentList.size) {
            return
        }
        val messageToUpdate = currentList[position]
        val updatedMessage = RpContinuation.withWords(messageToUpdate, newContent)
        val newList = currentList.toMutableList()
        newList[position] = updatedMessage
        _chatMessages.value = newList
        syncRpSwipeAltAfterAssistantEdit(position, newContent)
        autoSaveChat()
    }

    /** Keep swipe alts in sync when the user edits the last assistant bubble. */
    private fun syncRpSwipeAltAfterAssistantEdit(position: Int, newContent: String) {
        if (!isRpMode() || rpSwipeState.alts.isEmpty()) return
        val messages = _chatMessages.value ?: return
        val lastAssistantIndex = messages.indexOfLast { it.role == "assistant" && !isAssistantPlaceholder(it) }
        if (position != lastAssistantIndex) return
        val index = rpSwipeState.index.coerceIn(0, rpSwipeState.alts.lastIndex)
        val alts = rpSwipeState.alts.toMutableList()
        alts[index] = newContent
        rpSwipeState = rpSwipeState.copy(alts = alts, index = index)
        persistRpSwipeState()
        updateRpSwipeNav()
    }

    fun notifySessionDeleted(sessionId: Long) {
        if (currentSessionId != sessionId) return
        if (isRpMode()) {
            // Greeting in-memory only — autosave would immediately resurrect a history row.
            beginSessionTransition {
                assignOpenSession(null)
                clearForkMemory()
                clearRpSwipeMemory()
                sharedPreferencesHelper.saveRpDraftSessionId(ChatMode.RP, null)
                startNewRpChatKeepingCharacterInternal(persist = false)
            }
        } else {
            startNewChat()
        }
    }

    fun rememberDeletedCharacterForRematch(characterId: Long) {
        if (characterId <= 0L) return
        // LLM parked deletes must not clobber an open orphan rematch target.
        if (sharedPreferencesHelper.isRpLlmMode()) return
        val activeId = sharedPreferencesHelper.getRpActiveCharacterId()
        if (activeId == characterId || preservedSessionCharacterId == characterId) {
            preservedSessionCharacterId = characterId
        }
    }
    // NEW: Specialized resend for existing user prompt (keeps original UI bubble intact)
    fun resendExistingPrompt(
        userMessageIndex: Int,
        systemMessage: String? = null,
        /** Turns sent after the user message but never shown: a Rewrite's old reply and its note. */
        extraTurns: List<FlexibleMessage> = emptyList()
    ) {
        if (userMessageIndex < 0 || userMessageIndex >= (_chatMessages.value?.size ?: 0)) {

            return
        }

        val currentMessages = _chatMessages.value ?: emptyList()
        val userMessage = currentMessages[userMessageIndex]
        // The picture is already in the message. pendingUserImageUri is only the photo staged
        // in the composer; copying this turn's data URL into it made the next send inherit it.

        if (isRpMode()) {
            truncateWithoutFork(userMessageIndex + 1)
            clearForkMemory()
        } else {
            truncateHistory(userMessageIndex + 1, anchorAssistantIndex = userMessageIndex + 1)
        }

        val messagesForApiRequest = mutableListOf<FlexibleMessage>()
        if (systemMessage != null) {
            messagesForApiRequest.add(
                FlexibleMessage(
                    role = "system",
                    content = JsonPrimitive(systemMessage)
                )
            )
        }

        messagesForApiRequest.addAll(currentMessages.take(userMessageIndex))
        messagesForApiRequest.add(userMessage)
        trimMessagesForApiMemory(messagesForApiRequest)
        // A rewrite's old reply and its note have to survive a tight memory window. Trim pins
        // the newest turn, which would be the note, and would drop the reply the note refers to.
        messagesForApiRequest.addAll(extraTurns)

        val uiMessages = _chatMessages.value?.toMutableList() ?: mutableListOf()
        uiMessages.add(THINKING_MESSAGE)
        streamingAssistantIndex = uiMessages.lastIndex
        if (!isRpMode()) {
            markForkAnchorIfPending(streamingAssistantIndex)
        }
        _chatMessages.value = uiMessages

        _isAwaitingResponse.value = true
        _userScrolledDuringStream.value = false

        if (!bindChatEndpoint()) {
            pendingRpSwipeAppend = false
            _isAwaitingResponse.value = false
            removeAssistantPlaceholder(THINKING_MESSAGE)
            // Also drop a trailing thinking bubble if identity didn't match.
            val cleaned = _chatMessages.value?.toMutableList()
            if (cleaned != null) {
                while (cleaned.isNotEmpty() && isAssistantPlaceholder(cleaned.last())) {
                    cleaned.removeAt(cleaned.lastIndex)
                }
                _chatMessages.value = cleaned
            }
            restoreRpSwipeAltIfMissingAssistant()
            return
        }

        startChatTurn(messagesForApiRequest, THINKING_MESSAGE)
    }

    /** OpenRouter, or the LAN server. False when a LAN model has no endpoint; the toast is the same one both sends used. */
    private fun bindChatEndpoint(): Boolean {
        activeChatUrl = "https://openrouter.ai/api/v1/chat/completions"
        activeChatApiKey = sharedPreferencesHelper.getApiKeyFromPrefs("openrouter_api_key")
        if (!activeModelIsLan()) return true
        val lanEndpoint = sharedPreferencesHelper.getLanEndpoint()
        if (lanEndpoint == null) {
            _toastUiEvent.postValue(Event(str(R.string.toast_lan_endpoint_missing)))
            return false
        }
        activeChatUrl = "$lanEndpoint/v1/chat/completions"
        activeChatApiKey = sharedPreferencesHelper.getLanApiKeyForRequest()
        return true
    }

    /** One network turn for a new send and a resend. Stop and failure stay on this path. */
    private fun startChatTurn(
        messagesForApiRequest: List<FlexibleMessage>,
        thinkingMessage: FlexibleMessage,
    ) {
        startNetworkJob {
            try {
                val modelForRequest =
                    _activeChatModel.value ?: throw IllegalStateException("No active chat model")
                if (activeModelIsLan()) {
                    if (_isStreamingEnabled.value == true) {
                        streamTransport.handleStreamedResponseLAN(modelForRequest, messagesForApiRequest, thinkingMessage)
                    } else {
                        streamTransport.handleNonStreamedResponseLAN(modelForRequest, messagesForApiRequest, thinkingMessage)
                    }
                } else {
                    // The demo model only speaks in streams.
                    if (_isStreamingEnabled.value == true || DemoModel.isDemo(modelForRequest)) {
                        streamTransport.handleStreamedResponse(modelForRequest, messagesForApiRequest, thinkingMessage)
                    } else {
                        streamTransport.handleNonStreamedResponse(modelForRequest, messagesForApiRequest, thinkingMessage)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // The request's own withTimeout, not Stop: the job is alive, and the user should
                // hear that the request ran out of time rather than see the bubble vanish.
                handleError(e, thinkingMessage)
            } catch (e: CancellationException) {
                val cancelled = coroutineContext[Job]
                withContext(Dispatchers.Main) {
                    // Send, or a regenerate, may already own the list. Removing "the"
                    // placeholder then deletes the new one: every thinking bubble is
                    // the same object, so the lookup matches whichever is on screen.
                    if (!StreamTurn.applyCancelCleanup(networkJob, cancelled)) return@withContext
                    val wasRpRegen = pendingRpSwipeAppend
                    val discardPartial = discardableRpAssistantInFlight
                    pendingRpSwipeAppend = false
                    discardableRpAssistantInFlight = false
                    if (wasRpRegen) {
                        restoreRpSwipeAltIfMissingAssistant()
                    } else if (discardPartial) {
                        discardIncompleteRpAssistantAfterLastUser()
                    } else {
                        removeAssistantPlaceholder(thinkingMessage)
                    }
                }
                throw e
            } catch (e: Throwable) {
                handleError(e, thinkingMessage)
            } finally {
                // Only the active network turn may clear awaiting (Stop→Send must not be killed by a stale finally).
                if (networkJob === coroutineContext[Job]) {
                    continuationBase = null
                    discardableRpAssistantInFlight = false
                    _isAwaitingResponse.postValue(false)
                    if (_userScrolledDuringStream.value != true) {
                        _scrollToBottomEvent.postValue(Event(Unit))
                    }
                }
            }
        }
    }

    /** Cap API history; in character RP keep the opening greeting when budget allows. */
    private fun trimMessagesForApiMemory(messagesForApiRequest: MutableList<FlexibleMessage>) {
        val memoryCount = sharedPreferencesHelper.getChatMemoryCount()
        if (messagesForApiRequest.size <= memoryCount) return
        val systemMessages = messagesForApiRequest.filter { it.role == "system" }
        val nonSystem = messagesForApiRequest.filter { it.role != "system" }
        val budget = (memoryCount - systemMessages.size).coerceAtLeast(1)
        val recentMessages = RpApiMemory.trimNonSystem(
            nonSystem = nonSystem,
            budget = budget,
            pinCharacterGreeting = isRpMode() && !sharedPreferencesHelper.isRpLlmMode(),
            isAssistant = { it.role == "assistant" }
        )
        messagesForApiRequest.clear()
        messagesForApiRequest.addAll(systemMessages)
        messagesForApiRequest.addAll(recentMessages)
    }
    private val toolRuntime = ChatToolRuntime(object : ChatToolHost {
        override val application: Application get() = getApplication()
        override val json: Json get() = this@ChatViewModel.json
        override val httpClient: HttpClient get() = this@ChatViewModel.httpClient
        override val sharedPreferencesHelper: SharedPreferencesHelper
            get() = this@ChatViewModel.sharedPreferencesHelper
        override val chatMessages get() = _chatMessages
        override val activeChatModel get() = _activeChatModel
        override val scrollToBottomEvent get() = _scrollToBottomEvent
        override val toolUiEvent get() = _toolUiEvent
        override val toastUiEvent get() = _toastUiEvent
        override var streamingAssistantIndex: Int
            get() = this@ChatViewModel.streamingAssistantIndex
            set(value) { this@ChatViewModel.streamingAssistantIndex = value }
        override var toolCallsHandledForTurn: Boolean
            get() = this@ChatViewModel.toolCallsHandledForTurn
            set(value) { this@ChatViewModel.toolCallsHandledForTurn = value }
        override var toolRecursionDepth: Int
            get() = this@ChatViewModel.toolRecursionDepth
            set(value) { this@ChatViewModel.toolRecursionDepth = value }
        override fun activeModelIsLan(): Boolean = this@ChatViewModel.activeModelIsLan()
        override fun updateMessages(updateBlock: (MutableList<FlexibleMessage>) -> Unit) =
            this@ChatViewModel.updateMessages(updateBlock)
        override fun putAssistantMessage(
            list: MutableList<FlexibleMessage>,
            thinkingMessage: FlexibleMessage?,
            newMessage: FlexibleMessage,
        ) = this@ChatViewModel.putAssistantMessage(list, thinkingMessage, newMessage)
        override fun removeAssistantPlaceholder(thinkingMessage: FlexibleMessage?) =
            this@ChatViewModel.removeAssistantPlaceholder(thinkingMessage)
        override suspend fun continueConversation(messages: List<FlexibleMessage>) =
            this@ChatViewModel.continueConversation(messages)
    })

    private fun buildTools(): List<Tool> = toolRuntime.buildTools()

    private suspend fun handleToolCalls(
        toolCalls: List<ToolCall>,
        thinkingMessage: FlexibleMessage?,
    ) = toolRuntime.handleToolCalls(toolCalls, thinkingMessage)

    private fun saveFileToDownloads(filename: String, content: String, mimeType: String) =
        toolRuntime.saveFileToDownloads(filename, content, mimeType)

    private fun writeDownload(
        success: String,
        failure: (Exception) -> String = { str(R.string.save_failed_detail, it.message) },
        write: () -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                write()
                _toolUiEvent.postValue(Event(success))
            } catch (e: Exception) {
                _toolUiEvent.postValue(Event(failure(e)))
            }
        }
    }



    private suspend fun continueConversation(messages: List<FlexibleMessage>) {
        if (toolRecursionDepth > 12) {
            withContext(Dispatchers.Main) {
                updateMessages { list ->
                    putAssistantMessage(
                        list,
                        null,
                        FlexibleMessage(
                            role = "assistant",
                            content = JsonPrimitive(ERROR_BUBBLE_PREFIX + str(R.string.error_tool_followup_limit))
                        )
                    )
                }
            }
            return
        }
        toolCallsHandledForTurn = false

        val toolThinkingMessage = FlexibleMessage(
            role = "assistant",
            content = JsonPrimitive(ThinkingPlaceholder.TOKEN)
        )

        withContext(Dispatchers.Main) {
            updateMessages { it.add(toolThinkingMessage); streamingAssistantIndex = it.lastIndex }
            _scrollToBottomEvent.value = Event(Unit)
        }

        try {
            val modelForRequest = _activeChatModel.value ?: throw IllegalStateException("No active chat model")

            // Branch here to ensure tool-use follow-ups use the correct logic
            if (activeModelIsLan()) {
                streamTransport.handleNonStreamedResponseLAN(modelForRequest, messages, toolThinkingMessage)
            } else {
                streamTransport.handleNonStreamedResponse(modelForRequest, messages, toolThinkingMessage)
            }
        } catch (e: TimeoutCancellationException) {
            withContext(Dispatchers.Main) {
                handleError(e, toolThinkingMessage)
            }
        } catch (e: CancellationException) {
            withContext(Dispatchers.Main) {
                removeAssistantPlaceholder(toolThinkingMessage)
            }
            throw e
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) {
                handleError(e, toolThinkingMessage)
            }
        }
    }

    private val streamTransport = ChatStreamTransport(object : ChatStreamHost {
        override val application: Application get() = getApplication()
        override val scope: kotlinx.coroutines.CoroutineScope get() = viewModelScope
        override val json: Json get() = this@ChatViewModel.json
        override val demoHttpClient: HttpClient get() = this@ChatViewModel.demoHttpClient
        override val httpClient: HttpClient get() = this@ChatViewModel.httpClient
        override val lanHttpClient: HttpClient get() = this@ChatViewModel.lanHttpClient
        override val sharedPreferencesHelper: SharedPreferencesHelper get() = this@ChatViewModel.sharedPreferencesHelper
        override val activeChatModelState get() = _activeChatModel
        override val activeChatModel: LiveData<String> get() = this@ChatViewModel.activeChatModel
        override val isReasoningEnabled get() = _isReasoningEnabled
        override val isToolsEnabled get() = _isToolsEnabled
        override val toolUiEvent get() = _toolUiEvent
        override val toastUiEvent get() = _toastUiEvent
        override var activeChatUrl: String
            get() = this@ChatViewModel.activeChatUrl
            set(value) { this@ChatViewModel.activeChatUrl = value }
        override var activeChatApiKey: String
            get() = this@ChatViewModel.activeChatApiKey
            set(value) { this@ChatViewModel.activeChatApiKey = value }
        override var activeStreamPump: StreamUiPump?
            get() = this@ChatViewModel.activeStreamPump
            set(value) { this@ChatViewModel.activeStreamPump = value }
        override var pendingRpSwipeAppend: Boolean
            get() = this@ChatViewModel.pendingRpSwipeAppend
            set(value) { this@ChatViewModel.pendingRpSwipeAppend = value }
        override var discardableRpAssistantInFlight: Boolean
            get() = this@ChatViewModel.discardableRpAssistantInFlight
            set(value) { this@ChatViewModel.discardableRpAssistantInFlight = value }
        override var toolCallsHandledForTurn: Boolean
            get() = this@ChatViewModel.toolCallsHandledForTurn
            set(value) { this@ChatViewModel.toolCallsHandledForTurn = value }
        override fun isReasoningModel(modelIdentifier: String?) = this@ChatViewModel.isReasoningModel(modelIdentifier)
        override fun canRequestReasoning(modelIdentifier: String?) = this@ChatViewModel.canRequestReasoning(modelIdentifier)
        override fun isImageGenerationModel(modelIdentifier: String?) = this@ChatViewModel.isImageGenerationModel(modelIdentifier)
        override fun isRpMode() = this@ChatViewModel.isRpMode()
        override fun activeModelIsLan() = this@ChatViewModel.activeModelIsLan()
        override fun buildTools() = this@ChatViewModel.buildTools()
        override suspend fun handleToolCalls(toolCalls: List<ToolCall>, thinkingMessage: FlexibleMessage?) =
            this@ChatViewModel.handleToolCalls(toolCalls, thinkingMessage)
        override fun buildWebSearchPlugin() = this@ChatViewModel.buildWebSearchPlugin()
        override fun parseOpenRouterError(responseText: String) = this@ChatViewModel.parseOpenRouterError(responseText)
        override fun finalizeAssistantContent(text: String) = this@ChatViewModel.finalizeAssistantContent(text)
        override fun getModelDisplayName(apiIdentifier: String) = this@ChatViewModel.getModelDisplayName(apiIdentifier)
        override suspend fun downloadImages(imageUrls: List<String>) = this@ChatViewModel.downloadImages(imageUrls)
        override fun saveBinaryFileToDownloads(filename: String, bytes: ByteArray, mimeType: String) =
            this@ChatViewModel.saveBinaryFileToDownloads(filename, bytes, mimeType)
        override fun updateMessages(updateBlock: (MutableList<FlexibleMessage>) -> Unit) =
            this@ChatViewModel.updateMessages(updateBlock)
        override fun putAssistantMessage(list: MutableList<FlexibleMessage>, thinkingMessage: FlexibleMessage?, newMessage: FlexibleMessage) =
            this@ChatViewModel.putAssistantMessage(list, thinkingMessage, newMessage)
        override fun removeAssistantPlaceholder(thinkingMessage: FlexibleMessage?) =
            this@ChatViewModel.removeAssistantPlaceholder(thinkingMessage)
        override fun restoreRpSwipeAltIfMissingAssistant() = this@ChatViewModel.restoreRpSwipeAltIfMissingAssistant()
        override fun handleError(e: Throwable, thinkingMessage: FlexibleMessage?) =
            this@ChatViewModel.handleError(e, thinkingMessage)
    })












    fun refreshHttpClient() {
        httpClient.close()
        lanHttpClient.close()
        httpClient = createHttpClient()
        lanHttpClient = createLanHttpClient()
        llmService = LlmService(httpClient)
    }

    fun refreshLanHttpClient() {
        lanHttpClient.close()
        lanHttpClient = createLanHttpClient()
    }


    private fun handleError(e: Throwable, thinkingMessage: FlexibleMessage?) {
        val wasRpRegen = pendingRpSwipeAppend
        pendingRpSwipeAppend = false
        if (e is CancellationException && e !is TimeoutCancellationException) {
            removeAssistantPlaceholder(thinkingMessage)
            if (wasRpRegen) restoreRpSwipeAltIfMissingAssistant()
            else if (discardableRpAssistantInFlight) {
                discardableRpAssistantInFlight = false
                discardIncompleteRpAssistantAfterLastUser()
            }
            return
        }
        // Terminal error path — keep the Error bubble; Stop must not treat it as mid-stream.
        discardableRpAssistantInFlight = false
        // Ktor can wrap what an OkHttp interceptor threw, so look down the cause chain.
        val lanCertChange = generateSequence(e) { it.cause }.filterIsInstance<LanCertChangedException>().firstOrNull()
        if (wasRpRegen) {
            removeAssistantPlaceholder(thinkingMessage)
            restoreRpSwipeAltIfMissingAssistant()
            val shortMsg = when {
                lanCertChange != null -> lanCertChange.message ?: str(R.string.error_lan_cert_changed)
                e is TimeoutCancellationException || e is SocketTimeoutException ->
                    getApplication<Application>().getString(R.string.rp_regen_timeout)
                e is IOException ->
                    getApplication<Application>().getString(R.string.rp_regen_network)
                else ->
                    e.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: getApplication<Application>().getString(R.string.rp_regen_failed)
            }
            _toastUiEvent.postValue(Event(shortMsg))
            return
        }
        // Continue grows the last reply. An HTTP error used to write the Error bubble later,
        // after this turn had already forgotten the original text, so the story was replaced.
        if (continuationBase != null) {
            failContinuation(e, thinkingMessage, lanCertChange)
            return
        }
        val errorMsg = if (lanCertChange != null) {
            ERROR_BUBBLE_PREFIX + (lanCertChange.message ?: str(R.string.error_lan_cert_changed))
        } else when (e) {
            is ClientRequestException -> {
                // Handle in a coroutine scope
                var errorText = ERROR_BUBBLE_PREFIX + str(R.string.error_client_request, e.response.status)
                viewModelScope.launch {
                    try {
                        val errorBody = e.response.bodyAsText()
                        errorText = ERROR_BUBBLE_PREFIX + parseOpenRouterError(errorBody)
                    } catch (parseError: Exception) {
                        // Keep the default error text
                    }
                    // Update the UI with the final error message
                    val finalErrorMessage = FlexibleMessage(role = "assistant", content = JsonPrimitive(errorText))
                    updateMessages { list ->
                        putAssistantMessage(list, thinkingMessage, finalErrorMessage)
                    }
                }
                errorText // Return initial message for immediate display
            }
            is ServerResponseException -> {
                // Handle in a coroutine scope
                var errorText = ERROR_BUBBLE_PREFIX + str(R.string.error_server_request, e.response.status)
                viewModelScope.launch {
                    try {
                        val errorBody = e.response.bodyAsText()
                        errorText = ERROR_BUBBLE_PREFIX + parseOpenRouterError(errorBody)
                    } catch (parseError: Exception) {
                        // Keep the default error text
                    }
                    // Update the UI with the final error message
                    val finalErrorMessage = FlexibleMessage(role = "assistant", content = JsonPrimitive(errorText))
                    updateMessages { list ->
                        putAssistantMessage(list, thinkingMessage, finalErrorMessage)
                    }
                }
                errorText // Return initial message for immediate display
            }
            is TimeoutCancellationException, is SocketTimeoutException ->
                ERROR_BUBBLE_PREFIX + str(R.string.error_request_timeout, sharedPreferencesHelper.getTimeoutMinutes())
            is IOException -> ERROR_BUBBLE_PREFIX + str(R.string.error_network)
            else -> ERROR_BUBBLE_PREFIX + (e.localizedMessage ?: str(R.string.error_unknown))
        }

        // For non-suspend errors, update immediately
        if (e !is ClientRequestException && e !is ServerResponseException) {
            val errorMessage = FlexibleMessage(role = "assistant", content = JsonPrimitive(errorMsg))
            updateMessages { list ->
                putAssistantMessage(list, thinkingMessage, errorMessage)
            }
        }
    }

    /**
     * Leave the reply Continue was extending, and say why in a notice. The HTTP body is read
     * afterwards: that used to land as a second write once [continuationBase] was already cleared.
     */
    private fun failContinuation(
        e: Throwable,
        thinkingMessage: FlexibleMessage?,
        lanCertChange: LanCertChangedException?,
    ) {
        val base = continuationBase ?: return
        updateMessages { list ->
            val index = resolveAssistantSlot(list, thinkingMessage)
            if (index != -1) {
                val current = list[index]
                list[index] = current.copy(
                    content = ScenePhoto.replaceTextKeepingPicture(current.content, base)
                )
            }
        }
        val fallback = continuationErrorText(e, lanCertChange)
        if (e is ClientRequestException || e is ServerResponseException) {
            viewModelScope.launch {
                val parsed = try {
                    parseOpenRouterError(e.response.bodyAsText()).takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                _toastUiEvent.postValue(Event(parsed ?: fallback))
            }
        } else {
            _toastUiEvent.postValue(Event(fallback))
        }
    }

    private fun continuationErrorText(e: Throwable, lanCertChange: LanCertChangedException?): String {
        if (lanCertChange != null) return lanCertChange.message ?: str(R.string.error_lan_cert_changed)
        return when (e) {
            is ClientRequestException -> str(R.string.error_client_request, e.response.status)
            is ServerResponseException -> str(R.string.error_server_request, e.response.status)
            is TimeoutCancellationException, is SocketTimeoutException ->
                str(R.string.error_request_timeout, sharedPreferencesHelper.getTimeoutMinutes())
            is IOException -> str(R.string.error_network)
            else -> e.localizedMessage?.takeIf { it.isNotBlank() } ?: str(R.string.error_unknown)
        }
    }

    private fun updateMessages(updateBlock: (MutableList<FlexibleMessage>) -> Unit) {
        val current = _chatMessages.value?.toMutableList() ?: mutableListOf()
        updateBlock(current)
        _chatMessages.value = current
    }

    fun startNewChat() {
        // Abort in-flight load/restore so it cannot resurrect a cleared transcript.
        // Drop a partial reply first, then snapshot what remains, then leave.
        cancelCurrentRequest(restoreSwipeAlt = false)
        autoSaveChat(allowNetworkTitle = false)
        sessionTransitionJob?.cancel()
        sessionTransitionJob = null
        sessionEpoch++
        rpMemoryJob?.cancel()
        clearOpenTranscript()
        // The launch job skips its own ready when this bump wins. Publish the cleared id.
        markSessionReady()
    }

    /** Ask: empty thread. RP: reinject active character greeting when applicable. */
    fun startFreshChatForCurrentMode() {
        if (isRpMode()) _rpThreadOpenedEvent.value = Event(Unit)
        if (isRpMode()) {
            startNewRpChatKeepingCharacter()
        } else {
            startNewChat()
        }
    }

    private fun clearRpSwipeMemory() {
        pendingRpSwipeAppend = false
        discardableRpAssistantInFlight = false
        rpRegenRestoreFallback = null
        rpSwipeState = RpSwipeState()
        _rpSwipeNav.value = null
    }

    /** True when the open RP transcript already has a user turn (activating a character would wipe it). */
    fun rpChatHasUserTurn(): Boolean =
        isRpMode() && (_chatMessages.value?.any { it.role == "user" } == true)

    /** True when RP has any real content (greeting or user) that activating another character would replace. */
    fun rpChatHasContent(): Boolean =
        isRpMode() && (_chatMessages.value?.any {
            it.role == "user" || (it.role == "assistant" && !isAssistantPlaceholder(it))
        } == true)

    /**
     * Whether Start chat should confirm before replacing an existing RP thread.
     * Covers open RP content and a parked RP draft (Ask or empty RP after LLM wipe).
     */
    fun rpStartChatNeedsConfirm(): Boolean {
        if (rpChatHasContent()) return true
        val draftId = sharedPreferencesHelper.getRpDraftSessionId(ChatMode.RP) ?: return false
        // Empty open RP that already is the draft pointer — nothing valuable to replace.
        if (isRpMode() && draftId == currentSessionId) return false
        return true
    }

    /**
     * True once, when the reply that just went idle was stopped so the transcript
     * could be shortened. The caller saves what remains.
     */
    fun consumeSkipIdleAutosave(): Boolean {
        if (!skipNextIdleAutosave) return false
        skipNextIdleAutosave = false
        return true
    }

    /**
     * Edit, delete, and regenerate drop the tail. A token still in flight would be
     * written onto that shorter list, and Stop's idle save would persist the list
     * from before the cut. Stop first, without saving yet.
     */
    private fun stopTurnBeforeCut() {
        val running = networkJob?.isActive == true || activeStreamPump != null
        if (!running) return
        skipNextIdleAutosave = true
        cancelCurrentRequest(restoreSwipeAlt = false)
        streamingAssistantIndex = -1
        // The screen consumes the skip while it is observing. If it did not, the
        // next reply that actually finishes still has to be saved.
        skipNextIdleAutosave = false
    }

    /**
     * Stash messages from [startIndex] as the alternate branch, then truncate.
     * One fork per chat: restoring swaps the active tail with the stash.
     */
    fun stashAndTruncateFrom(startIndex: Int, anchorAssistantIndex: Int = startIndex) {
        // A new cut replaces an edit that had not been sent. Edit marks itself again just after.
        clearComposerEditMark()
        val current = _chatMessages.value?.toMutableList() ?: return
        if (startIndex < 0 || startIndex >= current.size) return
        // Stop does not change the messages. The copy above is still the tail to fork.
        stopTurnBeforeCut()
        val discarded = current.subList(startIndex, current.size)
            .filterNot { isAssistantPlaceholder(it) }
            .map { it.copy() }
        if (discarded.isNotEmpty()) {
            forkIndex = startIndex
            stashedForkTail = discarded
            forkDisplayVariant = 2
            forkAnchorAssistantIndex = anchorAssistantIndex
            _hasChatFork.value = true
            persistForkToPrefs()
        }
        current.subList(startIndex, current.size).clear()
        _chatMessages.value = current
    }

    /** True while Edit has cut a turn and the replacement has not been sent. */
    fun isComposerEditOpen(): Boolean = ChatEdit.awaitingSend(
        marked = composerEditKey != null,
        sameChat = composerEditKey == ComposerDrafts.key(currentSessionId),
        roleplay = isRpMode(),
        hasFork = _hasChatFork.value == true,
        variant = forkDisplayVariant,
        forkIndex = forkIndex,
        messageCount = _chatMessages.value?.size ?: 0,
    )

    /**
     * Call after the turn has been cut. [draft] is whatever was already in the field.
     * A second call for the same open edit does not replace that line with the message text.
     */
    fun openComposerEdit(draft: String) {
        if (isRpMode()) return
        if (_hasChatFork.value != true || forkDisplayVariant != 2 || forkIndex < 0) return
        if ((_chatMessages.value?.size ?: 0) > forkIndex) return
        val key = ComposerDrafts.key(currentSessionId)
        if (composerEditKey != key || key !in composerEditDrafts) composerEditDrafts[key] = draft
        composerEditKey = key
        currentSessionId?.let { id ->
            sharedPreferencesHelper.setChatForkEditing(id, true, composerEditDrafts[key].orEmpty())
        }
    }

    /** The replacement was sent, or a different cut replaced this one. The fork itself can stay. */
    fun finishComposerEdit() = clearComposerEditMark()

    private fun clearComposerEditMark() {
        val id = currentSessionId
        if (composerEditKey == null && (id == null || !sharedPreferencesHelper.isChatForkEditing(id))) {
            composerEditDrafts.remove(ComposerDrafts.NEW)
            return
        }
        composerEditKey = null
        if (id != null) {
            composerEditDrafts.remove(ComposerDrafts.key(id))
            sharedPreferencesHelper.setChatForkEditing(id, false, null)
        }
        composerEditDrafts.remove(ComposerDrafts.NEW)
    }

    data class EditCancel(val rememberedDraft: String?, val restoredUserText: String)

    /**
     * Put the cut turn back and return the line the field should show. Null when there
     * is nothing to cancel (the edit was already sent, or this is not the chat it belongs to).
     */
    fun cancelComposerEdit(): EditCancel? {
        if (!isComposerEditOpen()) return null
        val id = currentSessionId
        val key = ComposerDrafts.key(id)
        val remembered = when {
            key in composerEditDrafts -> composerEditDrafts.remove(key)
            id != null -> sharedPreferencesHelper.getChatForkEditDraft(id)
            else -> null
        }
        val index = forkIndex
        composerEditKey = null
        composerEditDrafts.remove(ComposerDrafts.NEW)
        id?.let { sharedPreferencesHelper.setChatForkEditing(it, false, null) }
        restoreChatFork()
        val restored = _chatMessages.value?.getOrNull(index)?.let { getMessageText(it.content) }.orEmpty()
        return EditCancel(remembered, restored)
    }

    fun truncateHistory(startIndex: Int, anchorAssistantIndex: Int = startIndex) {
        stashAndTruncateFrom(startIndex, anchorAssistantIndex)
    }

    private fun markForkAnchorIfPending(assistantIndex: Int) {
        if (_hasChatFork.value != true || forkAnchorAssistantIndex >= 0) return
        forkAnchorAssistantIndex = assistantIndex
        persistForkToPrefs()
    }

    fun deleteMessageAt(index: Int) {
        val size = _chatMessages.value?.size ?: return
        if (index < 0 || index >= size) return
        stopTurnBeforeCut()
        val current = _chatMessages.value?.toMutableList() ?: return
        if (index >= current.size) return
        current.subList(index, current.size).clear()
        _chatMessages.value = current
        syncRpSwipeAfterTranscriptChange()
        autoSaveChat()
    }

    /** Truncate for RP user-edit without creating an Ask-mode fork or leaving stale swipe state. */
    fun truncateForRpEdit(startIndex: Int) {
        val size = _chatMessages.value?.size ?: return
        if (startIndex < 0 || startIndex >= size) return
        stopTurnBeforeCut()
        truncateWithoutFork(startIndex)
        clearForkMemory()
        forgetRpSwipeVersions()
        autoSaveChat()
    }

    private fun syncRpSwipeAfterTranscriptChange() {
        if (!isRpMode()) return
        val messages = _chatMessages.value.orEmpty()
        val swipeable = RpSwipeRules.isSwipeableMessages(
            messages,
            isUser = { it.role == "user" },
            isAssistant = { it.role == "assistant" && !isAssistantPlaceholder(it) }
        )
        if (!swipeable) {
            forgetRpSwipeVersions()
            return
        }
        val lastAssistantIndex = messages.indexOfLast { it.role == "assistant" && !isAssistantPlaceholder(it) }
        val lastText = getMessageText(messages[lastAssistantIndex].content)
        if (lastText.isBlank() || isNonSwipeableRpAssistantText(lastText)) {
            forgetRpSwipeVersions()
            return
        }
        val (alts, index) = RpSwipeRules.reconcileAltsAfterTruncate(rpSwipeState.alts, lastText)
        if (alts != rpSwipeState.alts) dropUnreferencedSwipePictures()
        val pictures = if (alts == rpSwipeState.alts) rpSwipeState.pictureUris else emptyList()
        rpSwipeState = RpSwipeState(alts = alts, index = index, pictureUris = pictures)
        persistRpSwipeState()
        updateRpSwipeNav()
    }

    fun getForkNavForMessage(position: Int): ForkNavState? {
        if (isRpMode()) return null
        if (_hasChatFork.value != true || forkAnchorAssistantIndex < 0) return null
        if (position != forkAnchorAssistantIndex) return null
        return ForkNavState(
            variantIndex = forkDisplayVariant,
            canGoPrev = forkDisplayVariant > 1,
            canGoNext = forkDisplayVariant < 2
        )
    }

    fun navigateFork(direction: Int) {
        if (_hasChatFork.value != true) return
        when {
            direction < 0 && forkDisplayVariant > 1 -> restoreChatFork()
            direction > 0 && forkDisplayVariant < 2 -> restoreChatFork()
        }
    }

    private fun clearForkMemory() {
        forkIndex = -1
        forkAnchorAssistantIndex = -1
        stashedForkTail = emptyList()
        forkDisplayVariant = 1
        composerEditKey = null
        _hasChatFork.value = false
    }
    fun restoreChatFork() {
        if (_hasChatFork.value != true || forkIndex < 0 || stashedForkTail.isEmpty()) return
        val current = _chatMessages.value?.toMutableList() ?: return
        val safeIndex = forkIndex.coerceIn(0, current.size)
        val prefix = current.take(safeIndex)
        val activeTail = current.drop(safeIndex)
            .filterNot { isAssistantPlaceholder(it) }
            .map { it.copy() }
        val restoredTail = stashedForkTail.map { it.copy() }
        stashedForkTail = activeTail
        forkIndex = safeIndex
        forkDisplayVariant = 3 - forkDisplayVariant
        val merged = prefix + restoredTail
        _chatMessages.value = merged
        forkAnchorAssistantIndex = merged.indices.firstOrNull { i ->
            i >= safeIndex && merged[i].role == "assistant" && !isAssistantPlaceholder(merged[i])
        } ?: forkAnchorAssistantIndex
        _hasChatFork.value = stashedForkTail.isNotEmpty()
        persistForkToPrefs()
        autoSaveChat()
    }

    private fun persistForkToPrefs() {
        val sessionId = currentSessionId ?: return
        if (stashedForkTail.isEmpty() || forkIndex < 0) {
            sharedPreferencesHelper.clearChatFork(sessionId)
            return
        }
        persistCapturedFork(
            sessionId,
            CapturedFork(forkIndex, forkAnchorAssistantIndex, stashedForkTail)
        )
    }

    private fun persistCapturedFork(sessionId: Long, fork: CapturedFork?) {
        if (fork == null || fork.messages.isEmpty() || fork.index < 0) {
            sharedPreferencesHelper.clearChatFork(sessionId)
            return
        }
        try {
            val encoded = json.encodeToString(
                ListSerializer(FlexibleMessage.serializer()),
                fork.messages
            )
            sharedPreferencesHelper.saveChatFork(sessionId, fork.index, fork.anchor, encoded)
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Could not save the other branch of chat $sessionId", e)
        }
    }

    private fun persistCapturedSwipe(sessionId: Long, swipe: RpSwipeState?) {
        if (swipe == null || swipe.alts.isEmpty()) {
            sharedPreferencesHelper.clearRpSwipeJson(sessionId)
            return
        }
        rpSwipeStore.save(sessionId, swipe)
    }

    private fun parkMintedDraft(sessionId: Long, snap: ChatPersistSnapshot) {
        val mode = ChatMode.fromStorage(snap.mode)
        val draftNow = sharedPreferencesHelper.getRpDraftSessionId(mode)
        if (!ChatSaveGate.parkMintedDraft(
                snapshotMode = snap.mode,
                liveMode = sessionModeValue(),
                epochAtCapture = snap.epoch,
                liveEpoch = sessionEpoch,
                liveSessionId = currentSessionId,
                mintedId = sessionId,
                draftAtCapture = snap.draftAtCapture,
                draftNow = draftNow,
            )
        ) return
        sharedPreferencesHelper.saveRpDraftSessionId(mode, sessionId)
    }

    private fun loadForkFromPrefs(sessionId: Long) {
        clearForkMemory()
        val idx = sharedPreferencesHelper.getChatForkIndex(sessionId)
        val raw = sharedPreferencesHelper.getChatForkMessagesJson(sessionId) ?: return
        if (idx < 0) return
        val decoded = ForkLoad.messages(raw) ?: return
        forkIndex = idx
        stashedForkTail = decoded
        forkDisplayVariant = 2
        forkAnchorAssistantIndex = sharedPreferencesHelper.getChatForkAnchor(sessionId)
        if (forkAnchorAssistantIndex < 0) {
            val msgs = _chatMessages.value.orEmpty()
            forkAnchorAssistantIndex = msgs.indices.firstOrNull { i ->
                i >= idx && msgs[i].role == "assistant" && !isAssistantPlaceholder(msgs[i])
            } ?: idx
        }
        val editing = sharedPreferencesHelper.isChatForkEditing(sessionId)
        val size = _chatMessages.value?.size ?: 0
        if (editing && forkDisplayVariant == 2 && size <= forkIndex) {
            val key = ComposerDrafts.key(sessionId)
            composerEditKey = key
            if (key !in composerEditDrafts) {
                sharedPreferencesHelper.getChatForkEditDraft(sessionId)?.let { composerEditDrafts[key] = it }
            }
        } else if (editing) {
            sharedPreferencesHelper.setChatForkEditing(sessionId, false, null)
        }
        _hasChatFork.postValue(true)
    }

    fun hasWebpInHistory(): Boolean {
        val messages = _chatMessages.value ?: return false
        return messages.any { message ->
            MessageContent.imageUrls(message.content).any { it.startsWith("data:image/webp") }
        }
    }
    fun checkRemainingCredits() {
        viewModelScope.launch {
            val remaining = withContext(Dispatchers.IO) {
                llmService.getRemainingCredits(sharedPreferencesHelper.getApiKeyFromPrefs("openrouter_api_key"))
            }
            if (remaining != null) {
                val formattedCredits = String.format("%.4f", remaining)
                _creditsResult.postValue(Event(str(R.string.credits_remaining, formattedCredits)))
            } else {
                _creditsResult.postValue(Event(str(R.string.credits_failed)))
            }
        }
    }
    fun refreshApiKey() {
        activeChatApiKey = sharedPreferencesHelper.getApiKeyFromPrefs("openrouter_api_key")
    }
    fun supportsWebp(modelName: String): Boolean {
        return !modelName.lowercase().contains("grok")
    }
    suspend fun getSuggestedChatTitle(source: List<FlexibleMessage>? = null): String? {
        val messages = source ?: _chatMessages.value.orEmpty()
        if (DemoModel.isDemo(_activeChatModel.value)) {
            val first = messages.firstOrNull { it.role == "user" }
            return DemoModel.titleFor(first?.let { getMessageText(it.content) }.orEmpty())
        }
        // A title needs the gist, not the whole chat: the opening turns, capped.
        val chatContent = messages
            .filter { (it.role == "user" || it.role == "assistant") && !isAssistantPlaceholder(it) }
            .take(TITLE_SOURCE_MESSAGES)
            .joinToString("\n\n") { message ->
                val speaker = if (message.role == "user") "User" else "AI"
                "$speaker: ${getMessageText(message.content).trim().take(TITLE_SOURCE_MESSAGE_CHARS)}"
            }
            .take(TITLE_SOURCE_CHARS)

        // 1. Get the current provider (important for llama.cpp logic)
        val lanProvider = sharedPreferencesHelper.getLanProvider()
        val isLanModel = activeModelIsLan()

        // 2. Determine model, endpoint, and API Key
        val (modelId, endpoint, apiKey) = if (isLanModel) {
            val activeModel = getActiveLlmModel()
            val lanEndpoint = sharedPreferencesHelper.getLanEndpoint()

            if (activeModel?.apiIdentifier == null || lanEndpoint.isNullOrBlank()) {
                return null
            }

            Triple(
                activeModel.apiIdentifier,
                "$lanEndpoint/v1/chat/completions",
                sharedPreferencesHelper.getLanApiKeyForRequest()
            )
        } else {
            val cloudModelId = _activeChatModel.value
            if (cloudModelId.isNullOrBlank()) return null
            Triple(
                cloudModelId,
                "https://openrouter.ai/api/v1/chat/completions",
                activeChatApiKey
            )
        }

        // 3. Determine if the current model is a reasoning model
        // We need this to decide if we should pass kwargs
        // val activeModelInfo = getActiveLlmModel()
        val isReasoning = isReasoningModel(_activeChatModel.value)

        // 4. Call the service function with all the necessary context
        return llmService.getSuggestedChatTitle(
            chatContent = chatContent,
            apiKey = apiKey,
            modelId = modelId,
            endpoint = endpoint,
            lanProvider = lanProvider,
            isReasoningModel = isReasoning,
            client = if (isLanModel) lanHttpClient else null
        )
    }


    fun getBuiltInModels(): List<LlmModel> {
        return listOf(
            LlmModel(
                displayName = "OpenRouter: Free",
                apiIdentifier = "openrouter/free",
                isVisionCapable = true,
                isReasoningCapable = true,  // Add this (set to true if it supports reasoning)\
                isFree = true,
                isLANModel = false
            )
        )
    }
    fun checkAdvancedReasoningStatus() {
        _isAdvancedReasoningOn.value = sharedPreferencesHelper.getAdvancedReasoningEnabled()
    }
    fun getModelDisplayName(apiIdentifier: String): String {
        val builtInModels = getBuiltInModels()
        val customModels = sharedPreferencesHelper.getCustomModels()
        val allModels = builtInModels + customModels
        val model = allModels.find { it.apiIdentifier == apiIdentifier }
        return if (model != null) ModelNames.withoutProvider(model.displayName, model.apiIdentifier)
        else ModelNames.idWithoutProvider(apiIdentifier)
    }
    fun consumeSharedText(text: String) {
        _sharedText.value = text
    }
    fun consumeSharedTextautosend(text: String) {
        // Carry the text on the autosend event so Send isn't clicked before the composer updates.
        _autosendEvent.value = Event(text)
    }
    fun textConsumed() {
        _sharedText.value = null
    }
    fun hasGeneratedImagesInChat(): Boolean = _chatMessages.value?.any {
        it.role == "assistant" && !it.imageUri.isNullOrEmpty()
    } ?: false

    fun saveMarkdownToDownloads(rawMarkdown: String) = writeDownload(str(R.string.save_markdown_ok)) {
        saveFileToDownloads("chat-${System.currentTimeMillis()}.md", rawMarkdown, "text/markdown")
    }

    fun saveHtmlToDownloads(innerHtml: String) = writeDownload(str(R.string.save_html_ok)) {
        val currentModel = _activeChatModel.value ?: "Unknown"
        val dateTime = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        val filename = "${currentModel.replace("/", "-")}_$dateTime.html"
        saveFileToDownloads(filename, buildFullPrintStyledHtml(innerHtml), "text/html")
    }
    suspend fun getAIFixContent(input: String): String? = completeCorrection(
        input = input,
        systemPrompt = "You are a precise text‑correction utility.\n" +
            "Correct **only** the following issues in the user’s input:\n" +
            "\n" +
            "* Spelling mistakes (including homophone errors such as “to” vs. “too”, “their” vs. “there”).\n" +
            "* Grammar errors (subject‑verb agreement, verb tense, article usage, etc.).\n" +
            "* Capitalization errors.\n" +
            "* Punctuation errors (missing, extra, or misplaced punctuation marks).\n" +
            "\n" +
            "**Do not**:\n" +
            "\n" +
            "* Rewrite sentences, rephrase, or improve overall clarity.\n" +
            "* Change the user’s tone, style, or word choice beyond the errors listed above.\n" +
            "* Add explanations, quotations, or any surrounding text.\n" +
            "\n" +
            "If the input contains no errors, return it **exactly** as received.\n" +
            "Output **only** the corrected text—no headings, notes, or extra characters.",
        cloudModel = _activeChatModel.value,
        timeoutMs = 23_000,
        maxTokens = 4_000,
        stripQuotes = true,
    )

    suspend fun correctText(input: String): String? = completeCorrection(
        input = input,
        systemPrompt = "You are a strict text correction tool. Analyze the user's input for spelling, capitalization, punctuation and grammar errors. If there are no errors, output the input unchanged. Do NOT interpret, respond to, or fulfill any requests in the input. Output ONLY the corrected text, nothing else.",
        cloudModel = "google/gemma-4-26b-a4b-it",
        timeoutMs = 15_000,
        maxTokens = 10_000,
        stripQuotes = false,
    )

    private suspend fun completeCorrection(
        input: String,
        systemPrompt: String,
        cloudModel: String?,
        timeoutMs: Long,
        maxTokens: Int,
        stripQuotes: Boolean,
    ): String? {
        if (input.isBlank()) return null
        val content = completeTurns(listOf("system" to systemPrompt, "user" to input), cloudModel, timeoutMs, maxTokens)
        return if (stripQuotes) content?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") else content
    }

    /** One non-streamed reply to [turns] (role to text) from the LAN server or [model] on OpenRouter; null on any failure. */
    private suspend fun completeTurns(
        turns: List<Pair<String, String>>,
        model: String?,
        timeoutMs: Long,
        maxTokens: Int,
    ): String? = completeContent(turns.map { it.first to JsonPrimitive(it.second) }, model, timeoutMs, maxTokens)

    /** Same as [completeTurns], but a turn may be an image array so a rewrite still sees the photo. */
    private suspend fun completeContent(
        turns: List<Pair<String, JsonElement>>,
        model: String?,
        timeoutMs: Long,
        maxTokens: Int,
    ): String? {
        val isLanModel = activeModelIsLan()
        val lanProvider = sharedPreferencesHelper.getLanProvider()
        val isReasoningModel = isReasoningModel(_activeChatModel.value)
        val requestUrl: String
        val requestKey: String
        val modelToUse: String
        val client = if (isLanModel) {
            val lanEndpoint = sharedPreferencesHelper.getLanEndpoint()
            if (lanEndpoint.isNullOrBlank()) return null
            requestUrl = "$lanEndpoint/v1/chat/completions"
            requestKey = sharedPreferencesHelper.getLanApiKeyForRequest()
            modelToUse = _activeChatModel.value ?: return null
            lanHttpClient
        } else if (DemoModel.isDemo(model) || DemoModel.isDemo(_activeChatModel.value)) {
            // The demo answers on its own client and has no key.
            requestKey = "demo"
            requestUrl = "https://openrouter.ai/api/v1/chat/completions"
            modelToUse = DemoModel.ID
            demoHttpClient
        } else {
            requestKey = sharedPreferencesHelper.getApiKeyFromPrefs("openrouter_api_key")
            if (requestKey.isBlank() || model.isNullOrBlank()) return null
            requestUrl = "https://openrouter.ai/api/v1/chat/completions"
            modelToUse = model
            httpClient
        }
        return try {
            withTimeout(timeoutMs.milliseconds) {
                withContext(Dispatchers.IO) {
                    val requestBody = buildJsonObject {
                            put("model", JsonPrimitive(modelToUse))
                            putJsonArray("messages") {
                                turns.forEach { (role, content) ->
                                    add(buildJsonObject {
                                        put("role", JsonPrimitive(role))
                                        put("content", content)
                                    })
                                }
                            }
                            put("stream", JsonPrimitive(false))
                            put("max_tokens", JsonPrimitive(maxTokens))
                            if (isLanModel && lanProvider == LAN_PROVIDER_OLLAMA && isReasoningModel) {
                                put("think", JsonPrimitive(false))
                                put("reasoning_effort", JsonPrimitive("none"))
                            }
                            if (isLanModel && lanProvider == LAN_PROVIDER_LLAMA_CPP && isReasoningModel) {
                                put("chat_template_kwargs", buildJsonObject {
                                    put("enable_thinking", JsonPrimitive(false))
                                })
                            }
                        }
                        val response = client.post(requestUrl) {
                            header("Authorization", "Bearer $requestKey")
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                        }
                        if (!response.status.isSuccess()) {
                            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "No details" }
                            throw Exception("API Error: ${response.status} - $errorBody")
                        }
                        response.body<JsonObject>()["choices"]?.jsonArray
                            ?.firstOrNull()?.jsonObject
                            ?.get("message")?.jsonObject
                            ?.get("content")?.jsonPrimitive?.content
                }
            }
        } catch (e: CancellationException) {
            // A timeout is a failed rewrite. Stopping, or leaving the chat, is not.
            if (e is TimeoutCancellationException) null else throw e
        } catch (_: Throwable) {
            null
        }
    }
    fun setSortOrder(sortOrder: SortOrder) {
        _sortOrder.value = sortOrder
        sharedPreferencesHelper.saveSortOrder(sortOrder)
        applySort()
    }
    suspend fun getFormattedChatHistoryEpubHtml(): String = withContext(Dispatchers.IO) {
        val messages = messagesForExport(includeImages = true) ?: return@withContext ""

        val currentModel = _activeChatModel.value ?: "Unknown"
        val appContext = getApplication<Application>().applicationContext
        val resolver: ContentResolver = appContext.contentResolver

        buildString {
            // Title
            append("""
                <h1 style="text-align: center; margin-bottom: 1em;">Chat with ${escapeHtmlText(currentModel)}</h1>
                <hr style="border: 0; border-top: 1px solid #000; margin-bottom: 2em;" />
            """.trimIndent())

            messages.forEachIndexed { index, message ->
                val rawText = getMessageText(message.content).trim()

                // Fix table spacing and convert to HTML
                val fixedText = ensureTableSpacing(rawText)
                val contentHtml = markdownToHtmlFragment(fixedText)

                // We use a simple div with NO margin/padding for the container
                // We use inline styles for the labels to keep colors but remove icons
                when (message.role) {
                    "user" -> {
                        append("""
                        <div style="margin: 0; padding: 0;">
                            <p style="margin: 0 0 0.2em 0; font-weight: bold; color: #222222;">User:</p>
                            <div style="margin: 0; padding: 0;">
                                $contentHtml
                            </div>
                            ${extractAndEmbedUserImages(message.content, resolver)}
                        </div>
                        """.trimIndent())
                    }
                    "assistant" -> {
                        append("""
                        <div style="margin: 0; padding: 0;">
                            <p style="margin: 0 0 0.2em 0; font-weight: bold; color: #666666;">Assistant:</p>
                            <div style="margin: 0; padding: 0;">
                                $contentHtml
                            </div>
                            ${message.imageUri?.let { embedGeneratedImage(it, resolver) } ?: ""}
                        </div>
                        """.trimIndent())
                    }
                }

                // Minimal separator: Just a small blank space or a very thin line
                if (index < messages.size - 1) {
                    append("""
                        <div style="margin-top: 1em; margin-bottom: 1em; border-top: 1px solid #eee;"></div>
                    """.trimIndent())
                }
            }
        }
    }
    fun saveEpubToDownloads(innerHtml: String) = writeDownload(
        success = str(R.string.save_epub_ok),
        failure = { str(R.string.save_epub_failed, it.message) },
    ) {
        val currentModel = _activeChatModel.value ?: "Unknown"
        val dateTime = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        val filename = "${currentModel.replace("/", "-")}_$dateTime.epub"
        saveBinaryFileToDownloads(filename, createEpubBytes(currentModel, innerHtml), "application/epub+zip")
    }
    private fun createEpubBytes(title: String, contentHtml: String): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val zip = ZipOutputStream(outputStream)

        // 1. mimetype (MUST be the first file, and MUST be STORED/Uncompressed for Apple Books)
        val mimetypeBytes = "application/epub+zip".toByteArray(Charsets.UTF_8)
        val mimetypeEntry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = mimetypeBytes.size.toLong()
            compressedSize = mimetypeBytes.size.toLong()
            val crc = CRC32()
            crc.update(mimetypeBytes)
            this.crc = crc.value
        }
        zip.putNextEntry(mimetypeEntry)
        zip.write(mimetypeBytes)
        zip.closeEntry()

        // 2. META-INF/container.xml
        // We use trimMargin("|") to ensure absolutely no whitespace before <?xml
        val containerXml = """
            |<?xml version="1.0"?>
            |<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                |<rootfiles>
                    |<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                |</rootfiles>
            |</container>
        """.trimMargin()
        zip.putNextEntry(ZipEntry("META-INF/container.xml"))
        zip.write(containerXml.toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        // 3. Prepare XHTML Content
        val xhtmlContent = """
            |<?xml version="1.0" encoding="utf-8"?>
|<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
|<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
|<head>
|<title>${escapeHtmlText(title)}</title>
|<style>
|body { font-family: sans-serif; margin: 5px; padding: 0; }
|img { max-width: 100%; height: auto; display: block; margin-top: 0.5em; }
|/* CODE BLOCK STYLE */
|pre {
|background: transparent;
|border-left: 4px solid #888888;
|padding: 5px 5px 5px 10px;
|overflow-x: auto;
|white-space: pre-wrap;
|font-size: 0.9em;
|margin: 0.5em 0;
|}
|p { margin-top: 0; margin-bottom: 0.5em; }
|/* LIST STYLES - Explicit indentation to override reader defaults */
|ul, ol {
|margin: 0 0 0.5em 0;
|padding: 0 0 0 2em; /* Force 2em indentation on left */
|}
|li {
|margin: 0;
|padding: 0;
|}
|/* TABLE STYLES */
|.table-wrapper {
|width: 100%;
|overflow-x: auto;
|margin-bottom: 1em;
|border: 1px solid #eee;
|}
|table {
|border-collapse: collapse;
|width: 100%;
|font-size: 0.9em;
|margin: 0;
|}
|th, td {
|border: 1px solid #444;
|padding: 0.4em;
|text-align: left;
|vertical-align: top;
|}
|th {
|background-color: #f0f0f0;
|font-weight: bold;
|}
|</style>
|</head>
|<body>
|${makeHtmlXhtmlCompliant(contentHtml)}
|</body>
|</html>
        """.trimMargin()

        // 4. OEBPS/content.opf (The Manifest)
        val uuid = UUID.randomUUID().toString()
        val opfContent = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
                |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                    |<dc:title>$title</dc:title>
                    |<dc:language>en</dc:language>
                    |<dc:identifier id="BookId" opf:scheme="UUID">$uuid</dc:identifier>
                    |<dc:creator opf:role="aut">GradatiON AI</dc:creator>
                |</metadata>
                |<manifest>
                    |<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    |<item id="content" href="chat.xhtml" media-type="application/xhtml+xml"/>
                |</manifest>
                |<spine toc="ncx">
                    |<itemref idref="content"/>
                |</spine>
            |</package>
        """.trimMargin()
        zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
        zip.write(opfContent.toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        // 5. OEBPS/toc.ncx (Table of Contents)
        val ncxContent = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
            |<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                |<head>
                    |<meta name="dtb:uid" content="$uuid"/>
                    |<meta name="dtb:depth" content="1"/>
                    |<meta name="dtb:totalPageCount" content="0"/>
                    |<meta name="dtb:maxPageNumber" content="0"/>
                |</head>
                |<docTitle><text>$title</text></docTitle>
                |<navMap>
                    |<navPoint id="navPoint-1" playOrder="1">
                        |<navLabel><text>Chat History</text></navLabel>
                        |<content src="chat.xhtml"/>
                    |</navPoint>
                |</navMap>
            |</ncx>
        """.trimMargin()
        zip.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
        zip.write(ncxContent.toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        // 6. OEBPS/chat.xhtml (The actual content)
        zip.putNextEntry(ZipEntry("OEBPS/chat.xhtml"))
        zip.write(xhtmlContent.toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        zip.close()
        return outputStream.toByteArray()
    }
    // Helper to make standard HTML bits more friendly to XML/EPUB parsers
    private fun makeHtmlXhtmlCompliant(html: String): String {
        var compliant = html
            // Close break tags
            .replace("<br>", "<br/>")
            // Close horizontal rules
            .replace("<hr>", "<hr/>")
            .replace("<hr ", "<hr ")
            // Ensure images are self-closing
            .replace(Regex("<img([^>]+)(?<!/)>"), "<img$1 />")
            // INJECT LIST SEMANTICS
            .replace("<ul>", "<ul epub:type=\"list\">")
            .replace("<ol>", "<ol epub:type=\"list\">")

        // WRAP TABLES FOR SCROLLING
        if (compliant.contains("<table")) {
            compliant = compliant
                .replace("<table>", "<div class=\"table-wrapper\"><table epub:type=\"table\">")
                .replace("</table>", "</table></div>")
        }

        return compliant
    }
    private fun saveBinaryFileToDownloads(filename: String, bytes: ByteArray, mimeType: String) {
        writeBytesToDownloads(filename, mimeType, bytes)
    }

    private fun writeBytesToDownloads(filename: String, mimeType: String, bytes: ByteArray): Uri {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, WorkspacePaths.mediaStoreRelativePath())
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { out ->
            out.write(bytes)
        } ?: throw Exception("Cannot open output stream")
        return uri
    }
    private fun applySort() {
        val sortedList = when (_sortOrder.value) {
            SortOrder.ALPHABETICAL -> allOpenRouterModels.sortedBy {
                ModelNames.withoutProvider(it.displayName, it.apiIdentifier).lowercase()
            }
            SortOrder.BY_DATE -> allOpenRouterModels.sortedByDescending { it.created }
        }
        _openRouterModels.postValue(sortedList)
    }

    /** One OpenRouter model list. A failed status is an empty list, the same as the three calls were. */
    private suspend fun openRouterModels(url: String, map: (ModelData) -> LlmModel): List<LlmModel> {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) return emptyList()
        return response.body<OpenRouterResponse>().data.map(map)
    }

    private fun ModelData.asLlmModel(
        vision: Boolean = false,
        image: Boolean = false,
        reasoning: Boolean = false,
        transcription: Boolean = false,
    ) = LlmModel(
        displayName = name,
        apiIdentifier = id,
        isVisionCapable = vision,
        isImageGenerationCapable = image,
        isReasoningCapable = reasoning,
        isTranscription = transcription,
        created = created,
        isFree = id.endsWith(":free"),
    )

    fun fetchOpenRouterModels() {
        viewModelScope.launch {
            try {
                val regularModels = openRouterModels("https://openrouter.ai/api/v1/models") {
                    it.asLlmModel(
                        vision = it.architecture.input_modalities.contains("image"),
                        image = it.architecture.output_modalities?.contains("image") ?: false,
                        reasoning = it.supportedParameters?.contains("reasoning") ?: false,
                    )
                }
                val transcriptionModels = openRouterModels(
                    "https://openrouter.ai/api/v1/models?output_modalities=transcription",
                ) { it.asLlmModel(transcription = true) }
                val imageGenModels = openRouterModels(
                    "https://openrouter.ai/api/v1/models?output_modalities=image",
                ) { it.asLlmModel(image = true) }

                // Combine and merge by ID, ensuring capabilities are preserved/combined
                val allModels = regularModels + transcriptionModels + imageGenModels
                val merged = allModels
                    .groupBy { it.apiIdentifier }
                    .map { (id, models) ->
                        // Take the model with the most information (prefer regular, but merge flags)
                        val base = models.first() // could be any
                        LlmModel(
                            displayName = base.displayName,
                            apiIdentifier = base.apiIdentifier,
                            isVisionCapable = models.any { it.isVisionCapable },
                            isImageGenerationCapable = models.any { it.isImageGenerationCapable },
                            isReasoningCapable = models.any { it.isReasoningCapable },
                            isTranscription = models.any { it.isTranscription },
                            created = base.created,
                            isFree = base.isFree,
                            isLANModel = false
                        )
                    }

                allOpenRouterModels = merged
                saveOpenRouterModels(allOpenRouterModels)
                applySort()
            } catch (e: Exception) {
                _errorMessage.postValue("Error fetching models: ${e.message}")
            }
        }
    }



    fun modelExists(apiIdentifier: String): Boolean {
        val customModels = sharedPreferencesHelper.getCustomModels()
        val builtInModels = getBuiltInModels()
        return (customModels + builtInModels).any { it.apiIdentifier.equals(apiIdentifier, ignoreCase = true) }
    }

    fun addCustomModel(model: LlmModel) {
        val customModels = sharedPreferencesHelper.getCustomModels().toMutableList()
        if (!customModels.any { it.apiIdentifier.equals(model.apiIdentifier, ignoreCase = true) }) {
            customModels.add(model)
            sharedPreferencesHelper.saveCustomModels(customModels)
            _customModelsUpdated.postValue(Event(Unit))
        }
    }

    fun saveOpenRouterModels(models: List<LlmModel>) {
        sharedPreferencesHelper.saveOpenRouterModels(models)
    }

    fun getOpenRouterModels() {
        allOpenRouterModels = sharedPreferencesHelper.getOpenRouterModels()
        if (allOpenRouterModels.isEmpty() || !allOpenRouterModels.any { it.isFree }) {
            fetchOpenRouterModels()
        } else {
            applySort()
        }
    }
    private fun migrateOpenRouterModels() {
        if (sharedPreferencesHelper.getOpenRouterReasoningMigrated()) return
        val savedModels = sharedPreferencesHelper.getOpenRouterModels()
        val refresh = openRouterCacheMissingReasoning(alreadyMigrated = false, models = savedModels)
        sharedPreferencesHelper.saveOpenRouterReasoningMigrated()
        if (refresh) {
            sharedPreferencesHelper.clearOpenRouterModels()
            fetchOpenRouterModels()
        }
    }
    private fun getModerationErrorMessage(baseMessage: String, metadata: ModerationErrorMetadata): String {
        val reasons = metadata.reasons.joinToString(", ")
        val flaggedText = if (metadata.flagged_input.length > 50) {
            "${metadata.flagged_input.take(47)}..."
        } else {
            metadata.flagged_input
        }

        return "Content moderation: $baseMessage\n\n" +
                "Reasons: $reasons\n" +
                "Flagged content: \"$flaggedText\"\n" +
                "Provider: ${metadata.provider_name}\n" +
                "Model: ${metadata.model_slug}"
    }

    private fun getFriendlyErrorMessage(code: Int, originalMessage: String): String {
        return when (code) {
            400 -> "Invalid request: $originalMessage"
            401 -> "Authentication failed: Please check your API key"
            402 -> "Insufficient credits: Please add more credits to your account"
            403 -> "Content moderation: $originalMessage"  // Now handled by getModerationErrorMessage
            408 -> "Request timeout: Please try again"
            429 -> "Rate limited: Please wait before making more requests"
            502 -> "Model unavailable: The selected model is currently down"
            503 -> "Service unavailable: No available providers meet your requirements"
            else -> "$originalMessage (Code: $code)"
        }
    }
    private suspend fun downloadImages(imageUrls: List<String>): List<ScenePhoto.GeneratedPicture> {
        val saved = mutableListOf<ScenePhoto.GeneratedPicture>()
        val app = getApplication<Application>()
        withContext(Dispatchers.IO) {
            imageUrls.forEachIndexed { index, imageUrl ->
                try {
                    // Providers return either a base64 data URL or, for some image models, a plain https link.
                    val (imageBytes, mimeType) = if (imageUrl.startsWith("https://")) {
                        fetchGeneratedImage(imageUrl)
                    } else {
                        val mime = imageUrl.substringAfter("data:", "").substringBefore(";").ifBlank { "image/png" }
                        Base64.getDecoder().decode(imageUrl.substringAfter(",")) to mime
                    }
                    val jpeg = ScenePhoto.encode(imageBytes)
                    // The chat keeps a copy under app files. Downloads is the copy the user can open
                    // later; losing that file must not take the picture out of the story.
                    val owned = jpeg?.let { ScenePhoto.store(app, it)?.toString() }
                    val downloads = try {
                        val extension = when (mimeType) {
                            "image/jpeg", "image/jpg" -> "jpg"
                            "image/webp" -> "webp"
                            else -> "png"
                        }
                        val filename = "generated_image_${System.currentTimeMillis()}_$index.$extension"
                        writeBytesToDownloads(filename, mimeType, imageBytes).toString()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    val uri = owned ?: downloads
                    if (uri == null) {
                        _toastUiEvent.postValue(Event(str(R.string.image_download_failed, "could not save")))
                    } else {
                        saved.add(ScenePhoto.GeneratedPicture(uri, jpeg?.let { ScenePhoto.dataUrl(it) }))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _toastUiEvent.postValue(Event(str(R.string.image_download_failed, e.message)))
                }
            }
        }
        return saved
    }

    private suspend fun fetchGeneratedImage(url: String): Pair<ByteArray, String> {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) error(response.status.toString())
        val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length > MAX_GENERATED_IMAGE_BYTES) error("image too large")
        val bytes = response.body<ByteArray>()
        if (bytes.size > MAX_GENERATED_IMAGE_BYTES) error("image too large")
        val mime = response.headers[HttpHeaders.ContentType]?.substringBefore(";")?.trim()
            ?.takeIf { it.startsWith("image/") } ?: "image/png"
        return bytes to mime
    }
    fun getActiveLlmModel(): LlmModel? {
        val id = _activeChatModel.value ?: return null
        val customModels = sharedPreferencesHelper.getCustomModels()
        val builtIns = getBuiltInModels()
        return customModels.find { it.apiIdentifier == id } ?: builtIns.find { it.apiIdentifier == id } }
    fun activeModelIsLan(): Boolean = getActiveLlmModel()?.isLANModel == true

    fun activeModelIsDemo(): Boolean = DemoModel.isDemo(_activeChatModel.value)

    private suspend fun fetchLanModels(provider: String): List<LlmModel> = when (provider) {
        "llama_cpp" -> fetchOpenAiModelList(LanListAuth.NONE) { id, obj -> llamaCppModel(id, obj) }
        "lm_studio", "mlx_lm" -> fetchOpenAiModelList(LanListAuth.NONE) { id, _ -> plainLanModel(id) }
        "ollama" -> fetchOllamaModels()
        "omlx" -> fetchOpenAiModelList(LanListAuth.PLACEHOLDER) { id, _ -> plainLanModel(id) }
        "nativ" -> fetchOpenAiModelList(LanListAuth.IF_PRESENT) { id, _ -> plainLanModel(id) }
        "hermes_agent" -> fetchOpenAiModelList(LanListAuth.PLACEHOLDER) { id, _ -> hermesLanModel(id) }
        else -> emptyList()
    }

    fun startLanModelsFetch() {
        val provider = getCurrentLanProvider()
        lanFetchJob?.cancel()
        lanFetchJob = viewModelScope.launch {
            try {
                _lanModels.value = fetchLanModels(provider)
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    _lanModels.value = emptyList()
                    _toastUiEvent.value = Event(str(R.string.lan_models_timeout, provider))
                }
            } catch (e: Exception) {
                _lanModels.value = emptyList()
                _toastUiEvent.value = Event(str(R.string.lan_models_failed, provider, e.message))
            }
        }
    }

    private enum class LanListAuth { NONE, IF_PRESENT, PLACEHOLDER }

    /** OpenAI-compatible `/v1/models` list. Providers differ only in auth and how a row is labeled. */
    private suspend fun fetchOpenAiModelList(
        auth: LanListAuth,
        map: (String, JsonObject) -> LlmModel,
    ): List<LlmModel> = withTimeout(10_000.milliseconds) {
        withContext(Dispatchers.IO) {
            val lanEndpoint = sharedPreferencesHelper.getLanEndpoint()
            if (lanEndpoint.isNullOrBlank()) {
                throw IllegalStateException("LAN endpoint not configured. Please set it in settings.")
            }
            val response = lanHttpClient.get("$lanEndpoint/v1/models") {
                timeout { requestTimeoutMillis = 10000 }
                when (auth) {
                    LanListAuth.NONE -> Unit
                    LanListAuth.IF_PRESENT -> {
                        val apiKey = sharedPreferencesHelper.getLanApiKey()
                        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                    }
                    LanListAuth.PLACEHOLDER ->
                        header("Authorization", "Bearer ${sharedPreferencesHelper.getLanApiKeyForRequest()}")
                }
            }
            if (!response.status.isSuccess()) {
                throw Exception("Server returned ${response.status}: ${response.status.description}")
            }
            val modelsArray = response.body<JsonObject>()["data"]?.jsonArray ?: return@withContext emptyList()
            modelsArray.mapNotNull { modelJson ->
                try {
                    val obj = modelJson.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    map(id, obj)
                } catch (_: Exception) {
                    null
                }
            }.sortedBy { it.displayName.lowercase() }
        }
    }

    private fun plainLanModel(id: String) = LlmModel(
        displayName = id,
        apiIdentifier = id,
        isVisionCapable = false,
        isImageGenerationCapable = false,
        isReasoningCapable = false,
        created = System.currentTimeMillis() / 1000,
        isFree = true,
        isLANModel = true,
    )

    private fun hermesLanModel(id: String) = LlmModel(
        displayName = id,
        apiIdentifier = id,
        isVisionCapable = id.contains("vision", ignoreCase = true) || id.contains("vl", ignoreCase = true),
        isImageGenerationCapable = false,
        isReasoningCapable = id.contains("reason", ignoreCase = true) ||
            id.contains("thinking", ignoreCase = true) ||
            id.contains("r1", ignoreCase = true),
        created = System.currentTimeMillis() / 1000,
        isFree = true,
        isLANModel = true,
    )

    private fun llamaCppModel(id: String, obj: JsonObject): LlmModel {
        val description = obj["status"]?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""
        val isLoaded = description.equals("loaded", ignoreCase = true) ||
            (description.contains("loaded", ignoreCase = true) &&
                !description.contains("unloaded", ignoreCase = true))
        return LlmModel(
            displayName = if (description.isNotEmpty()) "$id - $description" else id,
            apiIdentifier = id,
            isVisionCapable = false,
            isImageGenerationCapable = false,
            isReasoningCapable = false,
            created = System.currentTimeMillis() / 1000,
            isFree = true,
            isLANModel = true,
            isLoaded = isLoaded,
        )
    }

    private suspend fun fetchOllamaModels(): List<LlmModel> = withTimeout(10_000.milliseconds) {
        withContext(Dispatchers.IO) {
            val lanEndpoint = sharedPreferencesHelper.getLanEndpoint()
                ?: throw IllegalStateException("LAN endpoint not configured")
            val response = lanHttpClient.get("$lanEndpoint/api/tags") {
                timeout { requestTimeoutMillis = 10000 }
            }
            if (!response.status.isSuccess()) {
                throw Exception("Failed to fetch LAN models: ${response.status}")
            }
            val modelsArray = response.body<JsonObject>()["models"]?.jsonArray ?: return@withContext emptyList()
            modelsArray.mapNotNull { modelJson ->
                try {
                    val name = modelJson.jsonObject["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    plainLanModel(name)
                } catch (_: Exception) {
                    null
                }
            }.sortedBy { it.displayName.lowercase() }
        }
    }

    suspend fun loadLlamaCppModel(model: LlmModel): Boolean = llamaCppModelAction(model, "load")

    suspend fun unloadLlamaCppModel(model: LlmModel): Boolean = llamaCppModelAction(model, "unload")

    private suspend fun llamaCppModelAction(model: LlmModel, action: String): Boolean = withContext(Dispatchers.IO) {
        val lanEndpoint = sharedPreferencesHelper.getLanEndpoint()
        if (lanEndpoint.isNullOrBlank()) {
            throw IllegalStateException("LAN endpoint not configured.")
        }
        val lanKey = sharedPreferencesHelper.getLanApiKey()
        val response = lanHttpClient.post("$lanEndpoint/models/$action") {
            contentType(ContentType.Application.Json)
            if (!lanKey.isNullOrBlank()) {
                header("Authorization", "Bearer $lanKey")
            }
            setBody(mapOf("model" to model.apiIdentifier))
        }
        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "Unknown error" }
            throw Exception("Failed to $action model: ${response.status} - $errorBody")
        }
        val responseBody = try { response.body<JsonObject>() } catch (_: Exception) { null }
        responseBody?.get("success")?.jsonPrimitive?.booleanOrNull == true
    }

    fun getLanEndpoint(): String? = sharedPreferencesHelper.getLanEndpoint()

    private fun buildWebSearchPlugin(): List<Plugin>? {
        if (isRpMode() || !sharedPreferencesHelper.getWebSearchBoolean() || activeModelIsLan()) return null

        val engine = getWebSearchEngine()
        val maxResults = sharedPreferencesHelper.getWebSearchMaxResults()

        val plugin = if (engine != "default") {
            Plugin(id = "web", engine = engine, maxResults = maxResults)
        } else {
            Plugin(id = "web", maxResults = maxResults)
        }
        return listOf(plugin)
    }
    fun setWebSearchAutoOff(autoOff: Boolean) { shouldAutoOffWebSearch = autoOff }
    fun shouldAutoOffWebSearch() = shouldAutoOffWebSearch
    fun resetWebSearchAutoOff() { shouldAutoOffWebSearch = false }
    fun getCurrentLanProvider(): String = sharedPreferencesHelper.getLanProvider()
    fun setUserScrolledDuringStream(value: Boolean) {
        _userScrolledDuringStream.value = value
    }
    fun setPendingUserImageUri(uriStr: String?) {
        pendingUserImageUri = uriStr
    }

    /** The file URI for a photo still staged, so a refused send can put it back. */
    fun pendingImageUri(): String? = pendingUserImageUri
    fun isImageGenerationModel(modelIdentifier: String?): Boolean {
        if (modelIdentifier == null) return false

        val customModels = sharedPreferencesHelper.getCustomModels()
        val allModels = getBuiltInModels() + customModels

        val model = allModels.find { it.apiIdentifier == modelIdentifier }
        return model?.isImageGenerationCapable ?: false
    }
    private fun parseOpenRouterError(responseText: String): String {
        return try {
            val errorResponse = json.decodeFromString<OpenRouterErrorResponse>(responseText)

            // Special handling for moderation errors (403)
            if (errorResponse.error.code == 403 && errorResponse.error.metadata != null) {
                try {
                    val moderationMetadata = json.decodeFromJsonElement<ModerationErrorMetadata>(
                        errorResponse.error.metadata
                    )
                    return getModerationErrorMessage(errorResponse.error.message, moderationMetadata)
                } catch (e: Exception) {
                    getFriendlyErrorMessage(errorResponse.error.code, errorResponse.error.message)
                }
            } else {
                getFriendlyErrorMessage(errorResponse.error.code, errorResponse.error.message)
            }
        } catch (e: Exception) {
            "Unknown error format: ${responseText.take(200)}"
        }
    }
    private fun escapeHtmlText(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun markdownToHtmlFragment(markdown: String): String {
        // ✅ Core + TABLES EXTENSION (renders | Col | perfectly)
        val parser = Parser.builder()
            .extensions(listOf(TablesExtension.create()))  // ✅ Tables magic
            .build()

        // Replies are untrusted and the export opens in a browser (and runs the copy script), so raw
        // HTML is shown as text and script-capable link targets are dropped.
        val renderer = HtmlRenderer.builder()
            .extensions(listOf(TablesExtension.create()))  // ✅ Renderer too
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build()

        val document = parser.parse(markdown)
        var html = renderer.render(document)

        // ✅ AUTO-LINK BARE URLs: "https://example.com" → <a>https://...</a>
        // Handles "[26] https://...", inline URLs, citations perfectly.
        // Skips already-linked <a>, code blocks, etc.
        html = html.replace(Regex("""(?<!["'=/])(?<!href=["'])https?://[^\s<>"'()]+(?<!["'=/])""")) { match ->
            "<a href=\"${match.value}\" target=\"_blank\">${match.value}</a>"
        }

        return html
    }

// ✅ Fragment printButton.setOnClickListener() & getFormattedChatHistoryHtmlWithImages() UNCHANGED.
// Now image chats get: Full MD parsing (tables/lists/bold) + regex citations `[26] https://...` → clickable + embedded imgs!


    private fun extractAndEmbedUserImages(content: JsonElement, resolver: ContentResolver): String {
        return MessageContent.imageUrls(content)
            .filter { it.startsWith("data:image/") }
            .joinToString("") { dataUrl ->
                "<br><img src='$dataUrl' style='max-width: 100%; height: auto; border-radius: 6px; margin-top: 1em;'>"
            }
    }

    private fun embedGeneratedImage(imageUriStr: String, resolver: ContentResolver): String? {
        return try {
            val uri = Uri.parse(imageUriStr)
            resolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                bitmap?.let {
                    val baos = ByteArrayOutputStream()
                    it.compress(Bitmap.CompressFormat.PNG, 90, baos)
                    val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                    "<br><img src='data:image/png;base64,$base64' style='max-width: 100%; height: auto; border-radius: 6px; margin-top: 1em;'>"
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    suspend fun getFormattedChatHistoryStyledHtml(): String = withContext(Dispatchers.IO) {
        val messages = messagesForExport(includeImages = true) ?: return@withContext ""

        val currentModel = _activeChatModel.value ?: "Unknown"
        val appContext = getApplication<Application>().applicationContext
        val resolver: ContentResolver = appContext.contentResolver

        buildString {
            append("""
            <h1 style="color: #222222; font-size: 2em; font-weight: 600; border-bottom: 1px solid #dddddd; padding-bottom: .3em; margin: 0 0 1em 0;">Chat with ${escapeHtmlText(currentModel)}</h1>
            <div style="margin-top: 2em;"></div>
        """.trimIndent())

            messages.forEachIndexed { index, message ->
                val rawText = getMessageText(message.content).trim()

                // ✅ 1. Apply the fix to the raw Markdown first
                val fixedText = ensureTableSpacing(rawText)

                // ✅ 2. Then convert that fixed Markdown to HTML
                val contentHtml = markdownToHtmlFragment(fixedText)

                when (message.role) {
                    "user" -> {
                        append("""
                        <div style="margin-bottom: 2em;">
                            <h3 style="color: #222222; margin-bottom: 0.5em;">👤 User</h3>
                            <div style="background: #f2f2f2; padding: 0.05em 0.5em; border-radius: 6px; border-left: 4px solid #444444;">
                                $contentHtml
                            </div>
                            ${extractAndEmbedUserImages(message.content, resolver)}
                        </div>
                    """.trimIndent())
                    }
                    "assistant" -> {
                        val textDiv = if (rawText.isNotBlank()) {
                            """
                            <div style="background: #f2f2f2; padding: 1em; border-radius: 6px; border-left: 4px solid #888888;">
                                $contentHtml
                            </div>
                        """.trimIndent()
                        } else ""
                        append("""
                        <div style="margin-bottom: 2em;">
                            <h3 style="color: #666666; margin-bottom: 0.5em;">🤖 Assistant</h3>
                            $textDiv
                            ${message.imageUri?.let { embedGeneratedImage(it, resolver) } ?: ""}
                        </div>
                    """.trimIndent())
                    }
                }

                if (index < messages.size - 1) {
                    append("<hr style='border: none; border-top: 1px solid #dddddd; margin: 2em 0;'>")
                }
            }
        }.replace(
            Regex("""<pre[^>]*>.*?</pre>""", RegexOption.DOT_MATCHES_ALL),
            "<div class=\"code-wrapper\">\$0</div>"
        )
    }
    fun getFormattedChatHistoryMarkdownandPrint(): String {
        val messages = messagesForExport(includeImages = false) ?: return ""

        val currentModel = _activeChatModel.value ?: "Unknown"

        return buildString {
            append("# Chat with $currentModel")
            append("\n\n")

            messages.forEachIndexed { index, message ->
                // 1. Get the raw text
                val rawText = getMessageText(message.content).trim()

                // 2. ✅ APPLY THE FIX HERE
                // This ensures the table inside this specific message gets its newline
                val contentText = ensureTableSpacing(rawText)

                when (message.role) {
                    "user" -> {
                        append("**👤 User:**\n\n")
                        append(contentText)
                    }
                    "assistant" -> {
                        append("**🤖 Assistant:**\n\n")
                        append(contentText)
                    }
                }

                if (index < messages.size - 1) {
                    append("\n\n---\n\n")
                }
            }
        }
    }
    private fun ensureTableSpacing(markdown: String): String {
        // Split into mutable list of lines to manipulate them
        val lines = markdown.lines().toMutableList()

        var i = 0
        // We loop until size - 1 because we need to peek at the NEXT line (i+1)
        while (i < lines.size - 1) {
            val currentLine = lines[i].trim()
            val nextLine = lines[i+1].trim()

            // 1. Identify a Table Start
            // A header starts with '|', contains another '|'
            // A separator starts with '|', contains '---'
            val isHeader = currentLine.startsWith("|") && currentLine.contains("|")
            val isSeparator = nextLine.startsWith("|") && nextLine.contains("---")

            if (isHeader && isSeparator) {
                // We found a table at index 'i'.
                // 2. Check if the PREVIOUS line (i-1) exists and has text
                if (i > 0 && lines[i-1].isNotBlank()) {
                    // 3. INSERT A BLANK LINE
                    lines.add(i, "")

                    // Skip the line we just added and the header we just processed
                    i += 2
                    continue
                }
            }
            i++
        }

        // Reassemble the string
        return lines.joinToString("\n")
    }

    // ✅ ViewModel: Update ONLY `buildFullPrintStyledHtml()` (add link wrapping – rest unchanged)
    private fun buildFullPrintStyledHtml(innerHtml: String): String {
        val copyJs = """
<script>
(function() {
    'use strict';
    const wrappers = document.querySelectorAll('.code-wrapper');
    wrappers.forEach(wrapper => {
        const btn = document.createElement('button');
        btn.className = 'copy-btn';
        btn.textContent = 'Copy';
        btn.title = 'Copy code to clipboard';
        btn.addEventListener('click', e => {
            e.stopPropagation();
            const pre = wrapper.querySelector('pre');
            const text = pre.textContent || pre.innerText || '';
            if (!text) return;
            
            const copyFn = (text) => {
                if (navigator.clipboard && window.isSecureContext) {
                    navigator.clipboard.writeText(text).then(success).catch(() => fallback(text));
                } else {
                    fallback(text);
                }
            };
            
            const fallback = (text) => {
                const ta = document.createElement('textarea');
                ta.value = text;
                ta.style.position = 'fixed'; ta.style.left = '-9999px'; ta.style.top = '-9999px';
                document.body.appendChild(ta);
                ta.focus(); ta.select();
                const ok = document.execCommand('copy');
                document.body.removeChild(ta);
                ok ? success() : fail();
            };
            
            const success = () => {
                const orig = btn.textContent;
                btn.textContent = 'Copied'; btn.style.background = '#555555';
                setTimeout(() => { btn.textContent = orig; btn.style.background = ''; }, 2000);
            };
            const fail = () => {
                btn.textContent = 'Failed';
                setTimeout(() => { btn.textContent = 'Copy'; }, 2000);
            };
            
            copyFn(text);
        });
        wrapper.appendChild(btn);
    });
})();
</script>
""".trimIndent()
        return """
<!DOCTYPE html>
<html><head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Chat History</title>
    <style>
        * { box-sizing: border-box; }
        body { 
            margin: 40px 20px;  
            padding: 0;         
            max-width: 100%;    
            font-family: -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif,"Apple Color Emoji","Segoe UI Emoji";
            font-size: 16px; line-height: 1.5; color: #222222; background: white;
        }
        .markdown-body { font-size: 16px; line-height: 1.5; }
        
        /* ✅ TITLE: Underline only, no border (always) */
        h1 { 
            color: #222222 !important; font-size: 2em !important; font-weight: 600 !important; 
            text-decoration: underline !important;
            border-bottom: none !important;
            padding-bottom: .3em !important; margin: 0 0 1em 0 !important; 
        }
        
        /* ✅ LINKS: Dark gray, underlined. WRAP LONG URLs (break-all for citations/URLs on mobile/narrow screens) */
        a { 
            color: #333333; 
            text-decoration: underline; 
            word-break: break-all !important;     /* ✅ Breaks long URLs at chars */
            overflow-wrap: break-word !important; /* ✅ Fallback for older browsers */
            hyphens: none !important;             /* ✅ Optional: hyphenate if possible */
        }
        a:hover, a:focus { text-decoration: underline; }
        
        strong { font-weight: 600; }
        pre, code { font-family: 'SFMono-Regular',Consolas,'Liberation Mono',Menlo,monospace; font-size: 14px; }
        code { background: #f2f2f2; border-radius: 6px; padding: .2em .4em; }
        pre { background: #f2f2f2; border-radius: 6px; padding: 16px; overflow: auto; margin: 1em 0; }
        .code-wrapper {
            position: relative !important;
            margin: 1em 0 !important;
        }
        .code-wrapper pre {
            margin: 0 !important;
            position: relative;
            z-index: 1;
        }
        .copy-btn {
    position: absolute !important;
    top: 8px !important;
    right: 8px !important;
    background: #333 !important;
    color: #fff !important;
    border: 1px solid #555 !important;
    padding: 6px 12px !important;
    border-radius: 4px !important;
    font-size: 12px !important;
    font-weight: bold !important;
    cursor: pointer !important;
    z-index: 10 !important;
    line-height: 1.2;
    box-shadow: 0 1px 3px rgba(0,0,0,0.2);
    transition: background 0.2s;
}
.copy-btn:hover {
    background: #444 !important;
}
.copy-btn:active {
    transform: scale(0.98);
}
        blockquote { border-left: 4px solid #dddddd; color: #666666; padding-left: 1em; margin: 1em 0; }
        table { border-collapse: collapse; width: 100%; margin: 1em 0; }
        th, td { border: 1px solid #cccccc; padding: .75em; text-align: left; }
        th { background: #f2f2f2; font-weight: 600; }
        ul, ol { padding-left: 2em; margin: 1em 0; }
        img { max-width: 100%; height: auto; }
        del { color: #666666; }
        input[type="checkbox"] { margin: 0 .25em 0 0; vertical-align: middle; }
        
        /* ✅ CHAT: Print look BAKED IN (always: no HR, spacers only after assistant, assistant plain text) */
        hr { display: none !important; }  /* ✅ No lines ever */
        
        /* Spacers: Tiny after user, 2em only after assistant */
        /* A user turn is told from a reply by the inline padding on its content div, not by color. */
        div[style*="margin-bottom: 2em"]:has(> div[style*="padding: 0.05em"]) {
            margin-bottom: 0.25em !important;  /* User → assistant: tight */
        }
        div[style*="margin-bottom: 2em"]:not(:has(> div[style*="padding: 0.05em"])) {
            margin-bottom: 2em !important;  /* Assistant → next: spacer only */
        }
        
        /* Assistant: Plain text (no bg/border/padding minimal) */
        h3 + div:not([style*="padding: 0.05em"]) {
            background: none !important;
            background-color: transparent !important;
            border: none !important;
            border-left: none !important;
            border-left-color: transparent !important;
            padding: 0.25em 0.5em !important;
            border-radius: 0 !important;
            margin: 0 !important;
        }
        
        /* User: Unchanged (keeps bg/border) – no overrides */
        h3 + div[style*="padding: 0.05em"] { /* Keeps inline */ }
        
        /* ✅ PRINT: Just page tweaks (look is already print-perfect). Links wrap too */
        @media print {
            body { 
                margin: 0.5in 0.25in !important;  
                padding: 0 !important;
                max-width: none !important;
                font-size: 12pt !important; line-height: 1.5 !important;
            }
            h1 { page-break-after: avoid; }
            a { 
                text-decoration: underline !important; 
                color: #333333 !important; 
                word-break: break-all !important; 
                overflow-wrap: break-word !important; 
            }
            pre {
    white-space: pre-wrap !important;
    word-break: break-word !important;
    overflow-wrap: break-word !important;
    padding: 12px !important;
    font-size: 10pt !important;
    page-break-inside: avoid !important;
    margin-bottom: 1em !important;
}
.code-wrapper {
    position: static !important;
    overflow: visible !important;
    page-break-inside: avoid !important;
    margin: 1em 0 !important;
    width: 100% !important;
}
            .copy-btn {
                display: none !important;
            }
            @page { margin: 0.5in; }
        }
    </style>
</head><body>
    <div class="markdown-body">$innerHtml</div>
    $copyJs
</body></html>
    """.trimIndent()
    }


    // --- GradatiON RP ---

    fun isRpMode(): Boolean = _chatMode.value == ChatMode.RP

    private fun sessionModeValue(): String = (_chatMode.value ?: ChatMode.ASK).storageValue

    private fun sessionCharacterId(): Long? {
        if (!isRpMode() || sharedPreferencesHelper.isRpLlmMode()) return null
        return sharedPreferencesHelper.getRpActiveCharacterId() ?: preservedSessionCharacterId
    }

    private fun sessionIsLlm(): Boolean = isRpMode() && sharedPreferencesHelper.isRpLlmMode()

    /** Message count at the last memory upkeep, per chat (see [RpAutoMemory.shouldUpdate]). */
    private val rpMemoryRunAt = HashMap<Long, Int>()
    private var rpMemoryJob: Job? = null
    /** Facts for a chat that does not have a session id yet (a new chat, before the first save). */
    private var draftRpFacts: String? = null

    fun currentRpFacts(): String {
        draftRpFacts?.let { return it }
        val id = currentSessionId ?: return ""
        return sharedPreferencesHelper.getRpFacts(id)
    }

    fun saveCurrentRpFacts(text: String) {
        val clean = text.trim()
        val id = currentSessionId
        if (id == null) {
            draftRpFacts = clean
        } else {
            draftRpFacts = null
            sharedPreferencesHelper.saveRpFacts(id, clean)
        }
    }

    /**
     * After a finished RP reply: once the chat nears the API window, have the model rewrite this
     * chat's Facts. The Memory note the user wrote is passed in as read-only and is not saved over.
     */
    private fun maybeUpdateRpMemory(latestReply: String) {
        if (!sharedPreferencesHelper.isRpAutoMemory() || rpMemoryJob?.isActive == true) return
        val modelId = _activeChatModel.value ?: return
        val demo = DemoModel.isDemo(modelId)
        val llm = sharedPreferencesHelper.isRpLlmMode()
        val character = _activeRpCharacter.value
        if (!llm && character == null) return
        val characterId = if (llm) null else character?.id
        val turns = _chatMessages.value.orEmpty()
            .filter { (it.role == "user" || it.role == "assistant") && !isAssistantPlaceholder(it) }
            .map { it.role to RpAutoMemory.turnBody(getMessageText(it.content), isImageMessage(it)) }
            .toMutableList()
        // The finished reply may not be in the list yet.
        if (turns.lastOrNull()?.let { it.first == "assistant" && it.second.trim() == latestReply.trim() } != true) {
            turns += "assistant" to latestReply
        }
        val sessionKey = currentSessionId ?: RpAutoMemory.UNSAVED_KEY
        val budget = sharedPreferencesHelper.getChatMemoryCount()
        val previousRun = rpMemoryRunAt[sessionKey] ?: 0
        if (!RpAutoMemory.shouldUpdate(turns.size, budget, previousRun)) return

        val charName = if (llm) getApplication<Application>().getString(R.string.rp_llm_speaker) else character!!.name
        // Same fallback as the prompt and the lore scan, so a {{user}} key still matches these notes.
        val userName = RpPromptEngine.chatNames(null, sharedPreferencesHelper.activeRpPersonaName()).second
        val userMemory = sharedPreferencesHelper.getRpMemory(characterId)
        val facts = currentRpFacts()
        val prompt = RpAutoMemory.prompt(
            charName, userName, userMemory, facts, RpAutoMemory.transcript(turns, charName, userName)
        )
        val isLan = activeModelIsLan() && !demo
        val endpoint = if (isLan) sharedPreferencesHelper.getLanEndpoint()?.takeIf { it.isNotBlank() }?.let { "$it/v1/chat/completions" }
            else "https://openrouter.ai/api/v1/chat/completions"
        val apiKey = if (isLan) sharedPreferencesHelper.getLanApiKeyForRequest() else activeChatApiKey
        if (endpoint == null || (!isLan && !demo && apiKey.isBlank())) return
        // Which chat this note is for: the session (null while it is still unsaved) and the open-chat epoch.
        val launchSessionId = currentSessionId
        val launchEpoch = sessionEpoch
        rpMemoryJob = viewModelScope.launch(Dispatchers.IO) {
            val reply = llmService.completeOnce(
                prompt = prompt,
                apiKey = apiKey,
                modelId = modelId,
                endpoint = endpoint,
                maxTokens = 400,
                lanProvider = if (isLan) sharedPreferencesHelper.getLanProvider() else null,
                isReasoningModel = isReasoningModel(modelId),
                client = if (demo) demoHttpClient else if (isLan) lanHttpClient else null
            )
            val note = RpAutoMemory.clean(reply, userMemory, charName, userName)
            // The user may have switched chats meanwhile; the note belongs to the one it was built for.
            withContext(Dispatchers.Main) {
                val sameChat = sessionEpoch == launchEpoch &&
                    (launchSessionId == null || currentSessionId == launchSessionId)
                if (note != null) {
                    // A chat that was unsaved at launch may have been saved since, which is still the same chat.
                    if (sameChat) {
                        saveCurrentRpFacts(note)
                    } else if (launchSessionId != null && repository.getSessionById(launchSessionId) != null) {
                        sharedPreferencesHelper.saveRpFacts(launchSessionId, note)
                    }
                }
                // An unsaved chat that gained an id keeps its mark there, not on the next new chat.
                if (launchSessionId == null) rpMemoryRunAt.remove(RpAutoMemory.UNSAVED_KEY)
                val key = RpAutoMemory.runKey(launchSessionId, currentSessionId, sameChat)
                if (key != null) {
                    rpMemoryRunAt[key] = RpAutoMemory.watermarkAfter(previousRun, turns.size, note != null)
                }
            }
        }
    }

    private fun finalizeAssistantContent(text: String): String {
        if (!isRpMode()) return text
        // Reply is complete — Stop must not treat the finished bubble as a mid-stream partial.
        discardableRpAssistantInFlight = false
        val cleaned = rpDelegate.cleanReply(text)
        rpRegenRestoreFallback = null
        if (cleaned.isBlank() || isNonSwipeableRpAssistantText(cleaned)) {
            pendingRpSwipeAppend = false
            return cleaned
        }
        maybeUpdateRpMemory(cleaned)
        if (pendingRpSwipeAppend) {
            pendingRpSwipeAppend = false
            appendRpSwipeAlt(cleaned)
        } else if (rpSwipeState.alts.isEmpty()) {
            // Seed first alt so the swipe bar (and ›) is available after a normal reply.
            // The picture is filled in when the message lands, a moment after this text.
            rpSwipeState = RpSwipeState(alts = listOf(cleaned), index = 0)
            persistRpSwipeState()
            updateRpSwipeNav()
        }
        return cleaned
    }

    suspend fun refreshActiveRpCharacter() {
        val character = rpDelegate.getActiveCharacter()
        // Prefer setValue on main so canSendRpMessage() sees the character immediately
        // (postValue can leave .value null for a beat after Start chat / session load).
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            _activeRpCharacter.value = character
            _rpChromeRefreshEvent.value = Event(Unit)
        } else {
            _activeRpCharacter.postValue(character)
            _rpChromeRefreshEvent.postValue(Event(Unit))
        }
    }

    /**
     * If the open RP thread is still greeting-only (or empty) for the active character, refresh
     * that bubble after an edit / rematch so name/greeting changes show without restarting the chat.
     * [refresh] is the card line from before the change. A rewritten opening is left alone unless
     * the greeting text on the card itself changed. [stillCurrent] drops a stale refresh when a
     * later persona change has already superseded it.
     */
    suspend fun syncActiveCharacterGreetingIfIdle(
        refresh: RpGreetingSync.Refresh? = null,
        stillCurrent: () -> Boolean = { true }
    ) {
        if (!isRpMode() || sharedPreferencesHelper.isRpLlmMode()) return
        if (!stillCurrent()) return
        val epoch = sessionEpoch
        val character = rpDelegate.getActiveCharacter() ?: return
        if (epoch != sessionEpoch || !stillCurrent()) return
        val messages = _chatMessages.value.orEmpty()
        if (messages.any { it.role == "user" }) return
        val onlyGreeting = messages.size == 1 &&
            messages[0].role == "assistant" &&
            !isAssistantPlaceholder(messages[0])
        if (!onlyGreeting && messages.isNotEmpty()) return
        val greeting = rpDelegate.greetingMessage(character)
        val current = if (onlyGreeting) getMessageText(messages[0].content) else ""
        if (!RpGreetingSync.shouldReplace(
                current = current,
                expandedBefore = refresh?.expandedBefore,
                expandedNow = greeting,
                templateChanged = refresh?.templateChanged == true
            )
        ) return
        if (epoch != sessionEpoch || !stillCurrent()) return
        _chatMessages.value = listOf(
            FlexibleMessage(role = "assistant", content = JsonPrimitive(greeting))
        )
        // Don't mint a session while a parked keepDraftId owns the RP draft pointer
        // (LLM-mismatch ephemeral greeting). Rematch with draft cleared still autosaves.
        val parkedDraft = sharedPreferencesHelper.getRpDraftSessionId(ChatMode.RP)
        if (currentSessionId != null || parkedDraft == null) {
            autoSaveChat()
        }
    }

    fun setChatMode(mode: ChatMode) {
        if (_chatMode.value == mode) return
        val previous = _chatMode.value ?: ChatMode.ASK
        // Only park a real open session. Writing null would wipe a keepDraftId left by
        // LLM-mismatch ephemeral greeting (currentSessionId == null). While a previous switch is
        // still loading, the open session belongs to the mode before [previous] (a swipe that
        // peeks at a tab and comes back), so parking it would cross the drafts.
        if (currentSessionId != null && sessionTransitionJob?.isActive != true) {
            sharedPreferencesHelper.saveRpDraftSessionId(previous, currentSessionId)
        }
        // Flip mode inside the transition so cancel+regen-restore still sees the previous mode.
        beginSessionTransition {
            _chatMode.value = mode
            sharedPreferencesHelper.saveChatMode(mode)
            if (mode == ChatMode.RP) {
                refreshActiveRpCharacter()
                restoreDraftOrNewChat(ChatMode.RP)
            } else {
                restoreDraftOrNewChat(ChatMode.ASK)
            }
        }
    }

    private var launchBlank = true

    private suspend fun restoreDraftOrNewChat(mode: ChatMode) {
        // Drop stale work if Ask↔RP flipped again while we were suspended.
        if (_chatMode.value != mode) return
        if (networkJob?.isActive == true) return
        val blankLaunch = launchBlank
        launchBlank = false
        val draftId = sharedPreferencesHelper.getRpDraftSessionId(mode)
        val draftSession = draftId?.let { repository.getSessionById(it) }
        if (draftSession != null) {
            if (_chatMode.value != mode) return
            // Ask-side LLM toggle updates the global flag without rewriting the RP draft.
            // Prefer that preference over a mismatched draft when returning to RP, but keep the
            // draft id so Ask↔RP can resume the thread once isLlm matches again.
            if (mode == ChatMode.RP &&
                draftSession.isLlm != sharedPreferencesHelper.isRpLlmMode()
            ) {
                val keepDraftId = draftSession.id
                if (sharedPreferencesHelper.isRpLlmMode()) {
                    clearOpenTranscript(clearDraft = false)
                } else {
                    // Don't autosave a greeting row — that would overwrite keepDraftId.
                    startNewRpChatKeepingCharacterInternal(persist = false)
                }
                sharedPreferencesHelper.saveRpDraftSessionId(ChatMode.RP, keepDraftId)
                return
            }
            // Use internal load so we don't cancel this transition job.
            loadChatInternal(draftSession.id)
        } else {
            if (_chatMode.value != mode) return
            if (draftId != null) sharedPreferencesHelper.saveRpDraftSessionId(mode, null)
            if (mode == ChatMode.RP) {
                startNewRpChatKeepingCharacterInternal(persist = !blankLaunch)
            } else {
                clearOpenTranscript()
            }
        }
    }

    fun toggleChatMode() {
        val next = if (isRpMode()) ChatMode.ASK else ChatMode.RP
        setChatMode(next)
    }

    fun startRpChatWithCharacter(character: RpCharacter, carryFacts: Boolean = false) {
        _rpThreadOpenedEvent.value = Event(Unit)
        val facts = if (carryFacts) currentRpFacts() else ""
        beginSessionTransition {
            val previous = _chatMode.value ?: ChatMode.ASK
            if (currentSessionId != null) {
                sharedPreferencesHelper.saveRpDraftSessionId(previous, currentSessionId)
            }
            rpDelegate.activateCharacter(character)
            preservedSessionCharacterId = null
            sharedPreferencesHelper.saveRpLlmMode(false)
            // Synchronous so Send is gated correctly before observers run.
            _activeRpCharacter.value = character
            _rpChromeRefreshEvent.value = Event(Unit)
            _chatMode.value = ChatMode.RP
            sharedPreferencesHelper.saveChatMode(ChatMode.RP)
            // Intentional Start chat replaces any parked keepDraftId with this greeting thread.
            clearOpenTranscript(clearDraft = true)
            draftRpFacts = facts
            val greeting = rpDelegate.greetingMessage(character)
            _chatMessages.value = listOf(
                FlexibleMessage(role = "assistant", content = JsonPrimitive(greeting))
            )
            autoSaveChat()
            // Drop leftover composer text from the previous thread.
            _composerRestoreEvent.value = Event("")
        }
    }

    fun startRpLlmChat() {
        _rpThreadOpenedEvent.value = Event(Unit)
        beginSessionTransition {
            val previous = _chatMode.value ?: ChatMode.ASK
            if (currentSessionId != null) {
                sharedPreferencesHelper.saveRpDraftSessionId(previous, currentSessionId)
            }
            sharedPreferencesHelper.saveRpLlmMode(true)
            // Keep the selected character id so LLM-off can restore greeting/chrome.
            // getActiveCharacter() already returns null while LLM mode is on.
            preservedSessionCharacterId = null
            refreshActiveRpCharacter()
            _chatMode.value = ChatMode.RP
            sharedPreferencesHelper.saveChatMode(ChatMode.RP)
            val parkedId = sharedPreferencesHelper.getRpDraftSessionId(ChatMode.RP)
            val parked = parkedId?.let { repository.getSessionById(it) }
            if (parked != null && parked.isLlm) {
                // Re-enable LLM after mismatch — resume the parked LLM thread.
                loadChatInternal(parked.id)
            } else {
                // Preserve character draft pointer until this LLM thread autosaves.
                clearOpenTranscript(clearDraft = false)
            }
            _composerRestoreEvent.value = Event("")
        }
    }

    /**
     * What lore keys are matched against. The character's name, scenario, personality, speech
     * style, greeting and description stay from the front, so a long chat does not forget the
     * setting or the way they talk. Memory
     * and this chat's facts share the rest of that pin, so a long Memory note cannot hide a fact.
     * [focus] is a beat that must still match after it leaves the recent window (the reply a
     * rewrite is changing). The open chat and [extra] (the line about to be sent, a scene
     * reminder, a rewrite note) fill whatever is left, newest last.
     */
    private fun rpLoreScan(vararg extra: String, focus: List<String> = emptyList()): String {
        val pinned = ArrayList<String>()
        val notes = ArrayList<String>()
        val recent = ArrayList<String>()
        val llm = sharedPreferencesHelper.isRpLlmMode()
        val char = if (llm) null else _activeRpCharacter.value
        val (charName, userName) = RpPromptEngine.chatNames(char?.name, sharedPreferencesHelper.activeRpPersonaName())
        fun expand(text: String) = RpPromptEngine.expandMacros(text, charName, userName)
        if (char != null) {
            if (char.name.isNotBlank()) pinned += char.name
            val persona = sharedPreferencesHelper.activeRpPersonaName()
            if (persona.isNotBlank()) pinned += persona
            // Short fields first, so a clip keeps the setting and the way they talk, and cuts the tail.
            RpPromptEngine.loreCardFields(char).forEach { pinned += expand(it) }
        } else {
            val persona = sharedPreferencesHelper.activeRpPersonaName()
            if (persona.isNotBlank()) pinned += persona
        }
        if (llm || char != null) {
            val memory = sharedPreferencesHelper.getRpMemory(if (llm) null else char!!.id)
            if (memory.isNotBlank()) notes += expand(memory)
        }
        val facts = currentRpFacts()
        if (facts.isNotBlank()) notes += expand(facts)
        _chatMessages.value.orEmpty().forEach { msg ->
            if ((msg.role == "user" || msg.role == "assistant") && !isAssistantPlaceholder(msg)) {
                val text = getMessageText(msg.content)
                // A caption-less photo has no words. It is still a beat, so a key can match it.
                val shown = if (text.isNotBlank()) text
                    else if (isImageMessage(msg)) RpAutoMemory.PHOTO_BEAT
                    else ""
                // A caption or a line written as {{char}} / {{user}} still matches those people.
                if (shown.isNotBlank()) recent += expand(shown)
            }
        }
        extra.forEach { if (it.isNotBlank()) recent += expand(it) }
        return RpLore.sceneScan(pinned, recent, focus = focus.map { expand(it) }, notes = notes)
    }

    /** History is cut before the card. Null means the whole definition still fits. */
    private fun rpDefinitionCap(): Int? = RpApiMemory.definitionCap(
        messageCount = _chatMessages.value.orEmpty().count {
            (it.role == "user" || it.role == "assistant") && !isAssistantPlaceholder(it)
        },
        historyBudget = sharedPreferencesHelper.getChatMemoryCount()
    )

    /** True when a "continue" beat makes sense: RP, a character (or LLM) and a reply to build on. */
    fun canContinueRpStory(): Boolean =
        isRpMode() && canSendRpMessage() && _isAwaitingResponse.value != true &&
            _chatMessages.value.orEmpty().any { it.role == "assistant" && !isAssistantPlaceholder(it) }

    /** Continue: the character carries on inside its last reply, with no new words from the user (and no bubble for the prompt). */
    fun continueRpStory(): Boolean = sendRpUserMessage("", continueBeat = true)

    /** [imageUrl]: a photo for the scene as a data URL, sent with the words (or alone). */
    fun sendRpUserMessage(
        rawText: String,
        messageInstruct: String? = null,
        continueBeat: Boolean = false,
        imageUrl: String? = null
    ): Boolean {
        // The composer clears the staged file as soon as this returns. The message is built
        // later, on the prep job, so the URI has to be taken now or the bubble cannot open it.
        val capturedImageUri = if (imageUrl != null) pendingUserImageUri else null
        val parsed = rpDelegate.parseSendText(rawText)
        // A reminder is a scene note, not the user's next line. Continue stays its own instruction.
        val scene = parsed.reminder?.takeIf { it.isNotBlank() }?.let(RpPromptEngine::sceneNote)
        val extraInstruction = if (continueBeat) {
            listOfNotNull(RpPromptEngine.CONTINUE_DIRECTION, scene).joinToString("\n").ifBlank { null }
        } else {
            scene
        }
        val app = getApplication<Application>()
        // Reminder-only sends still need a visible user beat so the model has a turn to answer.
        val userText = when {
            continueBeat && parsed.userText.isBlank() -> RpPromptEngine.CONTINUE_USER_TURN
            parsed.userText.isNotBlank() -> parsed.userText
            !parsed.reminder.isNullOrBlank() -> app.getString(R.string.rp_reminder_continue)
            imageUrl != null -> ""
            else -> {
                _toastUiEvent.postValue(Event(app.getString(R.string.rp_message_empty)))
                return false
            }
        }
        // Block Ask↔RP before the async prompt build (awaiting flips later inside sendUserMessage).
        if (_isAwaitingResponse.value == true) {
            _toastUiEvent.postValue(Event(app.getString(R.string.rp_wait_for_reply)))
            return false
        }
        _isAwaitingResponse.value = true
        val epoch = sessionEpoch
        val draftToRestore = rawText
        // Defer swipe wipe until send actually starts — prep failure must not erase alts.
        rpPrepJob?.cancel()
        rpPrepJob = viewModelScope.launch {
            var sendStarted = false
            try {
                sessionTransitionJob?.join()
                if (epoch != sessionEpoch || !isRpMode()) {
                    _isAwaitingResponse.value = false
                    _composerRestoreEvent.postValue(Event(draftToRestore))
                    return@launch
                }
                if (!messageInstruct.isNullOrBlank()) {
                    sharedPreferencesHelper.saveRpPendingInstruct(messageInstruct)
                }
                val systemPrompt = rpDelegate.buildSystemPrompt(
                    character = rpDelegate.getActiveCharacter(),
                    extraInstruction = extraInstruction,
                    loreScan = rpLoreScan(parsed.userText, parsed.reminder.orEmpty()),
                    definitionCap = rpDefinitionCap(),
                    facts = currentRpFacts()
                )
                if (epoch != sessionEpoch || !isRpMode()) {
                    _isAwaitingResponse.value = false
                    _composerRestoreEvent.postValue(Event(draftToRestore))
                    return@launch
                }
                val content = if (imageUrl == null) JsonPrimitive(userText) else buildJsonArray {
                    if (userText.isNotBlank()) add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(userText))
                    })
                    add(buildJsonObject {
                        put("type", JsonPrimitive("image_url"))
                        put("image_url", buildJsonObject { put("url", JsonPrimitive(imageUrl)) })
                    })
                }
                if (!sendUserMessage(
                        content,
                        systemPrompt,
                        // Continue keeps swipe alts until the new text actually arrives.
                        clearRpSwipeOnStart = !continueBeat,
                        continueInPlace = continueBeat,
                        capturedImageUri = capturedImageUri,
                        useCapturedImageUri = imageUrl != null,
                    )
                ) {
                    _composerRestoreEvent.postValue(Event(draftToRestore))
                } else {
                    sendStarted = true
                }
            } catch (e: CancellationException) {
                _isAwaitingResponse.value = false
                // Stop during prep — composer was already cleared; put the draft back.
                if (!sendStarted) {
                    _composerRestoreEvent.postValue(Event(draftToRestore))
                }
                throw e
            } catch (_: Exception) {
                _isAwaitingResponse.value = false
                if (!sendStarted) {
                    _composerRestoreEvent.postValue(Event(draftToRestore))
                }
            } finally {
                // Only the active prep may clear instruct — a cancelled job must not wipe a newer Instruct.
                if (rpPrepJob === coroutineContext[Job]) {
                    sharedPreferencesHelper.saveRpPendingInstruct(null)
                    rpPrepJob = null
                }
            }
        }
        return true
    }

    fun canSendRpMessage(): Boolean {
        if (sharedPreferencesHelper.isRpLlmMode()) return true
        if (_activeRpCharacter.value != null) return true
        // Prefs may already have the id while LiveData refresh is still in flight.
        return sharedPreferencesHelper.getRpActiveCharacterId() != null
    }

    /**
     * Regenerate the last RP assistant reply without duplicating the user turn or creating an Ask fork.
     * Existing assistant text is stashed into swipe alts; the new reply is appended when it completes.
     * @return false if soft-failed (toast already shown); true if prep started.
     */
    fun regenerateLastRpReply(instruction: String? = null, rewrite: String? = null): Boolean {
        rpRewriteJob?.cancel()
        rpRewriteJob = null
        if (_isAwaitingResponse.value == true) {
            _toastUiEvent.postValue(
                Event(getApplication<Application>().getString(R.string.rp_wait_for_reply))
            )
            return false
        }
        if (!canSendRpMessage()) {
            _toastUiEvent.postValue(
                Event(getApplication<Application>().getString(R.string.rp_select_character))
            )
            return false
        }
        val messages = _chatMessages.value ?: return false
        val lastAssistantIndex = messages.indexOfLast { it.role == "assistant" && !isAssistantPlaceholder(it) }
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        // Taken before the reply is removed, so lore keys that live only in it still match.
        val focusedReply = if (lastAssistantIndex > lastUserIndex) {
            getMessageText(messages[lastAssistantIndex].content)
        } else {
            ""
        }
        if (lastUserIndex < 0) {
            _toastUiEvent.postValue(
                Event(getApplication<Application>().getString(R.string.rp_need_user_turn))
            )
            return false
        }
        // A Rewrite shows the model the reply it is changing, so it needs one to change.
        val rewriteTurns = if (rewrite.isNullOrBlank()) emptyList() else {
            val old = messages.getOrNull(lastAssistantIndex)
                ?.takeIf { lastAssistantIndex > lastUserIndex }
                ?.let { getMessageText(it.content) }
                ?.takeIf { it.isNotBlank() && !isNonSwipeableRpAssistantText(it) }
                ?: return false
            listOf(
                FlexibleMessage(role = "assistant", content = JsonPrimitive(old)),
                FlexibleMessage(role = "user", content = JsonPrimitive(RpPromptEngine.rewriteDirective(rewrite)))
            )
        }
        _toastUiEvent.postValue(
            Event(str(if (rewriteTurns.isEmpty()) R.string.rp_regen_started else R.string.rp_rewrite_started))
        )
        // Block Ask↔RP before truncate + async prompt build.
        _isAwaitingResponse.value = true
        val epoch = sessionEpoch
        // Only truncate an assistant reply that follows the last user turn (not the greeting).
        if (lastAssistantIndex > lastUserIndex) {
            stashRpSwipeFromLastAssistant()
            truncateWithoutFork(lastAssistantIndex)
            // So Stop mid-prep restores the stashed alt (pending was previously set only at resend).
            pendingRpSwipeAppend = true
        }
        clearForkMemory()
        rpPrepJob?.cancel()
        rpPrepJob = viewModelScope.launch {
            try {
                sessionTransitionJob?.join()
                if (epoch != sessionEpoch || !isRpMode()) {
                    _isAwaitingResponse.value = false
                    return@launch
                }
                if (!instruction.isNullOrBlank()) {
                    sharedPreferencesHelper.saveRpPendingInstruct(instruction)
                }
                val systemPrompt = rpDelegate.buildSystemPrompt(
                    character = rpDelegate.getActiveCharacter(),
                    extraInstruction = null,
                    loreScan = rpLoreScan(rewrite.orEmpty(), focus = RpRewrite.loreFocus(focusedReply)),
                    definitionCap = rpDefinitionCap(),
                    facts = currentRpFacts()
                )
                if (epoch != sessionEpoch || !isRpMode()) {
                    _isAwaitingResponse.value = false
                    return@launch
                }
                pendingRpSwipeAppend = true
                resendExistingPrompt(lastUserIndex, systemPrompt, rewriteTurns)
            } catch (e: CancellationException) {
                _isAwaitingResponse.value = false
                throw e
            } catch (_: Exception) {
                pendingRpSwipeAppend = false
                restoreRpSwipeAltIfMissingAssistant()
                _isAwaitingResponse.value = false
            } finally {
                // Only the active prep may clear instruct — a cancelled job must not wipe a newer Instruct.
                if (rpPrepJob === coroutineContext[Job]) {
                    sharedPreferencesHelper.saveRpPendingInstruct(null)
                    rpPrepJob = null
                }
            }
        }
        return true
    }

    private var rpRewriteJob: Job? = null

    /**
     * Rewrite: the reply at [position] comes back changed the way [instruction] asks, written with
     * the chat up to it, the reply itself and the note in view. The last reply streams in as a new
     * swipe (the old one stays a swipe back); an earlier one, or the greeting, is replaced in place
     * with an Undo, and everything after it is left as it was.
     * @return false if soft-failed (toast already shown).
     */
    fun rewriteRpReply(position: Int, instruction: String): Boolean {
        val note = instruction.trim()
        if (note.isEmpty() || !isRpMode()) return false
        if (_isAwaitingResponse.value == true || rpRewriteJob?.isActive == true) {
            _toastUiEvent.postValue(Event(str(R.string.rp_wait_for_reply)))
            return false
        }
        val messages = _chatMessages.value ?: return false
        val target = messages.getOrNull(position)?.takeIf { it.role == "assistant" && !isAssistantPlaceholder(it) }
            ?: return false
        val original = getMessageText(target.content)
        if (original.isBlank() || isNonSwipeableRpAssistantText(original)) return false
        val lastAssistantIndex = messages.indexOfLast { it.role == "assistant" && !isAssistantPlaceholder(it) }
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        val preceding = messages.subList(0, position).lastOrNull {
            (it.role == "user" || it.role == "assistant") && !isAssistantPlaceholder(it)
        }?.let { getMessageText(it.content) }.orEmpty()
        if (RpRewrite.streamsAsNewSwipe(position, lastAssistantIndex, lastUserIndex)) {
            return regenerateLastRpReply(rewrite = note)
        }
        if (!canSendRpMessage()) {
            _toastUiEvent.postValue(Event(str(R.string.rp_select_character)))
            return false
        }
        _toastUiEvent.postValue(Event(str(R.string.rp_rewrite_started)))
        // Hold the turn the way a reply does, so Send becomes Stop and a tap cancels this
        // instead of dropping it. The latest-reply path already does that via regenerate.
        _isAwaitingResponse.value = true
        val epoch = sessionEpoch
        rpRewriteJob = viewModelScope.launch {
            try {
                val systemPrompt = rpDelegate.buildSystemPrompt(
                    character = rpDelegate.getActiveCharacter(),
                    extraInstruction = null,
                    loreScan = rpLoreScan(note, focus = RpRewrite.loreFocus(original, preceding)),
                    definitionCap = rpDefinitionCap(),
                    facts = currentRpFacts()
                )
                val prefix = messages.take(position + 1).filter { it.role != "system" && !isAssistantPlaceholder(it) }
                val request = mutableListOf(FlexibleMessage(role = "system", content = JsonPrimitive(systemPrompt)))
                request.addAll(prefix)
                trimMessagesForApiMemory(request)
                // The reply, and the turn before it, stay even when the greeting ate the budget.
                val pinned = RpApiMemory.pinTail(request, prefix.takeLast(2)) { a, b ->
                    a.role == b.role && a.content == b.content
                }
                request.clear()
                request.addAll(pinned)
                request.add(FlexibleMessage(role = "user", content = JsonPrimitive(RpPromptEngine.rewriteDirective(note))))
                if (epoch != sessionEpoch) return@launch
                val rewritten = completeContent(
                    turns = request.toApiMessages().map { it.role to it.content },
                    model = _activeChatModel.value,
                    timeoutMs = 120_000,
                    maxTokens = sharedPreferencesHelper.getMaxTokens().toIntOrNull() ?: 12_000,
                )?.let { rpDelegate.cleanReply(it) }?.takeIf { it.isNotBlank() }
                // The chat moved on (switched, or this reply was edited or removed): don't write into it.
                val now = _chatMessages.value
                if (epoch != sessionEpoch || now?.getOrNull(position)?.let { getMessageText(it.content) } != original) return@launch
                if (rewritten == null) {
                    _toastUiEvent.postValue(Event(str(R.string.rp_rewrite_failed)))
                    return@launch
                }
                updateMessageAt(position, rewritten)
                _rpRewriteDone.value = Event(RpRewriteDone(position, original, rewritten))
            } catch (e: CancellationException) {
                throw e
            } finally {
                val job = coroutineContext[Job]
                // A Stop or a newer send already cleared this job; don't clear that send's turn.
                if (rpRewriteJob === job) {
                    rpRewriteJob = null
                    _isAwaitingResponse.value = false
                }
            }
        }
        return true
    }

    /** A finished in-place Rewrite, for the notice's Undo. */
    data class RpRewriteDone(val position: Int, val original: String, val rewritten: String)
    private val _rpRewriteDone = MutableLiveData<Event<RpRewriteDone>>()
    val rpRewriteDone: LiveData<Event<RpRewriteDone>> = _rpRewriteDone

    /** Undo puts the old reply back, but only while the rewritten one is still what's there. */
    fun undoRpRewrite(done: RpRewriteDone) {
        val current = _chatMessages.value?.getOrNull(done.position) ?: return
        if (getMessageText(current.content) != done.rewritten) return
        updateMessageAt(done.position, done.original)
    }

    /** Drop messages from [startIndex] onward without stashing an Ask-mode fork. */
    private fun truncateWithoutFork(startIndex: Int) {
        val current = _chatMessages.value?.toMutableList() ?: return
        if (startIndex < 0 || startIndex >= current.size) return
        current.subList(startIndex, current.size).clear()
        _chatMessages.value = current
    }

    private fun stashRpSwipeFromLastAssistant() {
        val messages = _chatMessages.value ?: return
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        val lastAssistantIndex = messages.indexOfLast { it.role == "assistant" && !isAssistantPlaceholder(it) }
        if (lastAssistantIndex < 0 || lastAssistantIndex < lastUserIndex) return
        val current = messages[lastAssistantIndex]
        val currentText = getMessageText(current.content)
        if (currentText.isBlank()) return
        if (isNonSwipeableRpAssistantText(currentText)) {
            // Provider/network Error bubbles stay out of swipe alts; Stop mid-regen still needs a restore seed.
            rpRegenRestoreFallback = currentText
            return
        }
        rpRegenRestoreFallback = null
        // Don't duplicate the already-selected seed on first regen; keep that index so
        // Stop/error restore brings back the variant the user was viewing, picture included.
        val (alts, pictures, index) = RpSwipeRules.stashAlt(
            rpSwipeState.alts,
            rpSwipeState.pictureUris,
            rpSwipeState.index,
            currentText,
            RpSwipeRules.pictureUriOf(current.imageUri),
        )
        rpSwipeState = RpSwipeState(alts = alts, index = index, pictureUris = pictures)
        persistRpSwipeState()
        updateRpSwipeNav()
    }

    private fun isNonSwipeableRpAssistantText(text: String): Boolean =
        text.startsWith("**Error:**")

    private fun appendRpSwipeAlt(text: String) {
        if (text.isBlank()) return
        val (alts, pictures, index) = RpSwipeRules.appendAlt(
            rpSwipeState.alts,
            rpSwipeState.pictureUris,
            text,
        )
        rpSwipeState = RpSwipeState(alts = alts, index = index, pictureUris = pictures)
        persistRpSwipeState()
        updateRpSwipeNav()
    }

    fun swipeRpPrev() {
        if (!canInteractWithRpSwipe()) return
        if (rpSwipeState.alts.isEmpty()) return
        val newIndex = (rpSwipeState.index - 1).coerceAtLeast(0)
        applyRpSwipeIndex(newIndex)
    }

    fun swipeRpNext() {
        if (!canInteractWithRpSwipe()) return
        if (rpSwipeState.alts.isEmpty()) {
            regenerateLastRpReply()
            return
        }
        if (rpSwipeState.index < rpSwipeState.alts.lastIndex) {
            applyRpSwipeIndex(rpSwipeState.index + 1)
        } else {
            regenerateLastRpReply()
        }
    }

    /** Swipe must not run while a reply/regen is in flight (would append beside thinking). */
    private fun canInteractWithRpSwipe(): Boolean =
        isRpMode() && _isAwaitingResponse.value != true

    private fun persistRpSwipeState() {
        val id = currentSessionId ?: return
        if (rpSwipeState.alts.isEmpty()) return
        rpSwipeStore.save(id, rpSwipeState)
    }

    private fun applyRpSwipeIndex(index: Int, persistChat: Boolean = true) {
        if (!canInteractWithRpSwipe()) return
        val alt = rpSwipeState.alts.getOrNull(index) ?: return
        val picture = RpSwipeRules.pictureForAlt(rpSwipeState.pictureUris, rpSwipeState.alts.size, index)
        rpSwipeState = rpSwipeState.copy(index = index)
        persistRpSwipeState()
        updateRpSwipeNav()
        val messages = _chatMessages.value?.toMutableList() ?: return
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        val lastAssistantIndex = messages.indexOfLast { it.role == "assistant" && !isAssistantPlaceholder(it) }
        // Never rewrite the greeting when there is no user turn yet.
        if (lastUserIndex < 0) return
        // Mid-regen/stream: thinking placeholder after the user — do not append a stale alt beside it.
        val thinkingAfterUser = messages.withIndex().any { (i, m) ->
            i > lastUserIndex && isAssistantPlaceholder(m)
        }
        if (thinkingAfterUser) return
        when {
            lastAssistantIndex > lastUserIndex -> {
                messages[lastAssistantIndex] = RpContinuation.withVersion(
                    messages[lastAssistantIndex],
                    alt,
                    picture,
                )
            }
            else -> {
                // Missing reply after user — append rather than overwrite greeting.
                messages.add(
                    FlexibleMessage(
                        role = "assistant",
                        content = JsonPrimitive(alt),
                        imageUri = picture?.takeIf { it.isNotEmpty() },
                    )
                )
            }
        }
        _chatMessages.value = messages
        if (persistChat) autoSaveChat()
    }

    private fun updateRpSwipeNav() {
        if (rpSwipeState.alts.isEmpty()) {
            _rpSwipeNav.postValue(null)
            return
        }
        _rpSwipeNav.postValue(
            RpSwipeNav(
                index = rpSwipeState.index + 1,
                total = rpSwipeState.alts.size,
                canPrev = rpSwipeState.index > 0,
                canNext = true
            )
        )
    }

    fun loadRpSwipeForSession(sessionId: Long) {
        pendingRpSwipeAppend = false
        rpSwipeState = rpSwipeStore.load(sessionId) ?: RpSwipeState()
        val messages = _chatMessages.value.orEmpty()
        val swipeable = RpSwipeRules.isSwipeableMessages(
            messages,
            isUser = { it.role == "user" },
            isAssistant = { it.role == "assistant" && !isAssistantPlaceholder(it) }
        )
        if (!swipeable || rpSwipeState.alts.isEmpty()) {
            if (!swipeable) forgetRpSwipeVersions()
            updateRpSwipeNav()
            return
        }
        updateRpSwipeNav()
        applyRpSwipeIndex(rpSwipeState.index, persistChat = false)
    }

    /** New chat in RP: clear transcript and reinject the active character greeting when applicable. */
    fun startNewRpChatKeepingCharacter() {
        beginSessionTransition {
            startNewRpChatKeepingCharacterInternal(persist = true)
        }
    }

    private suspend fun startNewRpChatKeepingCharacterInternal(persist: Boolean = true) {
        val llm = sharedPreferencesHelper.isRpLlmMode()
        val charId = sharedPreferencesHelper.getRpActiveCharacterId()
        // Only ephemeral mismatch (persist=false) keeps keepDraftId; intentional New chat / LLM-off replaces it.
        val preserveParked = !persist &&
            currentSessionId == null &&
            sharedPreferencesHelper.getRpDraftSessionId(ChatMode.RP) != null
        val facts = if (persist) currentRpFacts() else ""
        clearOpenTranscript(clearDraft = !preserveParked)
        draftRpFacts = if (persist) facts else null
        _composerRestoreEvent.value = Event("")
        if (llm || charId == null) return
        val character = rpRepo.getCharacterById(charId) ?: return
        _activeRpCharacter.value = character
        val greeting = rpDelegate.greetingMessage(character)
        _chatMessages.value = listOf(
            FlexibleMessage(role = "assistant", content = JsonPrimitive(greeting))
        )
        if (persist && !preserveParked) autoSaveChat()
    }

    suspend fun remappingCharacterSessions(oldId: Long, newId: Long) {
        if (oldId == newId) return
        repository.remapSessionCharacterId(oldId, newId)
        if (preservedSessionCharacterId == oldId) {
            preservedSessionCharacterId = null
        }
        val active = sharedPreferencesHelper.getRpActiveCharacterId()
        if (active == null || active == oldId) {
            sharedPreferencesHelper.saveRpActiveCharacterId(newId)
            refreshActiveRpCharacter()
            // Delete left an empty transcript; reinject greeting now that the id is restored.
            syncActiveCharacterGreetingIfIdle()
        }
    }

    fun getRpRepository(): RpRepository = rpRepo

}
