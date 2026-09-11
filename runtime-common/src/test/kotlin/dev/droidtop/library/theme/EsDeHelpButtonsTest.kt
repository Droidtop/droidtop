package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real `HelpComponent::assignIcons` (HelpComponent.cpp:435-560) as a
 * table: which `customButtonIcon` attribute value each help entry reads,
 * per controller family.
 */
class EsDeHelpButtonsTest {

    @Test
    fun `twelve keys are the same in every family`() {
        val shared = listOf(
            EsDeHelpButton.DPAD_UP to "dpad_up",
            EsDeHelpButton.DPAD_DOWN to "dpad_down",
            EsDeHelpButton.DPAD_UPDOWN to "dpad_updown",
            EsDeHelpButton.DPAD_LEFTRIGHT to "dpad_leftright",
            EsDeHelpButton.DPAD_ALL to "dpad_all",
            EsDeHelpButton.THUMBSTICK_CLICK to "thumbstick_click",
            EsDeHelpButton.SHOULDER_L to "button_l",
            EsDeHelpButton.SHOULDER_R to "button_r",
            EsDeHelpButton.SHOULDER_LR to "button_lr",
            EsDeHelpButton.TRIGGER_L to "button_lt",
            EsDeHelpButton.TRIGGER_R to "button_rt",
            EsDeHelpButton.TRIGGER_LR to "button_ltrt",
        )
        for (family in EsDeControllerFamily.entries) {
            for ((button, key) in shared) {
                assertEquals(key, esDeHelpButtonIconKey(button, family))
            }
        }
    }

    @Test
    fun `face buttons take the family's art set`() {
        assertEquals("button_a_XBOX", esDeHelpButtonIconKey(EsDeHelpButton.A, EsDeControllerFamily.XBOX))
        assertEquals("button_a_XBOX", esDeHelpButtonIconKey(EsDeHelpButton.A, EsDeControllerFamily.XBOX360))
        assertEquals("button_b_PS", esDeHelpButtonIconKey(EsDeHelpButton.B, EsDeControllerFamily.PS5))
        assertEquals("button_x_SNES", esDeHelpButtonIconKey(EsDeHelpButton.X, EsDeControllerFamily.SNES))
        assertEquals("button_y_switch", esDeHelpButtonIconKey(EsDeHelpButton.Y, EsDeControllerFamily.SWITCH_PRO))
    }

    @Test
    fun `back and start split further inside the PlayStation and Xbox families`() {
        assertEquals("button_back_XBOX", esDeHelpButtonIconKey(EsDeHelpButton.BACK, EsDeControllerFamily.XBOX))
        assertEquals("button_back_XBOX360", esDeHelpButtonIconKey(EsDeHelpButton.BACK, EsDeControllerFamily.XBOX360))
        assertEquals("button_start_PS4", esDeHelpButtonIconKey(EsDeHelpButton.START, EsDeControllerFamily.PS4))
        assertEquals("button_start_PS5", esDeHelpButtonIconKey(EsDeHelpButton.START, EsDeControllerFamily.PS5))
        assertEquals("button_start_SNES", esDeHelpButtonIconKey(EsDeHelpButton.START, EsDeControllerFamily.SNES))
    }

    @Test
    fun `an unknown family setting falls back to ES-DE's own xbox default`() {
        assertEquals(EsDeControllerFamily.XBOX, EsDeControllerFamily.of(null))
        assertEquals(EsDeControllerFamily.XBOX, EsDeControllerFamily.of("dualshock"))
        assertEquals(EsDeControllerFamily.PS5, EsDeControllerFamily.of("ps5"))
    }
}
