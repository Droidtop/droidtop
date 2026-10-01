package dev.droidtop.app.onboarding

import dev.droidtop.app.defaultModeChoices
import dev.droidtop.library.settings.Mode
import dev.droidtop.shell.standard.HomeRolePrefs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The default-mode step offers the OUTCOME of the setup, not the
 * tick-box (docs/SPEC.md 7b, "Default mode"): with no mode set up at all
 * it confirms Gaming, which explains what to add, instead of offering the
 * home screen -- the "Anything else to set up?" step says droidtop keeps
 * Gaming on when nothing is ticked, so finishing must open into it, not
 * Android with both modes off (Droidtop/tracker#166).
 */
class OnboardingDefaultModeTest {

    @Test
    fun `with no mode set up the step confirms Gaming on every home answer`() {
        HomeRolePrefs.HomeImplementation.entries.forEach { home ->
            assertEquals(
                listOf(Mode.GAMING),
                defaultModeChoices(home, gamingUsable = false, desktopUsable = false).map { it.first },
            )
        }
    }

    @Test
    fun `the home screen is offered only while a mode is set up`() {
        assertEquals(
            listOf(Mode.LAUNCHER, Mode.GAMING),
            defaultModeChoices(
                HomeRolePrefs.HomeImplementation.STANDARD, gamingUsable = true, desktopUsable = false,
            ).map { it.first },
        )
        assertEquals(
            listOf(Mode.LAUNCHER, Mode.DESKTOP),
            defaultModeChoices(
                HomeRolePrefs.HomeImplementation.ALTERNATIVE, gamingUsable = false, desktopUsable = true,
            ).map { it.first },
        )
        // No HOME role held: the home screen is not an answer at all.
        assertEquals(
            listOf(Mode.GAMING),
            defaultModeChoices(
                HomeRolePrefs.HomeImplementation.NONE, gamingUsable = true, desktopUsable = false,
            ).map { it.first },
        )
    }
}
