package dev.droidtop.shell.gamepad

import androidx.compose.runtime.saveable.SaverScope
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

    // listSaver's save asks its SaverScope what may be stored; this one
    // stores anything, which is all the round-trip tests need.
    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

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

    @Test
    fun `the saver round-trips the whole place`() {
        val nav = stack()
        nav.openGroup("system:pc")
        nav.rememberFocus("pc-game")
        nav.openOptions()
        nav.openSection(GamingSection.SETTINGS)

        val saved = with(ShellBackStack.Saver) { saverScope.save(nav) }
        val restored = ShellBackStack.Saver.restore(saved!!)

        assertEquals(GamingSection.SETTINGS, restored!!.section)
        assertNull(restored.groupKey)
        assertNull(restored.detailId)
        assertFalse(restored.optionsOpen)
        // The focus saved in a place the section switch left survives:
        // returning to that PC grid lands on the game that was on it.
        assertEquals("pc-game", restored.focusIn(ShellPlace.Group("system:pc")))
        assertEquals(ShellPlace.Section(GamingSection.SETTINGS), restored.place)
    }

    @Test
    fun `the saver round-trips an open detail and per-place focus`() {
        val nav = stack()
        nav.openGroup("system:snes")
        nav.rememberFocus("snes-game")
        nav.openDetail("snes-game")

        val saved = with(ShellBackStack.Saver) { saverScope.save(nav) }
        val restored = ShellBackStack.Saver.restore(saved!!)

        assertEquals("system:snes", restored!!.groupKey)
        assertEquals("snes-game", restored.detailId)
        assertEquals(ShellPlace.Detail("snes-game"), restored.place)
        assertEquals(ShellPlace.Group("system:snes"), restored.under)
        // B out of the restored detail lands on the game it was opened
        // from, same as it did before the save.
        assertEquals(ShellPlace.Group("system:snes"), restored.back())
        assertEquals("snes-game", restored.focusHere)
    }

    @Test
    fun `the saver saves and restores the top level of the shell`() {
        val saved = with(ShellBackStack.Saver) { saverScope.save(stack()) }
        val restored = ShellBackStack.Saver.restore(saved!!)

        assertEquals(GamingSection.GAMES, restored!!.section)
        assertNull(restored.groupKey)
        assertNull(restored.detailId)
        assertFalse(restored.optionsOpen)
        assertNull(restored.back())
    }

    @Test
    fun `the saver refuses a save it cannot read back`() {
        assertNull(ShellBackStack.Saver.restore(emptyList<Any>()))
        assertNull(ShellBackStack.Saver.restore(listOf("not-a-section")))
    }
}
