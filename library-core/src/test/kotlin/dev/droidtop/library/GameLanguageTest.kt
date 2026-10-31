package dev.droidtop.library

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A Windows game's own language and pad layout (docs/SPEC.md 7i, "Game properties"; Droidtop/tracker#228). */
class GameLanguageTest {

    @Test
    fun `a language is found by Steam's code and gives Wine its locale`() {
        assertEquals(GameLanguage.JAPANESE, GameLanguage.bySteam("japanese"))
        assertEquals(GameLanguage.KOREAN, GameLanguage.bySteam("KOREANA"))
        assertNull(GameLanguage.bySteam("klingon"))
        assertNull(GameLanguage.bySteam(null))
        assertEquals(mapOf("LANG" to "ja_JP.utf8", "LC_ALL" to "ja_JP.utf8"), GameLanguage.JAPANESE.environment)
    }

    @Test
    fun `the device's locale maps to Steam's language, English when Steam has none`() {
        assertEquals(GameLanguage.GERMAN, GameLanguage.of(Locale.GERMANY))
        assertEquals(GameLanguage.BRAZILIAN, GameLanguage.of(Locale("pt", "BR")))
        assertEquals(GameLanguage.TRADITIONAL_CHINESE, GameLanguage.of(Locale.TAIWAN))
        assertEquals(GameLanguage.LATAM, GameLanguage.of(Locale("es", "MX")))
        assertEquals(GameLanguage.ENGLISH, GameLanguage.of(Locale("xx")))
    }

    @Test
    fun `a launch carries the game's locale first, then the person's allowed variables`() {
        val settings = WineGameSettings(language = "japanese", environment = mapOf("DXVK_HUD" to "fps", "LD_PRELOAD" to "x.so"))
        assertEquals("LANG=ja_JP.utf8 LC_ALL=ja_JP.utf8 DXVK_HUD=fps", GameLaunchOptions.launchEnvironment(settings))
        assertEquals("DXVK_HUD=fps", GameLaunchOptions.launchEnvironment(settings.copy(language = null)))
        assertEquals("", GameLaunchOptions.launchEnvironment(null as WineGameSettings?))
    }

    @Test
    fun `a game with only a language is not empty`() {
        assertEquals(false, WineGameSettings(language = "french").isEmpty)
    }

    @Test
    fun `the Controls row steps through every layout and back to Gamepad`() {
        assertEquals(GamePadLayouts.KEYBOARD_MOUSE, GamePadLayouts.next(null))
        assertEquals(GamePadLayouts.MOUSE_ONLY, GamePadLayouts.next(GamePadLayouts.KEYBOARD_MOUSE))
        assertEquals(null, GamePadLayouts.next(GamePadLayouts.MOUSE_ONLY))
        assertEquals(GamePadLayouts.MOUSE_ONLY, GamePadLayouts.byId("mouse_only"))
        assertEquals(null, GamePadLayouts.byId(""))
    }
}
