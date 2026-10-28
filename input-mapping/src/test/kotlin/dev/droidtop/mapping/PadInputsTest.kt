package dev.droidtop.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A pad's buttons by Steam's names (Droidtop/tracker#618). */
class PadInputsTest {
    @Test
    fun `face buttons, the d-pad, shoulders, triggers and stick clicks have Steam's names`() {
        assertEquals(MappingEngine.Physical("button_diamond", "button_a"), PadInputs.physical(PadInputs.KEYCODE_BUTTON_A))
        assertEquals(MappingEngine.Physical("dpad", "dpad_west"), PadInputs.physical(PadInputs.KEYCODE_DPAD_LEFT))
        assertEquals(MappingEngine.Physical("switch", "right_bumper"), PadInputs.physical(PadInputs.KEYCODE_BUTTON_R1))
        assertEquals(MappingEngine.Physical("switch", "button_menu"), PadInputs.physical(PadInputs.KEYCODE_BUTTON_START))
        assertEquals(MappingEngine.Physical("left_trigger", "click"), PadInputs.physical(PadInputs.KEYCODE_BUTTON_L2))
        assertEquals(MappingEngine.Physical("right_joystick", "click"), PadInputs.physical(PadInputs.KEYCODE_BUTTON_THUMBR))
    }

    @Test
    fun `a key that is not a pad button has none`() {
        assertNull(PadInputs.physical(29))
    }
}
