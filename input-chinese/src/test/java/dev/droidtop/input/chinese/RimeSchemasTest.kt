package dev.droidtop.input.chinese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pocketworkstation.pckeyboard.InputEngine

/** Droidtop/tracker#903 H8b: which schema and forms each Chinese input language gets, and how keys reach librime. */
class RimeSchemasTest {
    private val simp = RimeSchemas.Choice("luna_pinyin_simp", "zh_hant", simplifiedFirst = true)
    private val tw = RimeSchemas.Choice("luna_pinyin_tw", "zh_hant_tw", simplifiedFirst = false)
    private val hk = RimeSchemas.Choice("luna_pinyin_tw", "zh_hant_hk", simplifiedFirst = false)

    @Test fun `mainland and plain Chinese type simplified, Taiwan and Hong Kong traditional`() {
        assertEquals(simp, RimeSchemas.forLanguage("zh"))
        assertEquals(simp, RimeSchemas.forLanguage("zh_CN"))
        assertEquals(simp, RimeSchemas.forLanguage("zh-SG"))
        assertEquals(simp, RimeSchemas.forLanguage("zh_Hans_CN"))
        assertEquals(tw, RimeSchemas.forLanguage("zh_TW"))
        assertEquals(tw, RimeSchemas.forLanguage("zh-Hant"))
        assertEquals(tw, RimeSchemas.forLanguage("zh_Hant_TW"))
        assertEquals(hk, RimeSchemas.forLanguage("zh_HK"))
        assertEquals(hk, RimeSchemas.forLanguage("zh-Hant-MO"))
        assertNull(RimeSchemas.forLanguage("ja"))
        assertNull(RimeSchemas.forLanguage("en_US"))
        assertNull(RimeSchemas.forLanguage(""))
    }

    @Test fun `RIME serves every Chinese input language and nothing else`() {
        for (language in listOf("zh", "zh_CN", "zh_TW", "zh-HK", "ZH_cn")) assertTrue(language, RimeSchemas.serves(language))
        for (language in listOf("ja", "ko", "en", "zhx", "")) assertFalse(language, RimeSchemas.serves(language))
    }

    @Test fun `every schema a language gets is one the keyboard checks before it is ready`() {
        for (language in listOf("zh", "zh_CN", "zh_TW", "zh_HK")) {
            assertTrue(language, RimeSchemas.forLanguage(language)!!.schema in RimeSchemas.SCHEMAS)
        }
    }

    @Test fun `a form sets its option and clears the rest of the radio group`() {
        assertEquals(listOf("zh_hant" to false, "zh_hans" to true, "zh_hant_hk" to false, "zh_hant_tw" to false), RimeSchemas.formOptions(simp, simplified = true))
        assertEquals(listOf("zh_hant" to true, "zh_hans" to false, "zh_hant_hk" to false, "zh_hant_tw" to false), RimeSchemas.formOptions(simp, simplified = false))
        assertEquals(listOf("zh_hant" to false, "zh_hans" to false, "zh_hant_hk" to false, "zh_hant_tw" to true), RimeSchemas.formOptions(tw, simplified = false))
        assertEquals(listOf("zh_hant" to false, "zh_hans" to false, "zh_hant_hk" to true, "zh_hant_tw" to false), RimeSchemas.formOptions(hk, simplified = false))
    }

    @Test fun `characters are their keysyms, Latin-1 as themselves and the rest as X11's Unicode keysyms`() {
        assertEquals('a'.code, RimeKeys.forChar('a'.code))
        assertEquals(','.code, RimeKeys.forChar(','.code))
        assertEquals(0xe9, RimeKeys.forChar(0xe9))
        assertEquals(0x01004f60, RimeKeys.forChar(0x4f60))
        assertEquals(0x01020bb7, RimeKeys.forChar(0x20bb7))
        assertEquals(RimeKeys.RETURN, RimeKeys.forChar('\n'.code))
        assertEquals(RimeKeys.TAB, RimeKeys.forChar('\t'.code))
        assertNull(RimeKeys.forChar(0x7f))
        assertNull(RimeKeys.forChar(0x1b))
        assertNull(RimeKeys.forChar(0xd800))
    }

    @Test fun `engine keys are librime's keysyms, and the ones it has no counterpart for are left to the keyboard`() {
        assertEquals(RimeKeys.Press(0x20), RimeKeys.forKey(InputEngine.Key.SPACE))
        assertEquals(RimeKeys.Press(0xff0d), RimeKeys.forKey(InputEngine.Key.ENTER))
        assertEquals(RimeKeys.Press(0xff51), RimeKeys.forKey(InputEngine.Key.LEFT))
        assertEquals(RimeKeys.Press(0xff53), RimeKeys.forKey(InputEngine.Key.RIGHT))
        assertEquals(RimeKeys.Press(0xff52), RimeKeys.forKey(InputEngine.Key.UP))
        assertEquals(RimeKeys.Press(0xff54), RimeKeys.forKey(InputEngine.Key.DOWN))
        assertEquals(RimeKeys.Press(0xff51, 1), RimeKeys.forKey(InputEngine.Key.SHIFT_LEFT))
        assertEquals(RimeKeys.Press(0xff53, 1), RimeKeys.forKey(InputEngine.Key.SHIFT_RIGHT))
        assertEquals(RimeKeys.Press(0xff1b), RimeKeys.forKey(InputEngine.Key.ESCAPE))
        assertNull(RimeKeys.forKey(InputEngine.Key.UNDO))
        assertNull(RimeKeys.forKey(InputEngine.Key.TOGGLE))
    }
}
