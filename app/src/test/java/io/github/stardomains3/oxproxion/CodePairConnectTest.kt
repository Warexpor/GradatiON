package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.CodePairConnect
import io.github.stardomains3.oxproxion.code.CodePairConnect.Outcome
import io.github.stardomains3.oxproxion.code.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class CodePairConnectTest {

    @Test
    fun connected_wins() {
        assertEquals(
            Outcome.CONNECTED,
            CodePairConnect.outcome(ConnectionState.CONNECTED, "anything", timedOut = true),
        )
    }

    @Test
    fun connecting() {
        assertEquals(
            Outcome.CONNECTING,
            CodePairConnect.outcome(ConnectionState.CONNECTING, null),
        )
    }

    @Test
    fun timeout_unreachable() {
        assertEquals(
            Outcome.UNREACHABLE,
            CodePairConnect.outcome(ConnectionState.CONNECTING, null, timedOut = true),
        )
        assertEquals(
            Outcome.UNREACHABLE,
            CodePairConnect.outcome(null, null, timedOut = true),
        )
    }

    @Test
    fun auth_reject_message() {
        assertEquals(
            Outcome.WRONG_TOKEN,
            CodePairConnect.classifyError("The bridge rejected the pairing token"),
        )
        assertEquals(
            Outcome.WRONG_TOKEN,
            CodePairConnect.outcome(ConnectionState.FAILED, "The bridge rejected the pairing token"),
        )
    }

    @Test
    fun transport_token_code_is_wrong_token() {
        // The transport leaves a code, not English, in lastError; it must still read as a bad token.
        assertEquals(
            Outcome.WRONG_TOKEN,
            CodePairConnect.classifyError(io.github.stardomains3.oxproxion.code.CodeErrors.TOKEN_REJECTED),
        )
        assertEquals(
            Outcome.UNREACHABLE,
            CodePairConnect.classifyError(io.github.stardomains3.oxproxion.code.CodeErrors.INVALID_ADDRESS),
        )
    }

    @Test
    fun http_codes_in_error() {
        assertEquals(Outcome.WRONG_TOKEN, CodePairConnect.classifyError("HTTP 401"))
        assertEquals(Outcome.WRONG_TOKEN, CodePairConnect.classifyError("HTTP 403"))
        assertEquals(Outcome.UNREACHABLE, CodePairConnect.classifyError("HTTP 502"))
    }

    @Test
    fun portOrTimeoutContaining401_isUnreachable() {
        assertEquals(
            Outcome.UNREACHABLE,
            CodePairConnect.classifyError("Failed to connect to /10.0.0.1:4010"),
        )
        assertEquals(
            Outcome.UNREACHABLE,
            CodePairConnect.classifyError("Failed to connect to /10.0.0.1:4030"),
        )
        assertEquals(Outcome.UNREACHABLE, CodePairConnect.classifyError("timeout after 4012ms"))
        assertEquals(
            Outcome.WRONG_TOKEN,
            CodePairConnect.outcome(ConnectionState.FAILED, "status 403"),
        )
    }

    @Test
    fun networkish_unreachable() {
        assertEquals(Outcome.UNREACHABLE, CodePairConnect.classifyError("Failed to connect to /10.0.0.1:7878"))
        assertEquals(Outcome.UNREACHABLE, CodePairConnect.classifyError("Software caused connection abort"))
        assertEquals(Outcome.UNREACHABLE, CodePairConnect.classifyError(null))
        assertEquals(Outcome.UNREACHABLE, CodePairConnect.classifyError(""))
        assertEquals(
            Outcome.UNREACHABLE,
            CodePairConnect.outcome(ConnectionState.FAILED, "Connection reset"),
        )
    }

    @Test
    fun token_keywords() {
        assertEquals(Outcome.WRONG_TOKEN, CodePairConnect.classifyError("invalid token"))
        assertEquals(Outcome.WRONG_TOKEN, CodePairConnect.classifyError("Unauthorized"))
    }
}
