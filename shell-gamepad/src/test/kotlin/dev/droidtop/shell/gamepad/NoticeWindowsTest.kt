package dev.droidtop.shell.gamepad

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The notice hosts add their Popup or Dialog window only while the shell is started (Droidtop/tracker#410): a window
 * added for an activity that is stopping or gone throws BadTokenException.
 */
class NoticeWindowsTest {
    @Test
    fun aWindowIsAddedOnlyWhileTheShellIsStarted() {
        val allowed = Lifecycle.State.entries.filter(::canAddWindow)
        assertEquals(listOf(Lifecycle.State.STARTED, Lifecycle.State.RESUMED), allowed)
    }
}
