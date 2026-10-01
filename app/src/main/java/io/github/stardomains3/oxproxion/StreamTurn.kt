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

    /**
     * The pump a finished turn should leave behind. Stop, Edit, and Delete treat a
     * non-null pump as a reply that is still arriving. The finished pump used to stay
     * referenced, so the next cut ran that cleanup: it cancelled a model-list fetch
     * and cleared a note the next turn still needed. A newer pump, already started,
     * is left alone.
     */
    fun <T : Any> retainPump(active: T?, finished: T?): T? =
        if (finished != null && active === finished) null else active
}
