package io.github.stardomains3.oxproxion

/**
 * Speak and Copy on the answer shade read `last_ai_response_channel_2`.
 * Only a shade this turn is actually posting may replace that line.
 * A stream that handed off to tools continues; the follow-up posts the finished
 * answer, and saving the preamble here used to overwrite it. Errors do not post
 * a shade, so they must not replace the line the previous shade will speak.
 */
internal object AnswerShadeText {
    /**
     * Speak and the shade's Copy line stop here. The engine refuses a longer utterance,
     * and [String.take] counts UTF-16 units, so a limit that landed on the first half of
     * an emoji used to leave a broken character for Speak and for Copy.
     */
    const val SPEAK_LIMIT = 3900

    fun lineForShade(text: String, handedToTools: Boolean, isError: Boolean): String? {
        if (handedToTools || isError) return null
        if (text.length <= SPEAK_LIMIT) return text
        return clipForSpeak(text) + "..."
    }

    /** Cut [text] for the engine without ending on a high surrogate. */
    fun clipForSpeak(text: String, limit: Int = SPEAK_LIMIT): String {
        if (text.length <= limit) return text
        var end = limit
        if (end > 0 && text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end)
    }
}
