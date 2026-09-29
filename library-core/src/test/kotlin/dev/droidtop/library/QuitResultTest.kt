package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state rule behind the Quick Menu's "Quit to Library"
 * (docs/SPEC.md, Droidtop/tracker#82): droidtop's own running-game
 * bookkeeping clears ONLY when the game really ended, and the row's
 * subtitle is [QuitResult.message] -- never a claim that didn't happen.
 * [QuitResult] carries no Android framework, so the rule is unit-tested
 * here rather than on a device.
 */
class QuitResultTest {

    @Test
    fun `Ended is the only outcome that lets the shell clear its running-game state`() {
        assertTrue(QuitResult.Ended is QuitResult.Ended)
        // The other two outcomes must NOT be Ended: a quit that left the
        // emulator alive (the Android 13 case) or that couldn't even try
        // must not be reported as a success.
        val notEnded = QuitResult.NotEnded("Couldn't end the emulator's task")
        val unresolvable = QuitResult.Unresolvable("No emulator is installed")
        assertFalse(notEnded is QuitResult.Ended)
        assertFalse(unresolvable is QuitResult.Ended)
    }

    @Test
    fun `message is the promise before a quit runs and the outcome after`() {
        // Before a quit has run, the row shows the pre-quit promise; the
        // caller passes null and the tile renders "Ends <game>". After a
        // quit runs, message is the outcome's own words.
        assertEquals("Ended", QuitResult.Ended.message)
        assertEquals(
            "Couldn't end the emulator's task; it may have been started outside droidtop",
            QuitResult.NotEnded("Couldn't end the emulator's task; it may have been started outside droidtop").message,
        )
        assertEquals(
            "No emulator is installed for that system",
            QuitResult.Unresolvable("No emulator is installed for that system").message,
        )
    }

    @Test
    fun `NotEnded and Unresolvable are distinct outcomes`() {
        // A provider that couldn't even try (uninstalled game) is not the
        // same as one that tried and failed: the subtitle says which.
        val notEnded = QuitResult.NotEnded("still running")
        val unresolvable = QuitResult.Unresolvable("game was uninstalled")
        assertFalse(notEnded == unresolvable)
        assertTrue(notEnded is QuitResult.NotEnded)
        assertTrue(unresolvable is QuitResult.Unresolvable)
    }
}