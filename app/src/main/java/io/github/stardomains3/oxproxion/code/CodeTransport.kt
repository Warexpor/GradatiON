package io.github.stardomains3.oxproxion.code

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

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
}

/**
 * WebSocket to a GradatiON bridge: `Authorization: Bearer <token>`, JSON-RPC 2.0 text frames,
 * OkHttp pings every 20 s so dead links surface quickly on mobile networks.
 *
 * Skeleton: connects, sends, receives, reports state. Reconnect with backoff, resume after
 * reconnect (`session/load` + replay from last seen seq), and certificate pinning for self-signed
 * bridges are specified in docs/code-mode-plan.md and not built yet.
 */
class WebSocketTransport(
    private val url: String,
    private val token: String,
    private val client: OkHttpClient = defaultClient
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

    override fun connect() {
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
                _state.value = ConnectionState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socket = null
                lastError = when (response?.code) {
                    401, 403 -> "The bridge rejected the pairing token"
                    null -> t.message ?: t.javaClass.simpleName
                    else -> "HTTP ${response.code}"
                }
                _state.value = ConnectionState.FAILED
            }
        })
    }

    override fun send(frame: String): Boolean = socket?.send(frame) ?: false

    override fun close() {
        socket?.close(1000, "bye")
        socket = null
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
