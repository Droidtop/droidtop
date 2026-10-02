package dev.droidtop.shell.gamepad.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A hint row promises only what dispatches (docs/SPEC.md 7j, UI pass
 * 2026-09-24 H8): the row is the pairs whose [HintBinding.bound]
 * conditions hold, nothing else, and a condition that dies takes the
 * promise with it in the same read.
 */
class HintBindingsTest {

    @Test
    fun `a binding whose condition fails is not promised`() {
        val pairs = activeHintPairs(
            listOf(
                HintBinding(GamepadAction.A, "Open") { false },
                HintBinding(GamepadAction.B, "Close"),
            )
        )

        assertEquals(listOf(GamepadAction.B to "Close"), pairs)
    }

    @Test
    fun `a binding with no condition is always promised`() {
        val pairs = activeHintPairs(listOf(HintBinding(GamepadAction.Y, "Info")))

        assertEquals(listOf(GamepadAction.Y to "Info"), pairs)
    }

    @Test
    fun `order is the caller's, so a row reads the same built left to right`() {
        val pairs = activeHintPairs(
            listOf(
                HintBinding(GamepadAction.B, "Back") { true },
                HintBinding(GamepadAction.A, "Select") { false },
                HintBinding(GamepadAction.SELECT, "Options") { true },
            )
        )

        assertEquals(
            listOf(GamepadAction.B to "Back", GamepadAction.SELECT to "Options"),
            pairs,
        )
    }

    @Test
    fun `nothing bound means nothing promised`() {
        assertEquals(emptyList<Pair<GamepadAction, String>>(), activeHintPairs(emptyList()))
    }

    @Test
    fun `only the focus owner's declaration shows and a late release by the old owner is ignored`() {
        val host = FocusedHints()
        val first = Any()
        val second = Any()
        val one = listOf(HintBinding(GamepadAction.A, "One"))
        val two = listOf(HintBinding(GamepadAction.A, "Two"))

        host.declare(first, one)
        assertSame(one, host.bindings)

        // The next element takes the focus before the old one reports losing it.
        host.declare(second, two)
        host.release(first)
        assertSame(two, host.bindings)

        host.release(second)
        assertNull(host.bindings)
    }

    private val menus = ShellMenuHints
    private val own = listOf(HintBinding(GamepadAction.A, "Select"))
    private val sheet = listOf(HintBinding(GamepadAction.B, "Back"))

    @Test
    fun `the menu pills are Start Menu and R2 Quick Menu and nothing else`() {
        assertEquals(
            listOf(GamepadAction.START to "Menu", GamepadAction.R2 to "Quick Menu"),
            activeHintPairs(menus),
        )
    }

    @Test
    fun `a screen's footer begins with both menu pills, then its own hints`() {
        val row = footerBindings(null, false, null, own, menus, emptyList())
        assertEquals(menus + own, row)
    }

    @Test
    fun `a focused element's declaration replaces the fallback but keeps the menu pills`() {
        val declared = listOf(HintBinding(GamepadAction.X, "Filter"))
        assertEquals(menus + declared, footerBindings(null, false, declared, own, menus, emptyList()))
    }

    @Test
    fun `a sheet shows its own hints alone, the menus doing nothing behind it`() {
        assertEquals(sheet, footerBindings(sheet, false, null, own, menus, emptyList()))
    }

    @Test
    fun `a layer that answers Start and R2 keeps both pills`() {
        assertEquals(menus + sheet, footerBindings(sheet, true, null, own, menus, emptyList()))
    }

    @Test
    fun `no footer names a shoulder that switches section`() {
        val pairs = activeHintPairs(footerBindings(null, false, null, own, menus, emptyList()))
        assertEquals(emptyList<Pair<GamepadAction, String>>(), pairs.filter { it.first == GamepadAction.L || it.first == GamepadAction.R })
    }
}
