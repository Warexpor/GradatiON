package io.github.stardomains3.oxproxion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hub Continue closes the character list, then setChatMode(RP) lands later.
 * That landing must not reopen the list. A suppress left set while already in
 * Roleplay would skip the list on the next Ask → Roleplay switch.
 * Roleplay turned off during a reply flips back to Chat only once the reply is idle.
 */
class HubModeTest {

    @Test
    fun continueFromAsk_doesNotReopenHomeWhenModeLands() {
        val closed = HubUncover.afterUncover(
            alreadyInRoleplay = false,
            home = HubUncover.Home(open = false, suppressed = false, resumeAtHome = true),
        )
        val landed = HubUncover.afterEnterRoleplay(closed)
        assertFalse(landed.open)
        assertFalse(landed.suppressed)
    }

    @Test
    fun continueWhileAlreadyRoleplay_doesNotStickSuppress() {
        val closed = HubUncover.afterUncover(
            alreadyInRoleplay = true,
            home = HubUncover.Home(open = true, suppressed = false, resumeAtHome = true),
        )
        assertFalse(closed.open)
        assertFalse(closed.suppressed)
    }

    @Test
    fun ordinaryEnter_stillResumesHome() {
        val landed = HubUncover.afterEnterRoleplay(
            HubUncover.Home(open = false, suppressed = false, resumeAtHome = true),
        )
        assertTrue(landed.open)
        assertFalse(landed.suppressed)
    }

    @Test
    fun threadOpenWhileRoleplay_doesNotStickSuppress() {
        assertFalse(HubUncover.suppressForThreadOpen(alreadyInRoleplay = true))
        assertTrue(HubUncover.suppressForThreadOpen(alreadyInRoleplay = false))
    }

    @Test
    fun threadOpenFromAsk_suppressesUntilModeLands() {
        val home = HubUncover.Home(
            open = true,
            suppressed = HubUncover.suppressForThreadOpen(alreadyInRoleplay = false),
            resumeAtHome = true,
        ).copy(open = false)
        val landed = HubUncover.afterEnterRoleplay(home)
        assertFalse(landed.open)
        assertFalse(landed.suppressed)
    }

    @Test
    fun askHistoryWhileOnRoleplayList_remembersTheList() {
        val onList = HubUncover.Home(open = true, suppressed = false, resumeAtHome = false)
        // An Ask row must not signal, so the list is still open when Roleplay is left.
        assertFalse(HubUncover.shouldSignalThreadOpen(loadApplied = true, loadedRoleplay = false))
        val left = HubUncover.afterLeaveRoleplay(wasInRoleplay = true, home = onList)
        assertTrue(left.resumeAtHome)
        assertFalse(left.open)
        assertFalse(left.suppressed)
        val back = HubUncover.afterEnterRoleplay(left)
        assertTrue(back.open)
        assertFalse(back.suppressed)
    }

    @Test
    fun closingListBeforeLeave_forgetsTheList() {
        // What the old thread-open signal did: close the list, then snapshot.
        val onList = HubUncover.Home(open = true, suppressed = false, resumeAtHome = false)
        val closedFirst = onList.copy(open = false)
        val left = HubUncover.afterLeaveRoleplay(wasInRoleplay = true, home = closedFirst)
        assertFalse(left.resumeAtHome)
        assertFalse(HubUncover.afterEnterRoleplay(left).open)
    }

    @Test
    fun roleplayHistory_signalsSoLandingDoesNotReopen() {
        assertTrue(HubUncover.shouldSignalThreadOpen(loadApplied = true, loadedRoleplay = true))
        val home = HubUncover.Home(
            open = false,
            suppressed = HubUncover.suppressForThreadOpen(alreadyInRoleplay = false),
            resumeAtHome = true,
        )
        val landed = HubUncover.afterEnterRoleplay(home)
        assertFalse(landed.open)
        assertFalse(landed.suppressed)
    }

    @Test
    fun abortedHistoryLoad_doesNotSuppress() {
        assertFalse(HubUncover.shouldSignalThreadOpen(loadApplied = false, loadedRoleplay = true))
        assertFalse(HubUncover.shouldSignalThreadOpen(loadApplied = false, loadedRoleplay = false))
    }

    @Test
    fun laterAskReemit_doesNotWipeResumeMemory() {
        val onList = HubUncover.Home(open = true, suppressed = false, resumeAtHome = false)
        val left = HubUncover.afterLeaveRoleplay(wasInRoleplay = true, home = onList)
        val again = HubUncover.afterLeaveRoleplay(wasInRoleplay = false, home = left)
        assertTrue(again.resumeAtHome)
        assertFalse(again.suppressed)
    }

    @Test
    fun leaveDisabledRoleplay_onlyWhenIdle() {
        assertTrue(ModeGates.leaveDisabledRoleplay(roleplayEnabled = false, inRoleplay = true, awaitingReply = false))
        assertFalse(ModeGates.leaveDisabledRoleplay(roleplayEnabled = false, inRoleplay = true, awaitingReply = true))
        assertFalse(ModeGates.leaveDisabledRoleplay(roleplayEnabled = true, inRoleplay = true, awaitingReply = false))
        assertFalse(ModeGates.leaveDisabledRoleplay(roleplayEnabled = false, inRoleplay = false, awaitingReply = false))
    }
}
