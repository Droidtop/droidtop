package dev.droidtop.runtime.windows

import dev.droidtop.mapping.ControllerLayout
import dev.droidtop.mapping.MappingEngine
import dev.droidtop.mapping.MappingEngine.Output
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mouse mode as a layout (docs/SPEC.md 5b, "Controller mouse mode"; Droidtop/tracker#618 C4): the bundled file plays
 * what `PadMouseDriver` did, through the one engine.
 */
class MouseModeLayoutTest {
    private val layout = ControllerLayout.parse(File("src/main/res/raw/controls_mouse_mode.vdf").readText())

    @Test
    fun `it reads back as the same tree and names itself`() {
        assertEquals(layout.root, ControllerLayout.parse(layout.write()).root)
        assertEquals("Mouse mode", layout.localized(layout.title))
    }

    @Test
    fun `the right stick is the pointer on mouse mode's curve, R1 clicks and L1 right-clicks`() {
        val e = MappingEngine(layout)
        e.stick("right_joystick", 1f, 0f, 0)
        val move = e.tick(1000).single() as Output.MouseMove
        // Full push: 1.5 screen widths a second, as mouse mode always moved.
        assertEquals(MappingEngine.MOUSE_FULL_WIDTHS_PER_S, move.dx, 0.001f)
        // The stick back at rest, so the clicks below carry no pointer travel with them.
        e.stick("right_joystick", 0f, 0f, 1000)
        assertEquals(listOf(Output.MouseDown("LEFT")), e.press("switch", "right_bumper", 1000))
        assertEquals(listOf(Output.MouseUp("LEFT")), e.release("switch", "right_bumper", 1100))
        assertEquals(listOf(Output.MouseDown("RIGHT")), e.press("switch", "left_bumper", 1200))
    }

    @Test
    fun `the right stick's click held turns it into a wheel`() {
        val e = MappingEngine(layout)
        e.press("right_joystick", "click", 0)
        e.stick("right_joystick", 0f, 1f, 0)
        assertTrue(e.tick(500).all { it == Output.Wheel(MappingEngine.SCROLL_DOWN) })
        e.release("right_joystick", "click", 500)
        e.stick("right_joystick", 0f, 1f, 500)
        assertTrue(e.tick(600).single() is Output.MouseMove)
    }

    @Test
    fun `everything else still reaches the game as the pad`() {
        val e = MappingEngine(layout)
        assertNull(e.groupOn("button_diamond"))
        assertNull(e.groupOn("dpad"))
        assertNull(e.groupOn("joystick"))
        assertNull(e.groupOn("left_trigger"))
        assertNull(e.groupOn("switch")?.input("button_menu"))
    }
}
