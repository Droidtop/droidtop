package dev.droidtop.library.theme

import dev.droidtop.library.settings.AppearancePrefs
import dev.droidtop.notices.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colours outside Gaming (docs/SPEC.md 7k, "Colours outside Gaming"; Droidtop/tracker#827). */
class OutsideGamingColorsTest {
    private val themed = GamingThemeColors.DEFAULT.copy(ground = 0xFF2A1A10.toInt(), accent = 0xFFE0A030.toInt(), declared = true)
    private val blue = OutsideGamingColors.ACCENTS.first()
    private val violet = OutsideGamingColors.accentOf("violet")

    @Test
    fun `gaming theme is the stored theme colours as they are`() {
        assertSame(themed, OutsideGamingColors.choose(AppearancePrefs.GAMING_THEME, themed, null, violet, light = false, highContrast = false))
    }

    @Test
    fun `a choice that is not custom matches the gaming theme`() {
        assertSame(themed, OutsideGamingColors.choose("something else", themed, null, violet, light = true, highContrast = true))
    }

    @Test
    fun `custom ignores the gaming theme and is droidtop's own palette`() {
        val chosen = OutsideGamingColors.choose(AppearancePrefs.CUSTOM, themed, null, blue, light = false, highContrast = false)
        assertEquals(GamingThemeColors.DEFAULT, chosen)
        val light = OutsideGamingColors.choose(AppearancePrefs.CUSTOM, themed, null, blue, light = true, highContrast = false)
        assertEquals(GamingThemeMapping.defaultFor(light = true), light)
    }

    @Test
    fun `droidtop blue comes first and is the default accent`() {
        assertEquals("blue", blue.id)
        assertEquals(0xFF3BB5FF.toInt(), blue.argb)
        assertEquals(blue, OutsideGamingColors.accentOf(null))
        assertEquals(blue, OutsideGamingColors.accentOf("no such accent"))
    }

    @Test
    fun `another accent keeps droidtop's ground and text and changes what the accent decides`() {
        val custom = OutsideGamingColors.custom(violet, light = false, highContrast = false)
        val base = GamingThemeColors.DEFAULT
        assertEquals(base.ground, custom.ground)
        assertEquals(base.onSurface, custom.onSurface)
        assertNotEquals(base.accent, custom.accent)
        assertEquals(ColorMath.withAlpha(custom.accent, 0x38), custom.rowFillSelected)
        assertTrue(custom.declared)
    }

    @Test
    fun `every accent stays readable on droidtop's light and dark grounds`() {
        for (accent in OutsideGamingColors.ACCENTS) {
            for (light in listOf(false, true)) {
                val c = OutsideGamingColors.custom(accent, light, highContrast = false)
                assertTrue("${accent.id} light=$light", ColorMath.ratio(c.accent, c.ground) >= GamingThemeMapping.TEXT_FLOOR - 0.01)
            }
        }
    }

    @Test
    fun `high contrast applies to the custom theme too`() {
        val plain = OutsideGamingColors.custom(violet, light = false, highContrast = false)
        assertEquals(GamingThemeMapping.highContrast(plain), OutsideGamingColors.custom(violet, light = false, highContrast = true))
    }

    @Test
    fun `device colours are the mapped system palette`() {
        val device = GamingThemeMapping.fromDevicePalette(DevicePaletteFixture.palette, light = false)
        assertSame(device, OutsideGamingColors.choose(AppearancePrefs.DEVICE, themed, device, violet, light = false, highContrast = false))
    }

    @Test
    fun `device colours with no system palette fall back to the gaming theme`() {
        assertSame(themed, OutsideGamingColors.choose(AppearancePrefs.DEVICE, themed, null, violet, light = false, highContrast = false))
    }

    @Test
    fun `the tint is the active palette's accent only while it is on and the source is not the gaming theme`() {
        val active = OutsideGamingColors.custom(violet, light = false, highContrast = false)
        assertEquals(active.accent, OutsideGamingColors.tintAccent(AppearancePrefs.CUSTOM, tintOn = true, active = active))
        assertEquals(active.accent, OutsideGamingColors.tintAccent(AppearancePrefs.DEVICE, tintOn = true, active = active))
        assertNull(OutsideGamingColors.tintAccent(AppearancePrefs.CUSTOM, tintOn = false, active = active))
        assertNull(OutsideGamingColors.tintAccent(AppearancePrefs.GAMING_THEME, tintOn = true, active = active))
        assertNull(OutsideGamingColors.tintAccent(AppearancePrefs.DEVICE, tintOn = true, active = null))
    }
}
