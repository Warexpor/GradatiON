package io.github.stardomains3.oxproxion

/**
 * A reply that is still arriving, and a tap that stops it, do not own the transcript
 * for the rest of the frame.
 *
 * Stop's cleanup runs on a later pass of the main thread. By then Send may already
 * have placed the next reply's placeholder in the same slot, or Edit may have cut
 * the tail off. That cleanup may only touch the list when the stopped job is still
 * the one the screen is waiting on.
 */
object StreamTurn {

    fun applyCancelCleanup(active: Any?, cancelled: Any?): Boolean =
        cancelled != null && active === cancelled
}
