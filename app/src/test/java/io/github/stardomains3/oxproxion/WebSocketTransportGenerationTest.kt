package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.ConnectionState
import io.github.stardomains3.oxproxion.code.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Pure (no OkHttp network) coverage of R1: socket generation ignores stale callbacks so a
 * retired WebSocket cannot publish CONNECTED or poison a replacement via onClosed/onFailure.
 */
class WebSocketTransportGenerationTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class FakeSocket : WebSocket {
        override fun request(): Request = Request.Builder().url("ws://test.local/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }

    private class SocketFactory {
        data class Opened(val socket: FakeSocket, val listener: WebSocketListener)

        val opened = CopyOnWriteArrayList<Opened>()
        private val seq = AtomicInteger(0)

        val factory: (Request, WebSocketListener) -> WebSocket = { _, listener ->
            seq.incrementAndGet()
            val socket = FakeSocket()
            opened += Opened(socket, listener)
            socket
        }
    }

    private fun okResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()

    private fun transport(factory: SocketFactory): WebSocketTransport =
        WebSocketTransport(
            url = "ws://127.0.0.1:9/bridge",
            token = "tok",
            fingerprint = "",
            client = OkHttpClient.Builder()
                .callTimeout(1, TimeUnit.MILLISECONDS)
                .build(),
            scope = scope,
            sleeper = { _ -> },
            random01 = { 0.0 },
            nowMs = { 1_000L },
            webSocketFactory = factory.factory,
        )

    @Test
    fun staleOnOpen_afterCloseAndReconnect_doesNotPublishConnected() {
        val factory = SocketFactory()
        val t = transport(factory)

        t.connect()
        assertEquals(ConnectionState.CONNECTING, t.state.value)
        assertEquals(1, factory.opened.size)
        val stale = factory.opened[0]

        t.close()
        assertEquals(ConnectionState.DISCONNECTED, t.state.value)

        t.connect()
        assertEquals(2, factory.opened.size)
        val current = factory.opened[1]
        assertEquals(ConnectionState.CONNECTING, t.state.value)

        stale.listener.onOpen(stale.socket, okResponse(stale.socket.request()))
        assertEquals(ConnectionState.CONNECTING, t.state.value)

        current.listener.onOpen(current.socket, okResponse(current.socket.request()))
        assertEquals(ConnectionState.CONNECTED, t.state.value)

        t.close()
    }

    @Test
    fun staleOnFailure_whileReplacementConnecting_doesNotPoison() {
        val factory = SocketFactory()
        val t = transport(factory)

        t.connect()
        val stale = factory.opened[0]
        t.close()
        t.connect()
        assertEquals(2, factory.opened.size)
        assertEquals(ConnectionState.CONNECTING, t.state.value)

        stale.listener.onFailure(stale.socket, RuntimeException("old boom"), null)
        assertEquals(ConnectionState.CONNECTING, t.state.value)
        assertNull(t.lastError)

        val current = factory.opened[1]
        current.listener.onOpen(current.socket, okResponse(current.socket.request()))
        assertEquals(ConnectionState.CONNECTED, t.state.value)
        assertNull(t.lastError)

        t.close()
    }

    @Test
    fun staleOnClosed_whileReplacementConnected_doesNotDisconnect() {
        val factory = SocketFactory()
        val t = transport(factory)

        t.connect()
        val stale = factory.opened[0]
        t.close()
        t.connect()
        val current = factory.opened[1]
        current.listener.onOpen(current.socket, okResponse(current.socket.request()))
        assertEquals(ConnectionState.CONNECTED, t.state.value)

        stale.listener.onClosed(stale.socket, 1000, "bye")
        assertEquals(ConnectionState.CONNECTED, t.state.value)

        t.close()
    }

    @Test
    fun currentOnFailure_stillFailsAndRecordsError() {
        val factory = SocketFactory()
        // Park reconnect so we can observe FAILED before openSocket runs again.
        val resumeReconnect = CompletableDeferred<Unit>()
        val t = WebSocketTransport(
            url = "ws://127.0.0.1:9/bridge",
            token = "tok",
            fingerprint = "",
            client = OkHttpClient.Builder().callTimeout(1, TimeUnit.MILLISECONDS).build(),
            scope = scope,
            sleeper = { resumeReconnect.await() },
            random01 = { 0.0 },
            nowMs = { 1_000L },
            webSocketFactory = factory.factory,
        )

        t.connect()
        assertEquals(1, factory.opened.size)
        val current = factory.opened[0]
        current.listener.onFailure(current.socket, RuntimeException("network down"), null)

        assertEquals(ConnectionState.FAILED, t.state.value)
        assertEquals("network down", t.lastError)

        t.close()
        resumeReconnect.complete(Unit)
    }

    @Test
    fun concurrentConnectWhileOpeningDoesNotOpenASecondSocket() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val opened = AtomicInteger(0)
        val t = WebSocketTransport(
            url = "ws://127.0.0.1:9/bridge",
            token = "tok",
            fingerprint = "",
            client = OkHttpClient.Builder().callTimeout(1, TimeUnit.MILLISECONDS).build(),
            scope = scope,
            sleeper = { _ -> },
            random01 = { 0.0 },
            nowMs = { 1_000L },
            webSocketFactory = { _, _ ->
                opened.incrementAndGet()
                FakeSocket()
            },
            beforeOpenSocket = {
                started.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            },
        )
        val first = thread(name = "connect-a") { t.connect() }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        val second = thread(name = "connect-b") { t.connect() }
        // The second connect must wait out the in-flight open, not pass the state check.
        Thread.sleep(150)
        release.countDown()
        first.join(3_000)
        second.join(3_000)
        assertEquals(1, opened.get())
        assertEquals(ConnectionState.CONNECTING, t.state.value)
        t.close()
        assertFalse(t.send("{\"method\":\"session/prompt\"}"))
    }

    @Test
    fun failedOpenDuringReconnectSchedulesAnotherAttempt() {
        val opens = AtomicInteger(0)
        val sleeps = Channel<Unit>(Channel.RENDEZVOUS)
        val t = WebSocketTransport(
            url = "ws://127.0.0.1:9/bridge",
            token = "tok",
            fingerprint = "",
            client = OkHttpClient.Builder().callTimeout(1, TimeUnit.MILLISECONDS).build(),
            scope = scope,
            sleeper = { sleeps.receive() },
            random01 = { 0.0 },
            nowMs = { 1_000L },
            webSocketFactory = { _, _ ->
                if (opens.incrementAndGet() < 3) throw IllegalStateException("down")
                FakeSocket()
            },
        )
        t.connect()
        assertEquals(ConnectionState.FAILED, t.state.value)
        assertEquals(1, opens.get())
        assertTrue(sleeps.trySend(Unit).isSuccess)
        assertEquals(2, opens.get())
        assertEquals(ConnectionState.FAILED, t.state.value)
        assertTrue(sleeps.trySend(Unit).isSuccess)
        assertEquals(3, opens.get())
        assertEquals(ConnectionState.CONNECTING, t.state.value)
        t.close()
    }

    @Test
    fun returningToTheForegroundSkipsTheBackoffSleep() {
        val factory = SocketFactory()
        val gate = CompletableDeferred<Unit>()
        val t = WebSocketTransport(
            url = "ws://127.0.0.1:9/bridge",
            token = "tok",
            fingerprint = "",
            client = OkHttpClient.Builder().callTimeout(1, TimeUnit.MILLISECONDS).build(),
            scope = scope,
            sleeper = { gate.await() },
            random01 = { 1.0 },
            nowMs = { 1_000L },
            webSocketFactory = factory.factory,
        )
        t.connect()
        val first = factory.opened[0]
        first.listener.onFailure(first.socket, RuntimeException("drop"), null)
        assertEquals(ConnectionState.FAILED, t.state.value)
        assertEquals(1, factory.opened.size)
        t.setAppBackgrounded(false)
        assertEquals(2, factory.opened.size)
        assertEquals(ConnectionState.CONNECTING, t.state.value)
        t.close()
        gate.cancel()
    }

    @Test
    fun foregroundAfterADropWhileAwayOpensImmediately() {
        val factory = SocketFactory()
        val t = transport(factory)
        t.connect()
        val first = factory.opened[0]
        first.listener.onOpen(first.socket, okResponse(first.socket.request()))
        t.setAppBackgrounded(true)
        first.listener.onFailure(first.socket, RuntimeException("drop"), null)
        assertEquals(1, factory.opened.size)
        assertEquals(ConnectionState.FAILED, t.state.value)
        t.setAppBackgrounded(false)
        assertEquals(2, factory.opened.size)
        assertEquals(ConnectionState.CONNECTING, t.state.value)
        t.close()
    }
}
