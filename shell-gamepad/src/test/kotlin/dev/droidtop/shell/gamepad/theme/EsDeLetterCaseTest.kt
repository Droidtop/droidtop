package dev.droidtop.shell.gamepad.theme

import dev.droidtop.library.theme.EsDeLetterCase
import dev.droidtop.library.theme.applyTo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real `letterCase` has four values, not one (TextComponent.cpp:643-658).
 * `datetime` inherits the same property through TextComponent's own
 * applyTheme (DateTimeComponent.cpp:341).
 */
class EsDeLetterCaseTest {
    @Test
    fun `all four real values parse`() {
        assertEquals(EsDeLetterCase.UPPERCASE, esDeLetterCaseOf("uppercase"))
        assertEquals(EsDeLetterCase.LOWERCASE, esDeLetterCaseOf("lowercase"))
        assertEquals(EsDeLetterCase.CAPITALIZE, esDeLetterCaseOf("capitalize"))
        assertEquals(EsDeLetterCase.NONE, esDeLetterCaseOf("none"))
    }

    @Test
    fun `an unknown value keeps none, as ES-DE does after warning`() {
        assertEquals(EsDeLetterCase.NONE, esDeLetterCaseOf("Capitalize"))
        assertEquals(EsDeLetterCase.NONE, esDeLetterCaseOf(null))
    }

    @Test
    fun `capitalize is what decaffe's metadata values declare`() {
        assertEquals("Sega Of America", esDeLetterCaseOf("capitalize").applyTo("sega of america"))
        assertEquals("SEGA OF AMERICA", esDeLetterCaseOf("uppercase").applyTo("sega of america"))
        assertEquals("sega of america", esDeLetterCaseOf("lowercase").applyTo("SEGA OF AMERICA"))
        assertEquals("sega of America", esDeLetterCaseOf("none").applyTo("sega of America"))
    }
}
