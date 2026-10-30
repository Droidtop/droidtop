package dev.droidtop.shell.gamepad.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hint bar's fit rule (docs/SPEC.md 7j, "The hint bar fits"; Droidtop/tracker#485 F2a). */
class HintFitTest {
    private val hints = listOf(
        GamepadAction.A to "Open",
        GamepadAction.X to "Store",
        GamepadAction.L to "Switch tab",
        GamepadAction.SELECT to "Options",
        GamepadAction.B to "Close",
        GamepadAction.START to "Menu",
        GamepadAction.R2 to "Quick Menu · 3 new",
    )

    // A made-up width: each hint costs 10 plus its label's length, a glyph alone 10.
    private val width: (List<HintFit.Shown>) -> Float = { list -> list.sumOf { 10 + (it.label?.length ?: 0) }.toFloat() }

    @Test
    fun `start and R2 always come first`() {
        val shown = HintFit.at(hints, HintFit.Stage.FULL)
        assertEquals(listOf(GamepadAction.START, GamepadAction.R2), shown.take(2).map { it.action })
    }

    @Test
    fun `everything shows when it fits`() {
        assertEquals(7, HintFit.fit(hints, 1000f, width).size)
    }

    @Test
    fun `the menus shrink to glyphs first and R2 keeps its count`() {
        val full = width(HintFit.at(hints, HintFit.Stage.FULL))
        val shown = HintFit.fit(hints, full - 1, width)
        assertEquals(HintFit.Shown(GamepadAction.START, null), shown[0])
        assertEquals(HintFit.Shown(GamepadAction.R2, "3 new"), shown[1])
        assertEquals(7, shown.size)
    }

    @Test
    fun `then L1 and R1 drop, then Select keeps only its glyph, and A and B never shrink`() {
        val glyphs = width(HintFit.at(hints, HintFit.Stage.MENUS_AS_GLYPHS))
        val noShoulders = HintFit.fit(hints, glyphs - 1, width)
        assertTrue(noShoulders.none { it.action == GamepadAction.L })
        assertTrue(noShoulders.any { it.action == GamepadAction.SELECT })
        val tiny = HintFit.fit(hints, 1f, width)
        assertTrue(tiny.none { it.action == GamepadAction.L })
        // Select's pill can be the only touch route to what it opens (Droidtop/tracker#839): its glyph stays, tappable.
        assertEquals(HintFit.Shown(GamepadAction.SELECT, null), tiny.single { it.action == GamepadAction.SELECT })
        assertTrue(tiny.any { it.action == GamepadAction.A } && tiny.any { it.action == GamepadAction.B })
    }

    @Test
    fun `a screen reader hears the whole set`() {
        assertEquals(
            "Controls: Start Menu, R2 Quick Menu · 3 new, A Open",
            HintFit.spoken(listOf(GamepadAction.A to "Open", GamepadAction.START to "Menu", GamepadAction.R2 to "Quick Menu · 3 new")) { it.name.let { n -> if (n == "START") "Start" else n } },
        )
    }
}
