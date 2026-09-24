package dev.droidtop.app.onboarding

import dev.droidtop.app.OnboardingRun
import dev.droidtop.app.appModesOnAfterOnboarding
import dev.droidtop.library.settings.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Anything else to set up?" is the mode switch, not only a route through
 * the steps (docs/SPEC.md 7b): an unticked mode is off after onboarding,
 * so it runs nothing (SPEC 2c, Rule 1).
 */
class OnboardingModesTest {

    @Test
    fun `an unticked mode is switched off`() {
        assertEquals(
            setOf(Mode.GAMING),
            appModesOnAfterOnboarding(
                configureGaming = true, configureDesktop = false, desktopCapable = true, opensInto = Mode.GAMING,
            ),
        )
        assertEquals(
            emptySet<Mode>(),
            appModesOnAfterOnboarding(
                configureGaming = false, configureDesktop = false, desktopCapable = true, opensInto = Mode.LAUNCHER,
            ),
        )
    }

    @Test
    fun `desktop that cannot run here stays off even when ticked`() {
        assertEquals(
            setOf(Mode.GAMING),
            appModesOnAfterOnboarding(
                configureGaming = true, configureDesktop = true, desktopCapable = false, opensInto = Mode.GAMING,
            ),
        )
    }

    @Test
    fun `the mode onboarding opens into is on`() {
        // Nothing set up at all: onboarding opens Gaming, which cannot be
        // opened while it is off.
        assertEquals(
            setOf(Mode.GAMING),
            appModesOnAfterOnboarding(
                configureGaming = false, configureDesktop = false, desktopCapable = false, opensInto = Mode.GAMING,
            ),
        )
    }

    @Test
    fun `a first run starts with nothing ticked and a rerun starts from the modes that are on`() {
        val first = OnboardingRun()
        first.start(startStep = null, storageGranted = false, modesOnBefore = null)
        assertEquals(false, first.configureGaming.value)
        assertEquals(false, first.configureDesktop.value)

        val rerun = OnboardingRun()
        rerun.start(startStep = null, storageGranted = true, modesOnBefore = setOf(Mode.GAMING, Mode.LAUNCHER))
        assertEquals(true, rerun.configureGaming.value)
        assertEquals(false, rerun.configureDesktop.value)
    }
}
