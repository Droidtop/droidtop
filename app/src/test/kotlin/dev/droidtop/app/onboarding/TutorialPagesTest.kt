package dev.droidtop.app.onboarding

import dev.droidtop.app.TutorialPages
import dev.droidtop.library.settings.Mode
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.standard.HomeRolePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tutorial tells a setup only what that setup has (docs/SPEC.md 7b). */
class TutorialPagesTest {

    private fun pages(
        gaming: Boolean = true,
        desktop: Boolean = false,
        home: HomeRolePrefs.HomeImplementation = HomeRolePrefs.HomeImplementation.STANDARD,
        homeGoesTo: Mode? = Mode.LAUNCHER,
        defaultChosen: Boolean = true,
    ) = TutorialPages.build(gaming, desktop, home, homeGoesTo, defaultChosen) { it.name }

    @Test
    fun `a gaming setup is taught the quick menu and a game's page`() {
        val titles = pages().map { it.title }
        assertTrue("The Quick Menu" in titles)
        assertTrue("A game's page" in titles)
        assertEquals("Getting around", titles.first())
        assertEquals("Getting help", titles.last())
    }

    @Test
    fun `a setup without gaming is not taught gaming`() {
        val all = pages(gaming = false)
        assertFalse(all.any { it.title == "The Quick Menu" })
        assertFalse(all.flatMap { it.rows }.any { it.key == GamepadAction.R2.name })
    }

    @Test
    fun `the home button row says where home goes`() {
        fun homeRow(vararg args: Any?) = pages(
            homeGoesTo = args[0] as Mode?,
            defaultChosen = args[1] as Boolean,
        ).first { it.title == "Switching modes" }.rows.first { it.key == "Home button" }.does

        assertEquals("Takes you to your Android home screen", homeRow(Mode.LAUNCHER, true))
        assertEquals("Takes you to Gaming", homeRow(Mode.GAMING, true))
        assertEquals("Takes you back to the mode you used last", homeRow(Mode.GAMING, false))
    }

    @Test
    fun `every way into a mode named is one this setup has`() {
        val rows = pages(desktop = false, home = HomeRolePrefs.HomeImplementation.NONE)
            .first { it.title == "Switching modes" }.rows
        assertFalse(rows.any { it.key == "In Desktop" })
        assertFalse(rows.any { it.key == "On the home screen" })
        assertFalse(rows.any { it.key == "Home button" })
        assertTrue(rows.any { it.key == "From your launcher" })
    }
}
