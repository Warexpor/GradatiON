package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.CodePairPending
import io.github.stardomains3.oxproxion.code.CodePairing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Pair activate queue: a later success must not leave a prior scan error (and a failed
 * rescan must not keep an older pairing), or CodeModeHost toasts the stale failure while
 * also opening the host form.
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
        assertNull(CodePairPending.consumeError())
    }

    @Test
    fun consume_isOneShot() {
        CodePairPending.offer(pairing)
        assertEquals(pairing, CodePairPending.consume())
        assertNull(CodePairPending.consume())
    }
}
