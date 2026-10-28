package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#337: a short screen with pro mode off, nothing removed, a reset that keeps the person's own content. */
class KeyboardProModeTest {

    @Test
    fun theShortScreenKeepsTheCommonOptionsAndTheModeItself() {
        for (key in listOf("pref_pro_mode", "pref_reset_modern", "settings_height_portrait", "pref_screen_view", "pref_screen_languages", "show_suggestions", "pref_emoji", "pref_dictionaries", "pref_glide", "pref_offline_voice")) {
            assertTrue(key, KeyboardProMode.keepsInSimple(key))
        }
    }

    @Test
    fun hackersKeyboardsDeeperOptionsWaitForProMode() {
        for (key in listOf("pref_chording_ctrl_key", "pref_slide_keys_int", "pref_popup_content", "fullscreen_override", "pref_touch_pos", "pref_macros", null)) {
            assertFalse(key ?: "a preference with no key", KeyboardProMode.keepsInSimple(key))
        }
    }

    @Test
    fun anInstallThatAlreadyHasSettingsStartsInProMode() {
        assertTrue(KeyboardProMode.initialFor(setOf("pref_keyboard_layout")))
        assertFalse(KeyboardProMode.initialFor(emptySet()))
        assertFalse(KeyboardProMode.initialFor(setOf("pref_pro_mode")))
    }

    @Test
    fun aResetKeepsTheModeAndTheMacros() {
        assertEquals(
            setOf("pref_keyboard_layout", "auto_cap"),
            KeyboardProMode.resetKeys(setOf("pref_keyboard_layout", "auto_cap", "pref_pro_mode", "pref_macros")),
        )
    }

    @Test
    fun presetsSetOnlyOrdinaryOptionsThatTheScreensDeclare() {
        val declaredHere = setOf(
            "pref_keyboard_mode_portrait", "pref_keyboard_mode_landscape", "settings_height_landscape", "auto_cap",
            "show_suggestions", "suggestions_in_landscape", "auto_complete", "quick_fixes", "connectbot_tab_hack",
            "pref_keyboard_layout", "pref_glide",
        )
        for (preset in KeyboardProMode.Preset.entries) {
            assertTrue(preset.name, declaredHere.containsAll(preset.values.keys))
            assertFalse(preset.name, preset.values.keys.any { it in KeyboardProMode.KEPT_ON_RESET })
        }
        assertEquals("2", KeyboardProMode.Preset.TERMINAL.values["pref_keyboard_mode_landscape"])
        assertEquals(false, KeyboardProMode.Preset.TERMINAL.values["auto_cap"])
        assertEquals(false, KeyboardProMode.Preset.TERMINAL.values["pref_glide"])
    }
}
