package dev.droidtop.shell.gamepad

import org.junit.Assert.assertEquals
import org.junit.Test

/** The confirmation's safe choice never reads the same as its action (docs/SPEC.md, "The confirmation, every use"). */
class ConfirmChoiceTest {
    @Test
    fun aNamedChoiceIsKept() {
        assertEquals("Keep downloading", safeChoice("Keep downloading", "Cancel download"))
    }

    @Test
    fun noNameIsCancel() {
        assertEquals("Cancel", safeChoice(null, "Quit"))
        assertEquals("Cancel", safeChoice("  ", "Quit"))
    }

    @Test
    fun aChoiceThatMatchesTheActionIsKeepIt() {
        assertEquals("Keep it", safeChoice(null, "Cancel"))
        assertEquals("Keep it", safeChoice("cancel job", "Cancel job "))
    }
}
