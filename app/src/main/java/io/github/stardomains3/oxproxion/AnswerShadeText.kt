package io.github.stardomains3.oxproxion

/**
 * Speak and Copy on the answer shade read `last_ai_response_channel_2`.
 * Only a shade this turn is actually posting may replace that line.
 * A stream that handed off to tools continues; the follow-up posts the finished
 * answer, and saving the preamble here used to overwrite it. Errors do not post
 * a shade, so they must not replace the line the previous shade will speak.
 */
internal object AnswerShadeText {
    fun lineForShade(text: String, handedToTools: Boolean, isError: Boolean): String? {
        if (handedToTools || isError) return null
        return text
    }
}
