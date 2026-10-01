package io.github.stardomains3.oxproxion

/**
 * Editing a message you sent cuts that turn out of the transcript and parks it on a fork.
 * Cancel has to put the turn back until a replacement is sent. Regenerating a reply uses
 * the same fork and is not this state: [marked] is set only from the composer edit.
 */
object ChatEdit {

    /**
     * The composer is holding a cut that has not been sent. Variant 2 is the side that
     * shows the cut; the stashed side is the original turn. Once a message occupies the
     * cut, the edit has been sent and the fork navigator takes over.
     */
    fun awaitingSend(
        marked: Boolean,
        sameChat: Boolean,
        roleplay: Boolean,
        hasFork: Boolean,
        variant: Int,
        forkIndex: Int,
        messageCount: Int,
    ): Boolean {
        if (!marked || !sameChat || roleplay) return false
        if (!hasFork || variant != 2 || forkIndex < 0) return false
        return messageCount <= forkIndex
    }

    /**
     * What the field should hold after Cancel. [remembered] is the line that was already
     * there when Edit was tapped, including a blank. Null means that line was not kept,
     * so a field that only repeats the restored bubble is cleared and a line that was
     * changed is left as a draft.
     */
    fun composerAfterCancel(remembered: String?, field: String, restoredUserText: String): String {
        if (remembered != null) return remembered
        return if (field == restoredUserText) "" else field
    }

    /**
     * A photo read for Edit should land in the composer only while that edit is still the one
     * on screen. Ask drops it once Cancel, a send, or another chat has closed the edit mark.
     * Roleplay never sets that mark (there is no Cancel), so the photo follows the cut for as
     * long as this is still the same chat. Checking the mark there used to throw the picture away.
     */
    fun keepEditPhoto(roleplay: Boolean, askEditStillOpen: Boolean, sameChat: Boolean): Boolean =
        if (roleplay) sameChat else askEditStillOpen
}
