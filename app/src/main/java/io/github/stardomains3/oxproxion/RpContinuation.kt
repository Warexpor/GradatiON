package io.github.stardomains3.oxproxion

/**
 * Roleplay's Continue: the character carries on inside its last reply instead of starting a new
 * bubble. The model is asked (with a hidden user turn) to pick up exactly where the reply ends;
 * [join] then sews what it writes onto what was already there.
 */
object RpContinuation {

    /**
     * Characters that hug the text before them: no space goes in front of these. Straight quotes
     * are left out on purpose: a reply that opens with `"` is starting new dialogue.
     */
    private const val CLOSERS = ",.;:!?)]}%\u2026\u2019\u201D"

    /** Characters that hug the text after them: a reply that stops on one has more to say right after it. */
    private const val OPENERS = "-\u2013\u2014([{/\u2018\u201C"

    /**
     * [base] followed by [addition], with a space between only where the model left none and
     * the text needs one (a finished sentence or a closed action, then a new word).
     */
    fun join(base: String, addition: String): String {
        if (addition.isEmpty()) return base
        if (base.isEmpty()) return addition
        val last = base.last()
        val first = addition.first()
        if (last.isWhitespace() || first.isWhitespace()) return base + addition
        if (first in CLOSERS || last in OPENERS) return base + addition
        return "$base $addition"
    }

    /**
     * The whole reply after a Continue: [base] and [addition] sewn, then run through the reply
     * cleaner. The bubble and the swipe alternate both use this, so they never drift apart.
     */
    fun sew(base: String, addition: String, clean: (String) -> String): String =
        clean(join(base, addition))
}
