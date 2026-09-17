package dev.droidtop.shell.gamepad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One help row per screen (docs/SPEC.md 7j). The same value draws
 * droidtop's own bar and suppresses the theme's `<helpsystem>`, so there
 * is no combination in which both appear -- which is exactly what two
 * independent conditions produced in landscape with Slate (rig, build
 * 546: the theme's row drawn under the bar and sliced in half).
 */
class HelpRowOwnerTest {

    @Test
    fun `hints switched off means nobody draws the shell's bar`() {
        assertFalse(esDeShellOwnsHelpRow(showHints = false, touchFirst = true, themeHandlesHints = false))
        assertFalse(esDeShellOwnsHelpRow(showHints = false, touchFirst = false, themeHandlesHints = true))
    }

    @Test
    fun `a touch-first window always keeps the bar, theme row or not`() {
        assertTrue(esDeShellOwnsHelpRow(showHints = true, touchFirst = true, themeHandlesHints = true))
        assertTrue(esDeShellOwnsHelpRow(showHints = true, touchFirst = true, themeHandlesHints = false))
    }

    @Test
    fun `on the console the theme takes the row when it has one`() {
        assertFalse(esDeShellOwnsHelpRow(showHints = true, touchFirst = false, themeHandlesHints = true))
    }

    @Test
    fun `on the console a theme with no help row leaves it to the shell`() {
        assertTrue(esDeShellOwnsHelpRow(showHints = true, touchFirst = false, themeHandlesHints = false))
    }
}
