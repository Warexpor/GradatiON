package io.github.stardomains3.oxproxion

/**
 * A send tapped while another chat is still opening, or finished opening during
 * the gap before the turn starts. A staged photo is encoded off the main thread
 * first. The field still holds the chat that was on screen when Send was tapped,
 * and the transcript that lands may be a different one by the time the bytes
 * are ready. Queuing that send used to clear the field and append the line to
 * whichever chat finished loading. Stop could not cancel it, because the turn
 * had not started. The line stays until the chat on screen is the one that
 * will receive it.
 */
object ChatSend {
    enum class Outcome { Start, Keep }

    fun decide(transitionActive: Boolean, sameChat: Boolean = true): Outcome =
        if (transitionActive || !sameChat) Outcome.Keep else Outcome.Start
}
