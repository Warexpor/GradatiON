package io.github.stardomains3.oxproxion

import java.util.concurrent.atomic.AtomicLong

/**
 * Orders chat saves so an older snapshot cannot replace a newer one, and so two first
 * saves of different chats (the epoch changed) both land instead of one cancelling the other.
 */
internal class ChatSaveSerial {
    data class Ticket(val key: String, val generation: Long)

    private val next = AtomicLong(0)
    private val latest = HashMap<String, Long>()
    private val minted = HashMap<String, Long>()

    @Synchronized
    fun claim(sessionId: Long?, epoch: Long, saveAsNew: Boolean = false): Ticket {
        val generation = next.incrementAndGet()
        val key = when {
            // A copy must not cancel a save of the chat it was copied from.
            saveAsNew -> "copy:$generation"
            sessionId != null -> "id:$sessionId"
            else -> "new:$epoch"
        }
        latest[key] = generation
        return Ticket(key, generation)
    }

    @Synchronized
    fun isCurrent(ticket: Ticket): Boolean = latest[ticket.key] == ticket.generation

    /** The id an earlier save of this same chat inserted, so the next one overwrites it. */
    @Synchronized
    fun mintedId(ticket: Ticket): Long? = minted[ticket.key]

    @Synchronized
    fun noteMinted(ticket: Ticket, sessionId: Long) {
        if (sessionId <= 0L) return
        // The inserter may already be stale: a newer snapshot is waiting to overwrite this row.
        // Recording the id is what stops that snapshot from inserting a second copy.
        // A stale ticket must not replace an id a newer one already published.
        if (!isCurrent(ticket) && minted.containsKey(ticket.key)) return
        minted[ticket.key] = sessionId
    }
}
