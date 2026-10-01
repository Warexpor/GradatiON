package io.github.stardomains3.oxproxion

/**
 * Pure decisions for chat persistence races (sessionEpoch / delete-while-save).
 * Kept free of Android so unit tests can lock the contract.
 *
 * [decide] is whether the save may attach to the chat on screen. A snapshot taken
 * before a switch still has to be written; that choice is [persist].
 */
object ChatSaveGate {

    enum class Outcome {
        /** Overwrite the existing open session row. */
        ProceedExisting,
        /** Allocate a new session id (first save). */
        ProceedAllocateNew,
        /** Drop the scheduled save (stale epoch, deleted row, or session switched). */
        Abort
    }

    /**
     * Whether the captured transcript is written. Epoch and the live session are ignored:
     * leaving the chat must not throw the snapshot away. A newer snapshot of the same chat
     * ([ticketCurrent] false) is the one that writes. A deleted row is not recreated.
     */
    fun persist(
        ticketCurrent: Boolean,
        saveAsNew: Boolean,
        existingId: Long?,
        rowExists: Boolean
    ): Outcome {
        if (!ticketCurrent) return Outcome.Abort
        if (saveAsNew || existingId == null) return Outcome.ProceedAllocateNew
        if (!rowExists) return Outcome.Abort
        return Outcome.ProceedExisting
    }

    fun decide(
        epochAtSchedule: Long,
        currentEpoch: Long,
        openSessionId: Long?,
        liveSessionId: Long?,
        rowExists: Boolean,
        saveAsNew: Boolean
    ): Outcome {
        if (epochAtSchedule != currentEpoch) return Outcome.Abort
        if (openSessionId != null && liveSessionId != null && liveSessionId != openSessionId) {
            return Outcome.Abort
        }
        if (!saveAsNew && openSessionId != null && liveSessionId == null) {
            return Outcome.Abort
        }
        return when {
            !saveAsNew && openSessionId != null && rowExists -> Outcome.ProceedExisting
            !saveAsNew && openSessionId != null && !rowExists -> Outcome.Abort
            else -> Outcome.ProceedAllocateNew
        }
    }

    enum class AutoSaveKind {
        /** Never-saved empty / assistant-less chat — nothing to write. */
        Skip,
        /** Existing session — overwrite with current transcript (even if emptied). */
        ReuseExisting,
        /** First save — needs an assistant message to create a history row. */
        FirstSaveNeedsAssistant
    }

    fun autoSaveKind(
        sessionId: Long?,
        hasAssistant: Boolean,
        messagesEmpty: Boolean
    ): AutoSaveKind = when {
        sessionId != null -> AutoSaveKind.ReuseExisting
        messagesEmpty || !hasAssistant -> AutoSaveKind.Skip
        else -> AutoSaveKind.FirstSaveNeedsAssistant
    }
}
