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
    fun `a named theme scheme is on the row by its own label`() {
        val labels = mapOf("dark-shots" to "Dark [Screenshots]")
        assertEquals("Theme keeps Dark [Screenshots]", AppearancePrefs.schemeChip(AppearancePrefs.schemeLabel("dark-shots", labels)))
        // A scheme the theme gave no label is named by its id rather than not at all.
        assertEquals("Theme keeps night", AppearancePrefs.schemeChip(AppearancePrefs.schemeLabel("night", labels)))
    }

    @Test
    fun `automatic puts nothing on the row`() {
        assertNull(AppearancePrefs.schemeChip(AppearancePrefs.schemeLabel(null, mapOf("a" to "A"))))
    }
}
