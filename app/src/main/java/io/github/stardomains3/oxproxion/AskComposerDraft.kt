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
