package io.github.stardomains3.oxproxion

/**
 * A chat's branches: one cut, any number of tails after it. Each Redo or Edit adds a branch
 * instead of replacing the one before. The tail on screen lives in the transcript, so its slot
 * here is empty.
 *
 * Roleplay also keeps each branch's reply versions ([versions], by transcript position, the
 * newest reply's swipes folded in). The slot on screen has none here: those are the live ones.
 */
internal object ForkBranches {

    data class State(
        val index: Int,
        val branches: List<List<FlexibleMessage>>,
        val active: Int,
        val versions: List<Map<Int, RpVersions>> = List(branches.size) { emptyMap() },
    )

    /**
     * Cut [transcript] at [at]. With no [prior] cut this is the first branch. At the same cut the
     * tail joins the others. Deeper than the cut, the whole tail from the cut is kept as the
     * branch, so the new one shares the turns before [at]. Shallower, the cut moves up and every
     * old branch gets the turns between the two cuts in front of it. [live] is the versions of
     * the transcript on screen; the branch that parks it takes the ones inside it.
     */
    fun cut(
        transcript: List<FlexibleMessage>,
        at: Int,
        prior: State?,
        live: Map<Int, RpVersions> = emptyMap(),
    ): State {
        val tail = transcript.drop(at)
        if (prior == null || prior.index < 0 || prior.index > transcript.size ||
            prior.active !in prior.branches.indices
        ) {
            return State(at, listOf(tail, emptyList()), 1, listOf(live.filterKeys { it >= at }, emptyMap()))
        }
        val slots = prior.branches.toMutableList()
        val versions = prior.versions.takeIf { it.size == slots.size }?.toMutableList()
            ?: MutableList(slots.size) { emptyMap() }
        return when {
            at == prior.index -> {
                slots[prior.active] = tail
                versions[prior.active] = live.filterKeys { it >= at }
                State(at, slots + listOf(emptyList()), slots.size, versions + listOf(emptyMap()))
            }
            at > prior.index -> {
                slots[prior.active] = transcript.drop(prior.index)
                versions[prior.active] = live.filterKeys { it >= prior.index }
                State(prior.index, slots + listOf(emptyList()), slots.size, versions + listOf(emptyMap()))
            }
            else -> {
                val between = transcript.subList(at, prior.index)
                val shared = live.filterKeys { it >= at && it < prior.index }
                val moved = slots.mapIndexed { i, t -> if (i == prior.active) tail else between + t }
                val movedVersions = versions.mapIndexed { i, v ->
                    if (i == prior.active) live.filterKeys { it >= at } else shared + v
                }
                State(at, moved + listOf(emptyList()), moved.size, movedVersions + listOf(emptyMap()))
            }
        }
    }
}
