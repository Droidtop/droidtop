package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertTrue
import org.junit.Test

/** First setup's layout question (Droidtop/tracker#903): Automatic clears what any preset sets. */
class KeyboardLayoutChoiceTest {
    @Test fun automaticClearsEveryOptionAPresetWrites() {
        val keys = KeyboardProMode.presetKeys()
        KeyboardProMode.Preset.entries.forEach { preset -> assertTrue(keys.containsAll(preset.values.keys)) }
        assertTrue("pref_keyboard_mode_landscape" in keys)
        assertTrue("pref_keyboard_layout" in keys)
    }
}
