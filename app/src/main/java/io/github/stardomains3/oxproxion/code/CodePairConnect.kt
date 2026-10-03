package io.github.stardomains3.oxproxion.code

/**
 * Maps a post-save pairing test ([ConnectionState] + transport [lastError]) to a small UX outcome.
 * Pure / unit-testable — no Android deps.
 */
object CodePairConnect {

    enum class Outcome {
        /** Still waiting on the socket. */
        CONNECTING,
        /** Bridge accepted the token. */
        CONNECTED,
        /** HTTP 401/403 or auth-flavoured error text. */
        WRONG_TOKEN,
        /** Network / timeout / anything else — hint Tailscale or tunnel. */
        UNREACHABLE,
    }

    /**
     * @param timedOut true when the observe window expired without CONNECTED/FAILED
     */
    fun outcome(
        state: ConnectionState?,
        lastError: String?,
        timedOut: Boolean = false,
    ): Outcome {
        if (state == ConnectionState.CONNECTED) return Outcome.CONNECTED
        if (timedOut) return Outcome.UNREACHABLE
        return when (state) {
            ConnectionState.CONNECTING -> Outcome.CONNECTING
            ConnectionState.FAILED, ConnectionState.DISCONNECTED, null -> classifyError(lastError)
        }
    }

    /** Classify a failure [lastError] from [WebSocketTransport] (or similar). */
    fun classifyError(lastError: String?): Outcome {
        val e = lastError?.lowercase().orEmpty()
        if (e.isEmpty()) return Outcome.UNREACHABLE
        // Exact transport message for 401/403. A bare substring also matches port 4010
        // and "4012 ms", which are unreachable, not a rejected token.
        if (e.contains("rejected the pairing token")) return Outcome.WRONG_TOKEN
        if (HTTP_STATUS.containsMatchIn(e)) return Outcome.WRONG_TOKEN
        if (e.contains("unauthor") || e.contains("forbidden")) return Outcome.WRONG_TOKEN
        if (e.contains("token") && (e.contains("reject") || e.contains("invalid") || e.contains("wrong"))) {
            return Outcome.WRONG_TOKEN
        }
        return Outcome.UNREACHABLE
    }

    /** HTTP status as its own number, so 4010 / 4012 do not count as 401. */
    private val HTTP_STATUS = Regex("""(?<!\d)(?:401|403)(?!\d)""")
}
