package dev.droidtop.shell.gamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell's three levels and what B means at each (see [ShellBackStack]).
 * Build 542: B from a PC game's detail landed on the system carousel, at
 * the top, instead of the grid it was opened from.
 */
class ShellNavigationTest {

    private fun stack() = ShellBackStack(GamingSection.GAMES)

    @Test
    fun `back from a detail returns to the group it was opened from`() {
        val nav = stack()
        nav.openGroup("system:pc")
        nav.rememberFocus("game-42")
        nav.openDetail("game-42")

        assertEquals(ShellPlace.Detail("game-42"), nav.place)
        assertEquals(ShellPlace.Group("system:pc"), nav.under)

        assertEquals(ShellPlace.Group("system:pc"), nav.back())
        assertEquals("game-42", nav.focusHere)
    }

    @Test
    fun `back from a group's options screen returns to that group`() {
        val nav = stack()
        nav.openGroup("system:pc")
        nav.rememberFocus("astroneer")
        nav.openOptions()

        assertEquals(ShellPlace.Options("system:pc"), nav.place)
        assertEquals(ShellPlace.Group("system:pc"), nav.under)

        assertEquals(ShellPlace.Group("system:pc"), nav.back())
        assertFalse(nav.optionsOpen)
        assertEquals("system:pc", nav.groupKey)
        assertEquals("astroneer", nav.focusHere)
    }

    @Test
    fun `leaving the group closes its options screen with it`() {
        val nav = stack()
        nav.openGroup("system:pc")
        nav.openOptions()
        nav.openGroup(null)
        assertFalse(nav.optionsOpen)
        assertEquals(ShellPlace.Section(GamingSection.GAMES), nav.place)
    }

    @Test
    fun `there is no options screen without a group under it`() {
        val nav = stack()
        nav.openOptions()
        assertFalse(nav.optionsOpen)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `back from a group returns to the section`() {
        val nav = stack()
        nav.openGroup("system:snes")
        assertEquals(ShellPlace.Section(GamingSection.GAMES), nav.back())
        assertNull(nav.groupKey)
    }

    @Test
    fun `back at the top of the shell goes nowhere`() {
        val nav = stack()
        assertFalse(nav.canGoBack)
        assertNull(nav.back())
        assertEquals(ShellPlace.Section(GamingSection.GAMES), nav.place)
    }

    @Test
    fun `opening another version of the same game is sideways, not deeper`() {
        val nav = stack()
        nav.openGroup("system:pc")
        nav.openDetail("fetish-locator-week-1")
        nav.openDetail("fetish-locator-week-2")

        assertEquals(ShellPlace.Group("system:pc"), nav.back())
        assertNull(nav.detailId)
        assertEquals("system:pc", nav.groupKey)
    }

    @Test
    fun `each place remembers what was focused in it`() {
        val nav = stack()
        nav.openGroup("system:pc")
        nav.rememberFocus("pc-game")
        nav.openGroup("system:snes")
        nav.rememberFocus("snes-game")

        assertEquals("pc-game", nav.focusIn(ShellPlace.Group("system:pc")))
        assertEquals("snes-game", nav.focusIn(ShellPlace.Group("system:snes")))
        nav.openGroup("system:pc")
        assertEquals("pc-game", nav.focusHere)
    }

    @Test
    fun `a group this session has not been in has nothing to return to`() {
        val nav = stack()
        nav.openGroup("system:gc")
        assertNull(nav.focusHere)
    }

    @Test
    fun `switching sections leaves no group or detail open`() {
        val nav = stack()
        nav.openGroup("system:pc")
        nav.openDetail("game-1")
        nav.openSection(GamingSection.SETTINGS)

        assertEquals(GamingSection.SETTINGS, nav.section)
        assertNull(nav.groupKey)
        assertNull(nav.detailId)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `moving sideways between groups closes the open detail`() {
        val nav = stack()
        nav.openGroup("system:pc")
        nav.openDetail("game-1")
        nav.openGroup("system:snes")
        assertNull(nav.detailId)
        assertTrue(nav.canGoBack)
    }
}
