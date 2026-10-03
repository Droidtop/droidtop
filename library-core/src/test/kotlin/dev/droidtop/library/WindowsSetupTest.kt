package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The states Settings' row, the game page and launch all read ([WindowsSetup], Droidtop/tracker#299). */
class WindowsSetupTest {

    @Test
    fun anInstallUnderWayWinsOverEverythingElse() {
        val installing = WindowsSetup.State.Installing(9, "Installing Windows system files... 9%")
        assertEquals(installing, WindowsSetup.resolve(provisioned = true, installing = installing, failure = "old"))
    }

    @Test
    fun aFailureIsKeptUntilTheNextAttempt() {
        assertEquals(WindowsSetup.State.Failed("no network"), WindowsSetup.resolve(false, null, "no network"))
    }

    @Test
    fun withNothingToReportItIsReadyOrNotSetUp() {
        assertEquals(WindowsSetup.State.Ready, WindowsSetup.resolve(true, null, null))
        assertEquals(WindowsSetup.State.NotSetUp, WindowsSetup.resolve(false, null, null))
    }

    @Test
    fun labelsAreShortValues() {
        assertEquals("Not set up", WindowsSetup.label(WindowsSetup.State.NotSetUp))
        assertEquals("Installing 9%", WindowsSetup.label(WindowsSetup.State.Installing(9, "x")))
        assertEquals("Installing", WindowsSetup.label(WindowsSetup.State.Installing(null, "x")))
        assertEquals("Ready", WindowsSetup.label(WindowsSetup.State.Ready))
        assertEquals("Failed: no network", WindowsSetup.label(WindowsSetup.State.Failed("no network")))
    }

    @Test
    fun percentIsReadFromAProgressLine() {
        assertEquals(9, WindowsSetup.percentIn("Installing Windows system files... 9%"))
        assertEquals(100, WindowsSetup.percentIn("Done 100 %"))
        assertNull(WindowsSetup.percentIn("Downloading Wine"))
        assertNull(WindowsSetup.percentIn("Copied 250%"))
    }
}
