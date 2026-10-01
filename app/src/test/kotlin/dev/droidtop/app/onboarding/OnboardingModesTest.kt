package dev.droidtop.app.onboarding

import dev.droidtop.app.appModesOnAfterOnboarding
import dev.droidtop.library.settings.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "What droidtop opens into" is the mode switch, not only a route through
 * the steps (docs/SPEC.md 7b): a mode not set up is off after onboarding,
 * so it runs nothing (SPEC 2c, Rule 1).
 */
class OnboardingModesTest {

    @Test
    fun `a mode not set up is switched off`() {
        assertEquals(
            setOf(Mode.GAMING),
            appModesOnAfterOnboarding(
                configureGaming = true, configureDesktop = false, desktopCapable = true, opensInto = Mode.GAMING,
            ),
        )
        assertEquals(
            setOf(Mode.DESKTOP),
            appModesOnAfterOnboarding(
                configureGaming = false, configureDesktop = true, desktopCapable = true, opensInto = Mode.DESKTOP,
            ),
        )
    }

    @Test
    fun `desktop that cannot run here stays off even when set up`() {
        assertEquals(
            setOf(Mode.GAMING),
            appModesOnAfterOnboarding(
                configureGaming = true, configureDesktop = true, desktopCapable = false, opensInto = Mode.GAMING,
            ),
        )
    }

    @Test
    fun `the mode onboarding opens into is on`() {
        // A mode cannot be opened while it is off.
        assertEquals(
            setOf(Mode.GAMING),
            appModesOnAfterOnboarding(
                configureGaming = false, configureDesktop = false, desktopCapable = false, opensInto = Mode.GAMING,
            ),
        )
    }
}
