package dev.droidtop.library.settings

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/SPEC.md 7k, "Light and dark" (Droidtop/tracker#559): the setting decides, and Follow Android reads night mode. */
class AppearancePrefsTest {
    private val night = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES
    private val day = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_NO

    @Test
    fun `light and dark are what they say whatever Android is in`() {
        assertTrue(AppearancePrefs.light(AppearancePrefs.LIGHT, night))
        assertFalse(AppearancePrefs.light(AppearancePrefs.DARK, day))
    }

    @Test
    fun `follow android is light unless android is in night mode`() {
        assertTrue(AppearancePrefs.light(AppearancePrefs.FOLLOW_ANDROID, day))
        assertFalse(AppearancePrefs.light(AppearancePrefs.FOLLOW_ANDROID, night))
        // No night mode given at all is not night.
        assertTrue(AppearancePrefs.light(AppearancePrefs.FOLLOW_ANDROID, Configuration.UI_MODE_TYPE_NORMAL))
    }

    @Test
    fun `a named theme scheme is said by its own label`() {
        val labels = mapOf("dark-shots" to "Dark [Screenshots]")
        assertEquals("Dark [Screenshots]", AppearancePrefs.schemeLabel("dark-shots", labels))
        // A scheme the theme gave no label is named by its id rather than not at all.
        assertEquals("night", AppearancePrefs.schemeLabel("night", labels))
    }

    @Test
    fun `automatic names no scheme`() {
        assertNull(AppearancePrefs.schemeLabel(null, mapOf("a" to "A")))
    }

    @Test
    fun `the colour source defaults to device colours only where there is a system palette`() {
        assertEquals(AppearancePrefs.DEVICE, AppearancePrefs.defaultSource(sdkInt = 31, hasPalette = true))
        assertEquals(AppearancePrefs.DEVICE, AppearancePrefs.defaultSource(sdkInt = 35, hasPalette = true))
        assertEquals(AppearancePrefs.GAMING_THEME, AppearancePrefs.defaultSource(sdkInt = 31, hasPalette = false))
        assertEquals(AppearancePrefs.GAMING_THEME, AppearancePrefs.defaultSource(sdkInt = 30, hasPalette = true))
        assertEquals(AppearancePrefs.GAMING_THEME, AppearancePrefs.defaultSource(sdkInt = 26, hasPalette = false))
    }

    @Test
    fun `the first version's stored choices keep their meaning`() {
        assertEquals(AppearancePrefs.GAMING_THEME, AppearancePrefs.sourceOf("match_gaming", sdkInt = 34, hasPalette = true))
        assertEquals(AppearancePrefs.CUSTOM, AppearancePrefs.sourceOf("custom", sdkInt = 34, hasPalette = true))
        assertEquals(AppearancePrefs.CUSTOM, AppearancePrefs.sourceOf("custom", sdkInt = 29, hasPalette = false))
    }

    @Test
    fun `nothing stored is the default and device colours need a palette`() {
        assertEquals(AppearancePrefs.DEVICE, AppearancePrefs.sourceOf(null, sdkInt = 34, hasPalette = true))
        assertEquals(AppearancePrefs.GAMING_THEME, AppearancePrefs.sourceOf("unknown", sdkInt = 29, hasPalette = false))
        assertEquals(AppearancePrefs.GAMING_THEME, AppearancePrefs.sourceOf("device", sdkInt = 29, hasPalette = false))
        assertEquals(AppearancePrefs.DEVICE, AppearancePrefs.sourceOf("device", sdkInt = 33, hasPalette = true))
    }
}
