package io.github.stardomains3.oxproxion

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Time-based reveal pacing for [StreamRevealAnimator]. Keeps a small intentional lag behind
 * the network buffer so sparse tokens do not drain to zero and stall the Choreographer.
 */
object StreamRevealPacing {
    const val EMA_ALPHA = 0.2f
    const val ARRIVAL_DT_MIN_MS = 8f
    const val ARRIVAL_DT_MAX_MS = 500f
    const val LAG_SECONDS = 0.12f
    const val LAG_BACKLOG_MIN = 6f
    const val LAG_BACKLOG_MAX = 48f
    const val MIN_CHARS_PER_SEC = 21f
    const val FRAME_DT_MIN_MS = 8f
    const val FRAME_DT_MAX_MS = 50f
    const val BURST_CATCHUP_SEC = 0.35f
    const val WORD_SNAP_BACKLOG_THRESHOLD = 24
    const val IDLE_STOP_MS = 250L
    const val LAG_HOLD_MS = 150f
    const val LAG_RELEASE_MS = 300f
    const val FINISH_SEC = 0.12f

    class State(
        var arrivalRateCps: Float = MIN_CHARS_PER_SEC,
        var revealCarry: Float = 0f,
        var lastArrivalTimeMs: Long = 0L,
    ) {
        fun reset() {
            arrivalRateCps = MIN_CHARS_PER_SEC
            revealCarry = 0f
            lastArrivalTimeMs = 0L
        }
    }

    fun noteTargetGrowth(state: State, addedChars: Int, nowMs: Long) {
        if (addedChars <= 0) return
        val dtMs = if (state.lastArrivalTimeMs == 0L) {
            200f
        } else {
            (nowMs - state.lastArrivalTimeMs).toFloat().coerceIn(ARRIVAL_DT_MIN_MS, ARRIVAL_DT_MAX_MS)
        }
        val instant = addedChars * 1000f / dtMs
        state.arrivalRateCps = state.arrivalRateCps * (1f - EMA_ALPHA) + instant * EMA_ALPHA
        state.lastArrivalTimeMs = nowMs
    }

    fun desiredLagBacklog(arrivalRateCps: Float): Float =
        (arrivalRateCps * LAG_SECONDS).coerceIn(LAG_BACKLOG_MIN, LAG_BACKLOG_MAX)

    /**
     * How much of the lag to keep, given the time since text last arrived. A model that pauses
     * (a tool call, a slow thought) must not leave its last word hidden, so the lag fades out.
     */
    fun lagHold(sinceArrivalMs: Long): Float =
        (1f - (sinceArrivalMs - LAG_HOLD_MS) / LAG_RELEASE_MS).coerceIn(0f, 1f)

    data class FrameInput(
        val shown: Int,
        val targetLen: Int,
        val dtMs: Float,
        val finishing: Boolean,
        val sinceArrivalMs: Long = 0L,
    )

    data class FrameOutput(
        val charsToReveal: Int,
        val revealCarry: Float,
    )

    fun charsForFrame(state: State, input: FrameInput): FrameOutput {
        val backlog = input.targetLen - input.shown
        if (backlog <= 0) return FrameOutput(0, 0f)

        val dtSec = input.dtMs.coerceIn(FRAME_DT_MIN_MS, FRAME_DT_MAX_MS) / 1000f
        val rate = state.arrivalRateCps.coerceAtLeast(MIN_CHARS_PER_SEC)
        val lag = if (input.finishing) 0f else desiredLagBacklog(rate) * lagHold(input.sinceArrivalMs)
        val excess = backlog - lag
        if (excess <= 0f) return FrameOutput(0, state.revealCarry)

        var revealRate = max(rate, excess / BURST_CATCHUP_SEC)
        if (input.finishing) revealRate = max(revealRate, backlog / FINISH_SEC)

        val raw = revealRate * dtSec + state.revealCarry
        val cap = if (input.finishing) backlog else ceil(excess).toInt().coerceAtMost(backlog)
        val chars = floor(raw).toInt().coerceIn(0, cap)
        return FrameOutput(chars, (raw - chars).coerceIn(0f, 1f))
    }

    /** Past this backlog (or when finishing) a frame may round up to the end of the word. */
    fun snapToWordEnd(text: String, index: Int): Int {
        if (index <= 0 || index >= text.length) return index
        val c = text[index - 1]
        if (c.isWhitespace()) return index
        val nextBreak = text.indexOfAny(WORD_BREAKS, index)
        return if (nextBreak in index until index + 12) nextBreak + 1 else index
    }

    private val WORD_BREAKS = charArrayOf(' ', '\n', '\t', '.', ',', ';', ':', '!', '?')
}
