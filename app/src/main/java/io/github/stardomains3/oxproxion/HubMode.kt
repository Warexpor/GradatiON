package io.github.stardomains3.oxproxion

/**
 * Hub Continue / Start chat and the Roleplay mode gate.
 *
 * Continue closes the character list on the way back to the thread, then
 * [ChatViewModel.setChatMode] lands asynchronously. Without [Home.suppressed],
 * that landing reopens the list whenever Roleplay was last left there.
 * Already in Roleplay, the mode observer does not run, so a suppress left set
 * would skip the list on the next Ask → Roleplay switch.
 */
internal object HubUncover {

    data class Home(
        val open: Boolean,
        val suppressed: Boolean,
        val resumeAtHome: Boolean,
    )

    fun afterUncover(alreadyInRoleplay: Boolean, home: Home): Home = home.copy(
        open = false,
        suppressed = if (alreadyInRoleplay) home.suppressed else true,
    )

    /** Entering Roleplay from another mode. A hub suppress wins over resume-at-home. */
    fun afterEnterRoleplay(home: Home): Home = home.copy(
        open = !home.suppressed && home.resumeAtHome,
        suppressed = false,
    )

    /**
     * Start chat posts the thread-opened event before the mode coroutine.
     * Suppress only when Roleplay is not current yet. Already in Roleplay, the
     * list close is enough — leaving suppress set would stick.
     */
    fun suppressForThreadOpen(alreadyInRoleplay: Boolean): Boolean = !alreadyInRoleplay
}

/**
 * Settings can turn Roleplay off while a reply is still streaming. The tab hides
 * immediately, but the flip back to Chat has to wait until the reply is idle or
 * [ChatViewModel.toggleChatMode] would cut it off. The idle check has to run
 * again when the reply finishes — the prefs listener does not fire twice.
 */
internal object ModeGates {
    fun leaveDisabledRoleplay(
        roleplayEnabled: Boolean,
        inRoleplay: Boolean,
        awaitingReply: Boolean,
    ): Boolean = !roleplayEnabled && inRoleplay && !awaitingReply
}
