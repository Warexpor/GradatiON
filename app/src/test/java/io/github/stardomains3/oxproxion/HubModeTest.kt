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
    fun leaveDisabledRoleplay_onlyWhenIdle() {
        assertTrue(ModeGates.leaveDisabledRoleplay(roleplayEnabled = false, inRoleplay = true, awaitingReply = false))
        assertFalse(ModeGates.leaveDisabledRoleplay(roleplayEnabled = false, inRoleplay = true, awaitingReply = true))
        assertFalse(ModeGates.leaveDisabledRoleplay(roleplayEnabled = true, inRoleplay = true, awaitingReply = false))
        assertFalse(ModeGates.leaveDisabledRoleplay(roleplayEnabled = false, inRoleplay = false, awaitingReply = false))
    }
}
