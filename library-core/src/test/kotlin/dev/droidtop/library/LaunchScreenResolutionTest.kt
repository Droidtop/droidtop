package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The launch-screen priority chain (docs/SPEC.md section 4c), proven
 * without a device: per-game over per-system over ask over the global
 * target, and honest degradation when a remembered screen is not
 * currently attached. Display behaviour itself cannot be verified from
 * this environment (section 6c), so everything decidable is decided
 * here.
 */
class LaunchScreenResolutionTest {

    @Test
    fun `per-game choice wins over the per-system default`() {
        assertEquals(
            LaunchScreen.BUILT_IN,
            LaunchScreenResolution.remembered(game = LaunchScreen.BUILT_IN, system = LaunchScreen.SECOND),
        )
    }

    @Test
    fun `per-system default applies when the game has no choice of its own`() {
        assertEquals(
            LaunchScreen.SECOND,
            LaunchScreenResolution.remembered(game = null, system = LaunchScreen.SECOND),
        )
    }

    @Test
    fun `no memory at either level resolves to nothing`() {
        assertEquals(null, LaunchScreenResolution.remembered(game = null, system = null))
    }

    @Test
    fun `remembered built-in launches on the built-in display without asking`() {
        val decision = LaunchScreenResolution.decide(
            remembered = LaunchScreen.BUILT_IN,
            secondDisplayId = 9,
            askable = true,
            globalTarget = 9,
        )
        assertEquals(LaunchScreenResolution.Decision.Start(LaunchScreenResolution.BUILT_IN_DISPLAY), decision)
    }

    @Test
    fun `remembered second launches on the second display without asking`() {
        val decision = LaunchScreenResolution.decide(
            remembered = LaunchScreen.SECOND,
            secondDisplayId = 9,
            askable = true,
            globalTarget = null,
        )
        assertEquals(LaunchScreenResolution.Decision.Start(9), decision)
    }

    @Test
    fun `remembered second with no second display opens on the shell's screen`() {
        // The game still starts, on the only screen there is -- a
        // remembered preference must never fail a launch.
        val decision = LaunchScreenResolution.decide(
            remembered = LaunchScreen.SECOND,
            secondDisplayId = null,
            askable = false,
            globalTarget = null,
        )
        assertEquals(LaunchScreenResolution.Decision.Start(null), decision)
    }

    @Test
    fun `no memory asks when asking is possible`() {
        val decision = LaunchScreenResolution.decide(
            remembered = null,
            secondDisplayId = 9,
            askable = true,
            globalTarget = null,
        )
        assertEquals(LaunchScreenResolution.Decision.Ask, decision)
    }

    @Test
    fun `no memory and no chooser falls through to the global target`() {
        val decision = LaunchScreenResolution.decide(
            remembered = null,
            secondDisplayId = 9,
            askable = false,
            globalTarget = 9,
        )
        assertEquals(LaunchScreenResolution.Decision.Start(9), decision)
    }

    @Test
    fun `a chosen display id maps back to the screen it means`() {
        assertEquals(LaunchScreen.SECOND, LaunchScreenResolution.screenFor(displayId = 9, secondDisplayId = 9))
        assertEquals(LaunchScreen.BUILT_IN, LaunchScreenResolution.screenFor(displayId = null, secondDisplayId = 9))
        assertEquals(LaunchScreen.BUILT_IN, LaunchScreenResolution.screenFor(displayId = 0, secondDisplayId = 9))
    }

    @Test
    fun `no choice opens on the shell's screen, and only a choice of the built-in one opens there`() {
        // The console with its shell on the external display 4: the built-in display 0 is the companion's.
        assertEquals(4, LaunchScreenResolution.resolveDisplay(displayId = null, shellDisplayId = 4))
        assertEquals(0, LaunchScreenResolution.resolveDisplay(displayId = LaunchScreenResolution.BUILT_IN_DISPLAY, shellDisplayId = 4))
        assertEquals(9, LaunchScreenResolution.resolveDisplay(displayId = 9, shellDisplayId = 4))
        // Before the shell has said where it is, the built-in screen.
        assertEquals(0, LaunchScreenResolution.resolveDisplay(displayId = null, shellDisplayId = null))
    }
}
