package dev.droidtop.library.theme

import dev.droidtop.notices.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A made-up system palette: a blue accent family and a grey neutral one, lightest tone first. */
object DevicePaletteFixture {
    val accent1 = listOf(
        0xFFFFFFFF, 0xFFF0F4FF, 0xFFE1EAFF, 0xFFC4D6FF, 0xFFA6C2FF, 0xFF88ADFF, 0xFF6A99FF,
        0xFF4C84F0, 0xFF3A6BD0, 0xFF2A52A8, 0xFF1C3A80, 0xFF102458, 0xFF000000,
    ).map { it.toInt() }
    val neutral1 = listOf(
        0xFFFFFFFF, 0xFFF6F6F8, 0xFFEDEEF1, 0xFFDFE0E4, 0xFFC4C5CA, 0xFFA8A9AF, 0xFF8D8E94,
        0xFF73747A, 0xFF5B5C61, 0xFF444549, 0xFF2E2F33, 0xFF1B1C1F, 0xFF000000,
    ).map { it.toInt() }
    val palette = GamingThemeMapping.DevicePalette(accent1, neutral1)
}

/** Device colours and the theme tint (docs/SPEC.md 7k, "Colours outside Gaming"; Droidtop/tracker#844). */
class ColourSourceMappingTest {
    private val p = DevicePaletteFixture.palette

    @Test
    fun `a light device palette takes its ground from the neutral family and its accent from the accent family`() {
        val c = GamingThemeMapping.fromDevicePalette(p, light = true)
        assertEquals(p.neutral(50), c.ground)
        assertTrue(c.isLight)
        assertTrue(ColorMath.ratio(c.onSurface, c.ground) >= GamingThemeMapping.TEXT_FLOOR - 0.01)
        assertTrue(ColorMath.ratio(c.accent, c.ground) >= GamingThemeMapping.TEXT_FLOOR - 0.01)
        assertEquals(ColorMath.withAlpha(p.accent(100), 0x99), c.rowFillSelected)
    }

    @Test
    fun `a dark device palette uses the dark tones`() {
        val c = GamingThemeMapping.fromDevicePalette(p, light = false)
        assertEquals(p.neutral(900), c.ground)
        assertTrue(!c.isLight)
        assertTrue(ColorMath.ratio(c.onSurface, c.ground) >= GamingThemeMapping.TEXT_FLOOR - 0.01)
        assertEquals(ColorMath.withAlpha(p.accent(700), 0x99), c.rowFillSelected)
        assertNotEquals(GamingThemeMapping.fromDevicePalette(p, light = true).accent, c.accent)
    }

    @Test
    fun `the tint changes only the accent and the highlight`() {
        val theme = GamingThemeColors.DEFAULT.copy(ground = 0xFF2A1A10.toInt(), accent = 0xFFE0A030.toInt(), declared = true)
        val tinted = GamingThemeMapping.tint(theme, 0xFFA67CFF.toInt())
        assertNotEquals(theme.accent, tinted.accent)
        assertEquals(ColorMath.withAlpha(tinted.accent, 0x38), tinted.rowFillSelected)
        assertEquals(
            theme,
            tinted.copy(accent = theme.accent, onAccent = theme.onAccent, rowFillSelected = theme.rowFillSelected),
        )
    }

    @Test
    fun `resolving with no tint is the theme as mapped`() {
        assertEquals(
            GamingThemeMapping.resolved(null, light = false, schemeNamed = false, highContrast = false),
            GamingThemeMapping.resolved(null, light = false, schemeNamed = false, highContrast = false, tintAccent = 0xFFA67CFF.toInt()),
        )
    }
}
