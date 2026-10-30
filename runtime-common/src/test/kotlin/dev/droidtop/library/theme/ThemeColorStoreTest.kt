package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The stored Gaming theme colours (docs/SPEC.md 7k, "Colours outside Gaming"; Droidtop/tracker#827). */
class ThemeColorStoreTest {
    private val key = ThemeColorStore.Key(
        theme = "es-de-decaffe", colorScheme = null, variant = "grid", aspectRatio = null, light = false, highContrast = false,
    )

    @Test
    fun `droidtop's own palette comes back as it was stored`() {
        val stored = ThemeColorStore.encode(GamingThemeColors.DEFAULT)
        assertEquals(GamingThemeColors.DEFAULT, ThemeColorStore.decode(stored))
    }

    @Test
    fun `a theme's colours come back with their fonts and texture`() {
        val colors = GamingThemeMapping.defaultFor(light = true).copy(
            accent = 0xFFE0A030.toInt(),
            bodyFontPath = "/data/themes/a b/font.ttf",
            displayFontPath = null,
            textureImagePath = "/data/themes/a \"b\"/bg.png",
            declared = true,
        )
        assertEquals(colors, ThemeColorStore.decode(ThemeColorStore.encode(colors)))
    }

    @Test
    fun `nothing stored or something unreadable is no colours`() {
        assertNull(ThemeColorStore.decode(null))
        assertNull(ThemeColorStore.decode(""))
        assertNull(ThemeColorStore.decode("{\"ground\":1}"))
        assertNull(ThemeColorStore.decode("not json"))
    }

    @Test
    fun `equal keys are stored alike`() {
        assertEquals(key.encode(), key.copy().encode())
    }

    @Test
    fun `everything the colours depend on changes the key`() {
        val variants = listOf(
            key.copy(theme = "es-de-slate"),
            key.copy(colorScheme = "dark"),
            key.copy(variant = null),
            key.copy(aspectRatio = "16:9"),
            key.copy(light = true),
            key.copy(highContrast = true),
        )
        variants.forEach { assertNotEquals(key.encode(), it.encode()) }
        assertEquals(variants.size, variants.map { it.encode() }.toSet().size)
    }

    @Test
    fun `no theme and a theme named null are different keys`() {
        assertNotEquals(key.copy(theme = null).encode(), key.copy(theme = "null").encode())
    }
}
