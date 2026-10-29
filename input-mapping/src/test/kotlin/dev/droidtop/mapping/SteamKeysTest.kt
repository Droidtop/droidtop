package dev.droidtop.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Steam's key names as Android key codes (Droidtop/tracker#383); the expected codes are Android's KEYCODE_* values. */
class SteamKeysTest {
    @Test
    fun `letters, digits and function keys are themselves`() {
        assertEquals(29, SteamKeys.androidKeyCode("A"))
        assertEquals(31, SteamKeys.androidKeyCode("c"))
        assertEquals(54, SteamKeys.androidKeyCode("Z"))
        assertEquals(7, SteamKeys.androidKeyCode("0"))
        assertEquals(16, SteamKeys.androidKeyCode("9"))
        assertEquals(131, SteamKeys.androidKeyCode("F1"))
        assertEquals(142, SteamKeys.androidKeyCode("F12"))
    }

    @Test
    fun `named keys accept Steam's spelling and the common one`() {
        assertEquals(113, SteamKeys.androidKeyCode("LEFT_CONTROL"))
        assertEquals(113, SteamKeys.androidKeyCode("CONTROL"))
        assertEquals(19, SteamKeys.androidKeyCode("UP_ARROW"))
        assertEquals(22, SteamKeys.androidKeyCode("RIGHT"))
        assertEquals(111, SteamKeys.androidKeyCode("ESCAPE"))
    }

    @Test
    fun `a name with no key is null`() {
        assertNull(SteamKeys.androidKeyCode("F13"))
        assertNull(SteamKeys.androidKeyCode("NOT_A_KEY"))
        assertNull(SteamKeys.androidKeyCode(""))
    }
}
