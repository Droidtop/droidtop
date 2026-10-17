package dev.droidtop.runtime

import dev.droidtop.runtime.HomeDisplays.Target
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home brings back droidtop's own screen for the display it was pressed on (console, 2026-10-17). */
class HomeDisplaysTest {
    @Test
    fun `on the console the companion is on the built-in screen and Gaming on the monitor`() {
        // Display 0 shows the companion, display 6 (DP) the Gaming shell.
        assertEquals(Target.COMPANION, HomeDisplays.targetFor(pressedOn = 0, companionDisplayId = 0, shellDisplayId = 6))
        assertEquals(Target.MAIN, HomeDisplays.targetFor(pressedOn = 6, companionDisplayId = 0, shellDisplayId = 6))
    }

    @Test
    fun `in the usual arrangement the shell is on the built-in screen and the companion on the second`() {
        assertEquals(Target.MAIN, HomeDisplays.targetFor(pressedOn = 0, companionDisplayId = 2, shellDisplayId = 0))
        assertEquals(Target.COMPANION, HomeDisplays.targetFor(pressedOn = 2, companionDisplayId = 2, shellDisplayId = 0))
    }

    @Test
    fun `a screen that is neither shows droidtop's surface, and an unknown arrangement brings the main screen`() {
        assertEquals(Target.SURFACE, HomeDisplays.targetFor(pressedOn = 3, companionDisplayId = 2, shellDisplayId = 0))
        assertEquals(Target.MAIN, HomeDisplays.targetFor(pressedOn = 0, companionDisplayId = null, shellDisplayId = null))
        // The shell moved onto the companion's old screen: there, Home is the shell's.
        assertEquals(Target.MAIN, HomeDisplays.targetFor(pressedOn = 2, companionDisplayId = 2, shellDisplayId = 2))
    }
}
