package io.github.stardomains3.oxproxion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesces SSE deltas into at most one main-thread transcript update per frame.
 *
 * The network reader [offer]s every accumulated message without switching threads; a
 * single main-thread consumer applies only the latest one, then yields a frame. This keeps
 * the reader from stalling on the UI thread once per token and stops the transcript list
 * from being copied and diffed hundreds of times a second on fast local models.
 *
 * Call [finish] once the stream ends normally (applies whatever is still pending), or
 * [cancel] from the main thread before writing an error/final message so a stale partial
 * can never land on top of it.
 */
internal class StreamUiPump(
    scope: CoroutineScope,
    private val apply: (FlexibleMessage) -> Unit
) {
    private val channel = Channel<FlexibleMessage>(Channel.CONFLATED)
    private val job: Job = scope.launch(Dispatchers.Main.immediate) {
        for (message in channel) {
            apply(message)
            delay(FRAME_MS)
        }
    }

    fun offer(message: FlexibleMessage) {
        channel.trySend(message)
    }

    /** Runs the stream [block]; flushes on normal completion and always stops the pump. */
    suspend fun <T> drive(block: suspend () -> T): T = try {
        block().also { finish() }
    } finally {
        cancel()
    }

    /** Flush the last pending update and stop. Safe to call more than once. */
    suspend fun finish() {
        channel.close()
        job.join()
    }

    /** Drop anything pending and stop. Safe to call more than once. */
    fun cancel() {
        channel.cancel()
        job.cancel()
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
