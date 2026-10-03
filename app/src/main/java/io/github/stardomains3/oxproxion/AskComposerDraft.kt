package io.github.stardomains3.oxproxion

/**
 * When the open Chat thread changes, the composer swaps to that thread's unsent text.
 * Roleplay keeps the mode draft it already had. The first bind does not count as a
 * change: the field may already hold the restored text.
 */
object AskComposerDraft {

    data class State(
        val bound: Boolean = false,
        val mode: ChatMode? = null,
        val sessionId: Long? = null,
    )

    sealed class Effect {
        data object None : Effect()
        data class Bind(val sessionId: Long?) : Effect()
        /** The unsaved chat received an id. Keep the field; move its draft onto that id. */
        data class Promote(val sessionId: Long?) : Effect()
        data class Switch(val from: Long?, val to: Long?) : Effect()
    }

    /**
     * The field should take this thread's stored draft. [userEdited] is typing since
     * the view was restored. An empty field, or one that still holds the single
     * Ask snapshot from the last mode switch, is not that typing: the snapshot is
     * one string for every chat, so it can be a line from a different thread.
     * Text the view itself restored (a rotation, a share) is left alone.
     */
    fun takeStoredDraft(field: String, userEdited: Boolean, modeSnapshot: String): Boolean {
        if (userEdited) return false
        return field.isEmpty() || field == modeSnapshot
    }

    /**
     * True when leaving Ask should write [text] into the per-thread store. An empty
     * field the user never edited is not a request to forget a stored draft.
     */
    fun shouldParkText(dirty: Boolean, text: String): Boolean =
        dirty || text.isNotEmpty()

    /**
     * A blank field the user just edited is a clear: first save must drop the parked
     * caption, not copy it onto the new id. A blank field they did not edit (Code, or a
     * rebuild) still falls back to that caption.
     */
    fun blankLiveDropsParkedCaption(live: String, dirty: Boolean): Boolean =
        dirty && live.isBlank()

    /**
     * Ask's mode snapshot after promote. Blank live must not replace the caption
     * [ComposerDrafts.promote] just moved; a later mode re-emit would paint "".
     */
    fun snapshotAfterPromote(live: String, recovered: String): String =
        if (live.isBlank()) recovered else live

    /**
     * Put [recovered] on the composer. Code is covering the field until leave; a dirty
     * field is an edit (including a clear). Only a blank, unedited Chat field takes it.
     * Text the view already restored is left alone.
     */
    fun revealPromotedCaption(
        live: String,
        dirty: Boolean,
        recovered: String,
        codeCovering: Boolean,
    ): Boolean {
        if (codeCovering || dirty) return false
        if (recovered.isBlank()) return false
        return live.isBlank()
    }

    /**
     * A relaunch drops Ask's mode snapshot. That string is one line for every chat, and the
     * per-thread draft is what comes back. Roleplay's unsent line lives only in its mode draft,
     * so a relaunch keeps it.
     */
    fun dropOnRelaunch(mode: ChatMode): Boolean = mode == ChatMode.ASK

    fun bind(state: State, mode: ChatMode, sessionId: Long?): Pair<State, Effect> {
        if (state.bound) return state to Effect.None
        return State(bound = true, mode = mode, sessionId = sessionId) to Effect.Bind(sessionId)
    }

    fun change(state: State, mode: ChatMode, sessionId: Long?, promoted: Boolean): Pair<State, Effect> {
        if (!state.bound) return state to Effect.None
        val next = state.copy(mode = mode, sessionId = sessionId)
        if (state.mode != ChatMode.ASK || mode != ChatMode.ASK) return next to Effect.None
        if (promoted) return next to Effect.Promote(sessionId)
        if (state.sessionId == sessionId) return state to Effect.None
        return next to Effect.Switch(state.sessionId, sessionId)
    }
}
