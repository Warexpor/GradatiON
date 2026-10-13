package io.github.stardomains3.oxproxion

/**
 * A chat's branches: one cut, any number of tails after it. Each Redo or Edit adds a branch
 * instead of replacing the one before. The tail on screen lives in the transcript, so its slot
 * here is empty.
 */
internal object ForkBranches {

    data class State(val index: Int, val branches: List<List<FlexibleMessage>>, val active: Int)

    /**
     * Cut [transcript] at [at]. With no [prior] cut this is the first branch. At the same cut the
     * tail joins the others. Deeper than the cut, the whole tail from the cut is kept as the
     * branch, so the new one shares the turns before [at]. Shallower, the cut moves up and every
     * old branch gets the turns between the two cuts in front of it.
     */
    fun cut(transcript: List<FlexibleMessage>, at: Int, prior: State?): State {
        val tail = transcript.drop(at)
        if (prior == null || prior.index < 0 || prior.index > transcript.size ||
            prior.active !in prior.branches.indices
        ) {
            return State(at, listOf(tail, emptyList()), 1)
        }
        val slots = prior.branches.toMutableList()
        return when {
            at == prior.index -> {
                slots[prior.active] = tail
                slots += listOf(emptyList())
                State(at, slots, slots.lastIndex)
            }
            at > prior.index -> {
                slots[prior.active] = transcript.drop(prior.index)
                slots += listOf(emptyList())
                State(prior.index, slots, slots.lastIndex)
            }
            else -> {
                val between = transcript.subList(at, prior.index)
                val moved = slots.mapIndexed { i, t -> if (i == prior.active) tail else between + t }
                State(at, moved + listOf(emptyList()), moved.size)
            }
        }
    }
}
