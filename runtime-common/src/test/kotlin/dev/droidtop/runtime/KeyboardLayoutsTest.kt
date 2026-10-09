package dev.droidtop.runtime

import dev.droidtop.runtime.KeyboardLayouts.XkbLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardLayoutsTest {
    @Test
    fun `a language tag finds its layout, region first`() {
        assertEquals(XkbLayout("de"), KeyboardLayouts.fromLanguageTag("de-DE"))
        assertEquals(XkbLayout("ch"), KeyboardLayouts.fromLanguageTag("de-CH"))
        assertEquals(XkbLayout("ch", "fr"), KeyboardLayouts.fromLanguageTag("fr-CH"))
        assertEquals(XkbLayout("gb"), KeyboardLayouts.fromLanguageTag("en-GB"))
        assertEquals(XkbLayout("us"), KeyboardLayouts.fromLanguageTag("en-US"))
        assertEquals(XkbLayout("br"), KeyboardLayouts.fromLanguageTag("pt_BR"))
        assertEquals(XkbLayout("latam"), KeyboardLayouts.fromLanguageTag("es-419"))
        assertEquals(XkbLayout("jp"), KeyboardLayouts.fromLanguageTag("ja-JP"))
        assertNull(KeyboardLayouts.fromLanguageTag("xx-YY"))
        assertNull(KeyboardLayouts.fromLanguageTag(""))
    }

    private fun keys(vararg pairs: Pair<Int, Char>): (Int) -> Int {
        val map = pairs.toMap()
        return { code -> map[code]?.code ?: 0 }
    }

    @Test
    fun `a physical keyboard's character map names its layout`() {
        val q = android.view.KeyEvent.KEYCODE_Q
        val y = android.view.KeyEvent.KEYCODE_Y
        val semi = android.view.KeyEvent.KEYCODE_SEMICOLON
        val grave = android.view.KeyEvent.KEYCODE_GRAVE
        val backslash = android.view.KeyEvent.KEYCODE_BACKSLASH
        assertEquals(XkbLayout("us"), KeyboardLayouts.fromKeyCharacters(keys(q to 'q', y to 'y', semi to ';', backslash to '\\')))
        assertEquals(XkbLayout("gb"), KeyboardLayouts.fromKeyCharacters(keys(q to 'q', y to 'y', semi to ';', backslash to '#')))
        assertEquals(XkbLayout("de"), KeyboardLayouts.fromKeyCharacters(keys(q to 'q', y to 'z', semi to 'ö', grave to '^')))
        assertEquals(XkbLayout("ch"), KeyboardLayouts.fromKeyCharacters(keys(q to 'q', y to 'z', semi to 'ö', grave to '§')))
        assertEquals(XkbLayout("fr"), KeyboardLayouts.fromKeyCharacters(keys(q to 'a', y to 'y', semi to 'm')))
        assertEquals(XkbLayout("es"), KeyboardLayouts.fromKeyCharacters(keys(q to 'q', y to 'y', semi to 'ñ')))
        assertEquals(
            XkbLayout("ru", options = "grp:alt_shift_toggle"),
            KeyboardLayouts.fromKeyCharacters(keys(q to 'й', y to 'н')),
        )
        assertNull(KeyboardLayouts.fromKeyCharacters(keys()))
    }

    @Test
    fun `Android's physical layout type picks the variant`() {
        assertEquals(XkbLayout("us", "dvorak"), KeyboardLayouts.fromLanguageTag("en-US", "dvorak"))
        assertEquals(XkbLayout("tr", "f"), KeyboardLayouts.fromLanguageTag("tr", "turkish_f"))
        assertEquals(XkbLayout("de"), KeyboardLayouts.fromLanguageTag("de", "qwertz"))
    }

    @Test
    fun `a layout without Latin letters comes second to us, with a switch`() {
        val ru = KeyboardLayouts.fromLanguageTag("ru-RU")!!
        assertEquals(
            listOf("xkbcli", "compile-keymap", "--layout", "us,ru", "--options", "grp:alt_shift_toggle"),
            KeyboardLayouts.compileCommand(ru),
        )
        assertEquals(listOf("xkbcli", "compile-keymap", "--layout", "de"), KeyboardLayouts.compileCommand(XkbLayout("de")))
        assertEquals(
            listOf("xkbcli", "compile-keymap", "--layout", "ch", "--variant", "fr"),
            KeyboardLayouts.compileCommand(XkbLayout("ch", "fr")),
        )
    }

    @Test
    fun `stored ids round-trip and refuse anything that is not a layout name`() {
        assertEquals(XkbLayout("ch", "fr"), XkbLayout.fromId("ch:fr"))
        assertEquals("ch:fr", XkbLayout("ch", "fr").id)
        assertNull(XkbLayout.fromId("de; rm -rf /"))
        assertNull(XkbLayout.fromId("--layout"))
    }

    @Test
    fun `the layout list is the evdev lst layout section`() {
        val text = "! model\n  pc105           Generic 105-key PC\n\n! layout\n  us              English (US)\n  de              German\n\n! variant\n  dvorak          us: English (Dvorak)\n"
        assertEquals(listOf("us" to "English (US)", "de" to "German"), KeyboardLayouts.parseLayoutList(text))
    }
}
