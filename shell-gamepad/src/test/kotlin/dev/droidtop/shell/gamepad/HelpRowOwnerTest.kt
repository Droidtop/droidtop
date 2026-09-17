package dev.droidtop.shell.gamepad

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One help row per screen (docs/SPEC.md 7j). One function answers who
 * draws it, and every side reads that one answer, so there is no
 * combination in which two rows appear -- which is exactly what
 * independent conditions produced twice: the theme's row drawn under the
 * shell's bar and sliced in half in landscape with Slate (rig, build
 * 546), and the PC grid's own row with the shell's bar stacked beneath
 * it in portrait (rig, build 547).
 */
class HelpRowOwnerTest {

    @Test
    fun `hints switched off leaves the row to nobody`() {
        assertEquals(
            HelpRowOwner.NONE,
            esDeHelpRowOwner(showHints = false, touchFirst = true, claim = HelpRowClaim.NONE),
        )
        assertEquals(
            HelpRowOwner.NONE,
            esDeHelpRowOwner(showHints = false, touchFirst = true, claim = HelpRowClaim.SCREEN),
        )
    }

    @Test
    fun `a theme still draws its own row with droidtop's hints switched off`() {
        // The pref is droidtop's own bar; a themed view switched off by
        // it would lose the row the THEME drew, which is not what the
        // setting says.
        assertEquals(
            HelpRowOwner.THEME,
            esDeHelpRowOwner(showHints = false, touchFirst = false, claim = HelpRowClaim.THEME),
        )
    }

    @Test
    fun `a touch-first window takes a theme's row, because that row is a legend`() {
        assertEquals(
            HelpRowOwner.SHELL,
            esDeHelpRowOwner(showHints = true, touchFirst = true, claim = HelpRowClaim.THEME),
        )
    }

    @Test
    fun `a screen's own row is never doubled by the shell's, in either shape`() {
        assertEquals(
            HelpRowOwner.SCREEN,
            esDeHelpRowOwner(showHints = true, touchFirst = true, claim = HelpRowClaim.SCREEN),
        )
        assertEquals(
            HelpRowOwner.SCREEN,
            esDeHelpRowOwner(showHints = true, touchFirst = false, claim = HelpRowClaim.SCREEN),
        )
    }

    @Test
    fun `on the console the theme takes the row when it has one`() {
        assertEquals(
            HelpRowOwner.THEME,
            esDeHelpRowOwner(showHints = true, touchFirst = false, claim = HelpRowClaim.THEME),
        )
    }

    @Test
    fun `a screen with nothing of its own leaves the row to the shell`() {
        assertEquals(
            HelpRowOwner.SHELL,
            esDeHelpRowOwner(showHints = true, touchFirst = false, claim = HelpRowClaim.NONE),
        )
        assertEquals(
            HelpRowOwner.SHELL,
            esDeHelpRowOwner(showHints = true, touchFirst = true, claim = HelpRowClaim.NONE),
        )
    }
}
