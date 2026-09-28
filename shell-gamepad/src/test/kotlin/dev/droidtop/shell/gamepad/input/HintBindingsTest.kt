package dev.droidtop.shell.gamepad.input

import org.junit.Assert.assertEquals
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
}
