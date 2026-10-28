package dev.droidtop.app

import dev.droidtop.app.DesktopSetupPrefs.LeaveAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** Leaving Desktop: stop, keep, or ask once (Droidtop/tracker#389, docs/SPEC.md 3 "Stopping is a stop"). */
class DesktopLeaveActionTest {

    @Test
    fun `a desktop that is not live stops whatever was chosen`() {
        for (keep in listOf(false, true)) for (answered in listOf(false, true)) {
            assertEquals(LeaveAction.STOP, DesktopSetupPrefs.leaveAction(sessionLive = false, keepRunning = keep, answered = answered))
        }
    }

    @Test
    fun `a live desktop the person chose to keep keeps running`() {
        assertEquals(LeaveAction.KEEP, DesktopSetupPrefs.leaveAction(sessionLive = true, keepRunning = true, answered = true))
    }

    @Test
    fun `a live desktop the person chose to stop stops without asking`() {
        assertEquals(LeaveAction.STOP, DesktopSetupPrefs.leaveAction(sessionLive = true, keepRunning = false, answered = true))
    }

    @Test
    fun `a live desktop nobody chose for is offered`() {
        assertEquals(LeaveAction.OFFER, DesktopSetupPrefs.leaveAction(sessionLive = true, keepRunning = false, answered = false))
    }
}
