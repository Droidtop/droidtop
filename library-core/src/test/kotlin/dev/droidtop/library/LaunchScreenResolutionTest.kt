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
    fun `remembered built-in launches on the default display without asking`() {
        val decision = LaunchScreenResolution.decide(
            remembered = LaunchScreen.BUILT_IN,
            secondDisplayId = 9,
            askable = true,
            globalTarget = 9,
        )
        assertEquals(LaunchScreenResolution.Decision.Start(null), decision)
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
    fun `remembered second with no second display degrades to the default display`() {
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
}
