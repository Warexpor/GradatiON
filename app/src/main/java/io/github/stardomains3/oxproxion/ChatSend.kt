package io.github.stardomains3.oxproxion

/**
 * A send tapped while another chat is still opening. The field still holds the
 * chat on screen, and the transcript that lands is the one being opened.
 * Queuing that send used to clear the field, close an open edit, and then append
 * the line to whichever chat finished loading. Stop could not cancel it, because
 * the turn had not started. The line stays until the chat on screen is the one
 * that will receive it.
 */
object ChatSend {
    enum class Outcome { Start, Keep }

    fun decide(transitionActive: Boolean): Outcome =
        if (transitionActive) Outcome.Keep else Outcome.Start
}
