package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Switch keyboard" (docs/SPEC.md 6a, Droidtop/tracker#717). */
class SwitchKeyboardTest {
    @Test
    fun `a long press on space switches keyboard, other keys keep their own`() {
        assertEquals(SwitchKeyboard.CODE, SwitchKeyboard.longPressCode(' '.code))
        assertNull(SwitchKeyboard.longPressCode('a'.code))
        assertNull(SwitchKeyboard.longPressCode('0'.code))
    }

    @Test
    fun `the code is the keyboard's own and never reaches a container`() {
        assertNull(HackersKeyCodes.specialStroke(SwitchKeyboard.CODE))
        // It sits beside the fork's other keyboard codes without taking one of theirs (-100 to -105, -10024).
        assertEquals(-106, SwitchKeyboard.CODE)
    }
}
