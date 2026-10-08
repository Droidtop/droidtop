package dev.droidtop.runtime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Typing on the add-on display (docs/SPEC.md 4c, Droidtop/tracker#314). */
class AddonKeyboardRulesTest {
    @Test
    fun `Android draws a keyboard on the built-in display and on displays set local, nowhere else`() {
        assertTrue(AddonKeyboardRules.androidDrawsKeyboard(0, emptySet()))
        assertFalse(AddonKeyboardRules.androidDrawsKeyboard(15, emptySet()))
        assertTrue(AddonKeyboardRules.androidDrawsKeyboard(15, setOf(15)))
    }

    @Test
    fun `droidtop's own fields draw their keyboard only where Android will not`() {
        assertFalse(AddonKeyboardRules.ownFieldNeedsKeyboard(0, emptySet()))
        assertTrue(AddonKeyboardRules.ownFieldNeedsKeyboard(15, emptySet()))
        assertFalse(AddonKeyboardRules.ownFieldNeedsKeyboard(15, setOf(15)))
        assertFalse(AddonKeyboardRules.ownFieldNeedsKeyboard(null, emptySet()))
    }

    @Test
    fun `typing goes through the input method, then accessibility, then the elevated helper, and nothing is none`() {
        assertEquals(TypingRoute.DROIDTOP_IME, AddonKeyboardRules.route(true, true, true))
        assertEquals(TypingRoute.ACCESSIBILITY, AddonKeyboardRules.route(false, true, true))
        assertEquals(TypingRoute.ELEVATED_INPUT, AddonKeyboardRules.route(false, false, true))
        assertEquals(TypingRoute.NONE, AddonKeyboardRules.route(false, false, false))
    }

    @Test
    fun `one overlay at most, only off the keyboard's screens, the input method's first when it may draw`() {
        fun owner(display: Int?, local: Set<Int> = emptySet(), ime: Boolean = false, overlay: Boolean = false, a11y: Boolean = false) =
            AddonKeyboardRules.overlayOwner(display, local, ime, overlay, a11y)
        assertEquals(OverlayOwner.INPUT_METHOD, owner(15, ime = true, overlay = true, a11y = true))
        assertEquals(OverlayOwner.ACCESSIBILITY, owner(15, ime = true, overlay = false, a11y = true))
        assertEquals(OverlayOwner.ACCESSIBILITY, owner(15, ime = false, overlay = true, a11y = true))
        assertEquals(OverlayOwner.NONE, owner(15, ime = true, overlay = false, a11y = false))
        assertEquals(OverlayOwner.NONE, owner(15, ime = false, overlay = true, a11y = false))
        assertEquals(OverlayOwner.NONE, owner(15, local = setOf(15), ime = true, overlay = true, a11y = true))
        assertEquals(OverlayOwner.NONE, owner(0, ime = true, overlay = true, a11y = true))
        assertEquals(OverlayOwner.NONE, owner(null, ime = true, overlay = true, a11y = true))
    }

    @Test
    fun `the Displays row names the keyboard apps there get`() {
        assertEquals(AppsKeyboard.ANDROID, AddonKeyboardRules.appsKeyboard(true, false, false, false))
        assertEquals(AppsKeyboard.DROIDTOP, AddonKeyboardRules.appsKeyboard(false, true, true, true))
        assertEquals(AppsKeyboard.ACCESSIBILITY, AddonKeyboardRules.appsKeyboard(false, true, false, true))
        assertEquals(AppsKeyboard.ACCESSIBILITY, AddonKeyboardRules.appsKeyboard(false, false, false, true))
        assertEquals(AppsKeyboard.NEEDS_OVERLAY, AddonKeyboardRules.appsKeyboard(false, true, false, false))
        assertEquals(AppsKeyboard.OFF, AddonKeyboardRules.appsKeyboard(false, false, true, false))
    }

    @Test
    fun `the plan sets every second display local while on, gives back what it changed when off, and waits without access`() {
        val applied = mapOf(15 to DisplayImePolicy.FALLBACK_DISPLAY)
        assertEquals(setOf(15, 16), AddonKeyboardRules.plan(true, true, setOf(15, 16), applied).setLocal)
        assertEquals(emptyMap<Int, DisplayImePolicy>(), AddonKeyboardRules.plan(true, true, setOf(15), applied).restore)
        val off = AddonKeyboardRules.plan(false, true, setOf(15), applied)
        assertEquals(emptySet<Int>(), off.setLocal)
        assertEquals(applied, off.restore)
        val none = AddonKeyboardRules.plan(true, false, setOf(15), applied)
        assertEquals(emptySet<Int>(), none.setLocal)
        assertEquals(emptyMap<Int, DisplayImePolicy>(), none.restore)
    }

    @Test
    fun `input commands name the display, escape spaces and drop what input text cannot type`() {
        assertEquals(listOf("input", "-d", "15", "keyevent", "67"), AddonKeyboardRules.inputKeyArgv(15, 67))
        assertEquals(listOf("input", "-d", "15", "text", "hi%sthere"), AddonKeyboardRules.inputTextArgv(15, "hi there"))
        assertEquals(listOf("input", "-d", "15", "text", "ab"), AddonKeyboardRules.inputTextArgv(15, "aéb"))
        assertNull(AddonKeyboardRules.inputTextArgv(15, "é"))
    }

    @Test
    fun `the applied record round-trips and skips anything malformed`() {
        val applied = mapOf(16 to DisplayImePolicy.HIDE, 15 to DisplayImePolicy.FALLBACK_DISPLAY)
        assertEquals("15:1,16:2", AddonKeyboardRules.formatApplied(applied))
        assertEquals(applied, AddonKeyboardRules.parseApplied("15:1,16:2"))
        assertEquals(mapOf(15 to DisplayImePolicy.LOCAL), AddonKeyboardRules.parseApplied("15:0,x:1,16:9,,17"))
        assertEquals(emptyMap<Int, DisplayImePolicy>(), AddonKeyboardRules.parseApplied(null))
    }

    @Test
    fun `the policy tool's answer is read from its policy line`() {
        assertEquals(DisplayImePolicy.LOCAL, AddonKeyboardRules.parsePolicyLine("noise\npolicy=0\n"))
        assertNull(AddonKeyboardRules.parsePolicyLine("error: java.lang.SecurityException"))
    }
}
