package dev.droidtop.library

import dev.droidtop.library.settings.GameAwakeMode.NEVER_SLEEP
import dev.droidtop.library.settings.GameAwakeMode.SYSTEM_TIMER
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule for holding the screen on while a game runs (docs/SPEC.md 7f, "Sleep and return to game"). */
class GameWakeLockTest {
    @Test
    fun `held while a game runs and the shell is not in front`() {
        assertTrue(GameWakeLock.shouldHold(NEVER_SLEEP, gameRunning = true, gameDisplayId = 0, shellFrontDisplayId = null))
    }

    @Test
    fun `released the moment the shell comes in front on the game's display`() {
        assertFalse(GameWakeLock.shouldHold(NEVER_SLEEP, gameRunning = true, gameDisplayId = 0, shellFrontDisplayId = 0))
        assertFalse(GameWakeLock.shouldHold(NEVER_SLEEP, gameRunning = true, gameDisplayId = null, shellFrontDisplayId = 0))
    }

    @Test
    fun `held when the shell is in front on the other screen`() {
        assertTrue(GameWakeLock.shouldHold(NEVER_SLEEP, gameRunning = true, gameDisplayId = 2, shellFrontDisplayId = 0))
    }

    @Test
    fun `never held without a running game or with the system timer`() {
        assertFalse(GameWakeLock.shouldHold(NEVER_SLEEP, gameRunning = false, gameDisplayId = 0, shellFrontDisplayId = null))
        assertFalse(GameWakeLock.shouldHold(SYSTEM_TIMER, gameRunning = true, gameDisplayId = 0, shellFrontDisplayId = null))
    }
}
