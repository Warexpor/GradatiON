package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.CodePairPending
import io.github.stardomains3.oxproxion.code.CodePairing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pair activate queue: a later success must not leave a prior scan error (and a failed
 * rescan must not keep an older pairing), or CodeModeHost toasts the stale failure while
 * also opening the host form. offer/offerError share one lock so interleaved calls cannot
 * leave both pending and error set.
 */
class CodePairPendingTest {

    private val pairing = CodePairing.Result(
        url = "wss://studio.tailnet.ts.net:7878/v1",
        token = "pair-token",
        fingerprint = "",
    )

    @Before
    fun setUp() {
        CodePairPending.clear()
    }

    @After
    fun tearDown() {
        CodePairPending.clear()
    }

    @Test
    fun offer_clearsStaleError() {
        CodePairPending.offerError("camera denied")
        CodePairPending.offer(pairing)
        assertNull(CodePairPending.error.value)
        assertNull(CodePairPending.peekError())
        assertEquals(pairing, CodePairPending.consume())
    }

    @Test
    fun offerError_clearsStalePending() {
        CodePairPending.offer(pairing)
        CodePairPending.offerError("bad QR")
        assertNull(CodePairPending.peek())
        assertEquals("bad QR", CodePairPending.consumeError())
    }

    @Test
    fun clear_dropsPendingAndError() {
        CodePairPending.offer(pairing)
        CodePairPending.clear()
        assertNull(CodePairPending.peek())
        CodePairPending.offerError("scan failed")
        CodePairPending.clear()
        assertNull(CodePairPending.error.value)
        assertNull(CodePairPending.peekError())
        assertNull(CodePairPending.consumeError())
    }

    @Test
    fun consume_isOneShot() {
        CodePairPending.offer(pairing)
        assertEquals(pairing, CodePairPending.consume())
        assertNull(CodePairPending.consume())
    }

    @Test
    fun consumeError_dropsWhenPendingAlreadyWon() {
        CodePairPending.offer(pairing)
        CodePairPending.offerError("bad QR")
        // Success after the failure must win: the error is gone, and consumeError must not
        // resurrect it once offer has stored the pairing.
        CodePairPending.offer(pairing)
        assertNull(CodePairPending.consumeError())
        assertEquals(pairing, CodePairPending.consume())
    }

    @Test
    fun offerAndOfferError_interleavedStayExclusive() {
        val barrier = CyclicBarrier(3)
        val stop = AtomicBoolean(false)
        val done = CountDownLatch(2)
        val fail = AtomicReference<Throwable?>(null)
        val torn = AtomicInteger(0)
        fun run(block: () -> Unit) {
            Thread {
                try {
                    barrier.await(5, TimeUnit.SECONDS)
                    repeat(400) { block() }
                } catch (t: Throwable) {
                    fail.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        run { CodePairPending.offer(pairing) }
        run { CodePairPending.offerError("bad QR") }
        Thread {
            try {
                barrier.await(5, TimeUnit.SECONDS)
                while (!stop.get()) {
                    if (!CodePairPending.isMutuallyExclusive()) torn.incrementAndGet()
                }
            } catch (t: Throwable) {
                fail.compareAndSet(null, t)
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(done.await(15, TimeUnit.SECONDS))
        } finally {
            stop.set(true)
        }
        fail.get()?.let { throw it }
        assertEquals("saw pending and error set together", 0, torn.get())
        assertTrue(CodePairPending.isMutuallyExclusive())
    }
}
