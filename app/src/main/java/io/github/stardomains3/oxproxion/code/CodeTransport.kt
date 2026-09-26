package io.github.stardomains3.oxproxion.code

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * A bidirectional text-frame pipe to a host. Knows nothing about harnesses or JSON-RPC; the
 * [HarnessAdapter] gives the frames meaning. Implementations: [WebSocketTransport] (the bridge),
 * and later SSH-tunnelled or relay transports (see the plan, "Transports").
 */
interface CodeTransport {
    val state: StateFlow<ConnectionState>
    /** Raw inbound frames, in order. Collectors that fall far behind drop the oldest (see buffer). */
    val incoming: SharedFlow<String>
    /** Last failure message, for the connection banner. */
    val lastError: String?
    fun connect()
    /** False when not connected or the send queue is full; callers surface that, never retry blindly. */
    fun send(frame: String): Boolean
    fun close()

    /**
     * Pause auto-reconnect while the app is backgrounded unless a session is running
     * ([setKeepAliveForSession]). Default no-op for transports that do not reconnect.
     */
    fun setAppBackgrounded(backgrounded: Boolean) {}

    /** Keep reconnecting (and stay connected) while a session turn is in flight. */
    fun setKeepAliveForSession(keepAlive: Boolean) {}
}

/**
 * Exponential backoff for transport reconnect: 0.5 s → 30 s cap, with full jitter.
 * Counter resets after a connection has stayed up for [RESET_AFTER_CONNECTED_MS].
 */
object ReconnectBackoff {
    const val INITIAL_MS = 500L
    const val MAX_MS = 30_000L
    const val RESET_AFTER_CONNECTED_MS = 60_000L

    /**
     * @param attempt 0-based fail count since last reset
     * @param random01 value in \[0.0, 1.0) for full jitter (delay uniformly in \[0, capped])
     */
    fun delayMs(attempt: Int, random01: Double): Long {
        val shift = attempt.coerceIn(0, 16)
        val exp = INITIAL_MS * (1L shl shift)
        val capped = min(exp, MAX_MS)
        val r = random01.coerceIn(0.0, 1.0)
        return (capped * r).toLong()
    }
}

/**
 * WebSocket to a GradatiON bridge: `Authorization: Bearer <token>`, JSON-RPC 2.0 text frames,
 * OkHttp pings every 20 s so dead links surface quickly on mobile networks.
 *
 * When [fingerprint] is non-blank, the OkHttp client pins that SHA-256 via [BridgeTls]
 * (self-signed bridges). Empty fingerprint keeps system-CA trust (legacy / demo).
 *
 * Auto-reconnects with [ReconnectBackoff] after drops (not after [close], and not after an auth
 * rejection). Reconnect pauses while the app is backgrounded unless [setKeepAliveForSession].
 */
class WebSocketTransport(
    private val url: String,
    private val token: String,
    fingerprint: String = "",
    private val client: OkHttpClient = BridgeTls.clientFor(fingerprint, url),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val random01: () -> Double = { Random.nextDouble() },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : CodeTransport {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = _state
    private val _incoming = MutableSharedFlow<String>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: SharedFlow<String> = _incoming
    @Volatile
    override var lastError: String? = null
        private set
    @Volatile
    private var socket: WebSocket? = null

    @Volatile private var userWantsConnection = false
    @Volatile private var intentionalClose = false
    @Volatile private var appBackgrounded = false
    @Volatile private var keepAliveForSession = false
    @Volatile private var attempt = 0
    @Volatile private var connectedAtMs: Long? = null
    @Volatile private var authRejected = false
    private var reconnectJob: Job? = null

    override fun setAppBackgrounded(backgrounded: Boolean) {
        appBackgrounded = backgrounded
        if (!backgrounded) scheduleReconnectIfNeeded()
    }

    override fun setKeepAliveForSession(keepAlive: Boolean) {
        keepAliveForSession = keepAlive
        if (keepAlive) scheduleReconnectIfNeeded()
    }

    private fun reconnectAllowed(): Boolean =
        userWantsConnection &&
            !intentionalClose &&
            !authRejected &&
            !(appBackgrounded && !keepAliveForSession)

    override fun connect() {
        intentionalClose = false
        authRejected = false
        userWantsConnection = true
        reconnectJob?.cancel()
        reconnectJob = null
        openSocket()
    }

    private fun openSocket() {
        if (_state.value == ConnectionState.CONNECTING || _state.value == ConnectionState.CONNECTED) return
        val request = runCatching {
            Request.Builder().url(url).apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
                header("X-Gradation-Client", "android/1")
            }.build()
        }.getOrElse {
            lastError = "Invalid address"
            _state.value = ConnectionState.FAILED
            return
        }
        _state.value = ConnectionState.CONNECTING
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastError = null
                connectedAtMs = nowMs()
                _state.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                _incoming.tryEmit(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) socket = null
                handleDrop(authReject = false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socket = null
                val reject = response?.code == 401 || response?.code == 403
                lastError = when (response?.code) {
                    401, 403 -> "The bridge rejected the pairing token"
                    null -> t.message ?: t.javaClass.simpleName
                    else -> "HTTP ${response.code}"
                }
                handleDrop(authReject = reject)
            }
        })
    }

    private fun handleDrop(authReject: Boolean) {
        val heldFor = connectedAtMs?.let { nowMs() - it } ?: 0L
        connectedAtMs = null
        if (authReject) {
            authRejected = true
            userWantsConnection = false
            _state.value = ConnectionState.FAILED
            return
        }
        if (heldFor >= ReconnectBackoff.RESET_AFTER_CONNECTED_MS) attempt = 0
        if (intentionalClose || !userWantsConnection) {
            _state.value = ConnectionState.DISCONNECTED
            return
        }
        _state.value = if (lastError != null) ConnectionState.FAILED else ConnectionState.DISCONNECTED
        scheduleReconnectIfNeeded()
    }

    private fun scheduleReconnectIfNeeded() {
        if (!reconnectAllowed()) return
        if (_state.value == ConnectionState.CONNECTED || _state.value == ConnectionState.CONNECTING) return
        if (reconnectJob?.isActive == true) return
        val n = attempt
        attempt = n + 1
        val wait = ReconnectBackoff.delayMs(n, random01())
        reconnectJob = scope.launch {
            sleeper(wait)
            if (reconnectAllowed() &&
                _state.value != ConnectionState.CONNECTED &&
                _state.value != ConnectionState.CONNECTING
            ) {
                openSocket()
            }
        }
    }

    override fun send(frame: String): Boolean = socket?.send(frame) ?: false

    override fun close() {
        intentionalClose = true
        userWantsConnection = false
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "bye")
        socket = null
        connectedAtMs = null
        _state.value = ConnectionState.DISCONNECTED
    }

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
