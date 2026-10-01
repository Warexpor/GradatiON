package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.JsonElement

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
    private const val CLOSERS = ",.;:!?)]}%\u2026\u2019\u201D。！？」』"

    /** Characters that hug the text after them: a reply that stops on one has more to say right after it. */
    private const val OPENERS = "-\u2013\u2014([{/\u2018\u201C「『"

    /** Sentence enders, and the markup/quotes that may trail one (`*She smiles.*`, `"Come in."`, 「来て。」). */
    private const val ENDERS = ".!?…。！？"
    private const val TRAILERS = "*_~\"')]’”」』"

    /**
     * [base] followed by [addition], with a separator only where the model left none: a new
     * paragraph after a finished sentence or closed action, a plain space after unfinished
     * text. A sentence in an unspaced script gets no space, and 。！？ count as sentence ends.
     */
    /**
     * Continue replaces the reply with the joined text. A picture already on that reply stays
     * unless the new piece brought one of its own. A data URL is not a file we can show.
     * The JPEG stored in the message stays too, so the next save still has the picture.
     */
    fun keepPicture(
        priorUri: String?,
        updated: FlexibleMessage,
        priorContent: JsonElement? = null,
    ): FlexibleMessage {
        val withUri = when {
            priorUri.isNullOrEmpty() || priorUri.startsWith("data:") -> updated
            !updated.imageUri.isNullOrEmpty() -> updated
            else -> updated.copy(imageUri = priorUri)
        }
        val priorImage = priorContent?.let { MessageContent.imageUrl(it) }
            ?.takeIf { it.startsWith("data:image") }
            ?: return withUri
        if (MessageContent.hasImage(withUri.content)) return withUri
        return withUri.copy(content = ScenePhoto.embed(withUri.content, priorImage))
    }

    /**
     * Another version of the same reply. The words change; a picture already on it stays,
     * including the JPEG stored in the message. Swipe used to replace the whole body with
     * text, so the next save forgot that JPEG and a missing file took the picture with it.
     */
    fun withWords(message: FlexibleMessage, text: String): FlexibleMessage =
        message.copy(
            content = ScenePhoto.replaceTextKeepingPicture(message.content, text),
            reasoning = null,
            thinking = null,
        )

    fun join(base: String, addition: String): String {
        if (addition.isEmpty()) return base
        if (base.isEmpty()) return addition
        val last = base.last()
        val first = addition.first()
        if (last.isWhitespace() || first.isWhitespace()) return base + addition
        if (first in CLOSERS || last in OPENERS) return base + addition
        val end = base.trimEnd { it in TRAILERS }.lastOrNull()
        if (end != null && end in ENDERS) return "$base\n\n$addition"
        // Japanese, Chinese, Korean and the other unspaced scripts do not take a space between words.
        if (unspaced(last) || unspaced(first)) return base + addition
        return "$base $addition"
    }

    private fun unspaced(ch: Char): Boolean {
        val script = Character.UnicodeScript.of(ch.code)
        return script == Character.UnicodeScript.HAN ||
            script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA ||
            script == Character.UnicodeScript.HANGUL ||
            script == Character.UnicodeScript.THAI ||
            script == Character.UnicodeScript.LAO ||
            script == Character.UnicodeScript.KHMER ||
            script == Character.UnicodeScript.MYANMAR
    }
}
