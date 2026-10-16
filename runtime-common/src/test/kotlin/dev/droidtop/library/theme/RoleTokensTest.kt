package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The role tokens plugin-drawn UI reads (docs/SPEC.md 7k "Light and dark", Droidtop/tracker#559). */
class RoleTokensTest {
    @Test
    fun `every token comes from the palette, in the order SPEC gives`() {
        val dark = RoleTokens.of(GamingThemeColors.DEFAULT)
        assertFalse(dark.light)
        assertEquals(RoleTokens.NAMES, dark.colors.keys.toList())
        val c = GamingThemeColors.DEFAULT
        assertEquals(c.ground, dark.color("ground"))
        assertEquals(c.overlaySurface, dark.color("surface"))
        assertEquals(c.cardFocused, dark.color("surfaceVariant"))
        assertEquals(c.onSurface, dark.color("onSurface"))
        assertEquals(c.accent, dark.color("primary"))
        assertEquals("the focus ring's outer edge is the accent", c.accent, dark.color("focus"))
        assertEquals(FocusRingInks.inner(c.accent), dark.color("focusInner"))
        assertEquals(c.noticeColors().dangerInk, dark.color("danger"))
    }

    @Test
    fun `droidtop's own light palette reads as light`() {
        assertTrue(RoleTokens.of(GamingThemeMapping.defaultFor(light = true)).light)
    }

    @Test
    fun `colours are written as RRGGBB without alpha`() {
        assertEquals("#101418", RoleTokens.hex(0xFF101418.toInt()))
        assertEquals("#FFFFFF", RoleTokens.hex(0x80FFFFFF.toInt()))
    }
}
