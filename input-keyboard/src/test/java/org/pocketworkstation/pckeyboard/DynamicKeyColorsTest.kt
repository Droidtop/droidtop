package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.random.Random

/** Dynamic (wallpaper): the keyboard's roles from a Material You palette (Droidtop/tracker#903 H12b). */
class DynamicKeyColorsTest {

    /** A tone family like Android's: tone t at lightness L* = 100 - t / 10, tinted toward [hue] by [chroma]. */
    private fun family(hue: Int, chroma: Double): List<Int> = DynamicKeyColors.TONES.map { t ->
        val lStar = 100.0 - t / 10.0
        val y = if (lStar > 8) ((lStar + 16) / 116).pow(3) else lStar / 903.3
        val grey = encode(y)
        fun ch(shift: Int) = (grey + (((hue ushr shift) and 0xFF) - 128) * chroma * (1 - kotlin.math.abs(lStar - 50) / 50)).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun encode(y: Double): Int {
        val c = if (y <= 0.0031308) 12.92 * y else 1.055 * y.pow(1 / 2.4) - 0.055
        return (c * 255 + 0.5).toInt().coerceIn(0, 255)
    }

    private fun palette(hue: Int) = DynamicKeyColors.TonalPalette(family(hue, 0.6), family(hue, 0.05), family(hue, 0.12))

    private val hues = listOf(0xFF2196F3, 0xFFF44336, 0xFF4CAF50, 0xFFFFEB3B, 0xFF9C27B0, 0xFF795548, 0xFF9E9E9E).map { it.toInt() }

    @Test fun lightKeysAreDrawnFromTheBrightTonesAndDarkFromTheDeep() {
        val p = palette(hues.first())
        val light = DynamicKeyColors.derive(p, light = true)
        val dark = DynamicKeyColors.derive(p, light = false)
        assertEquals(p.neutral1[2], light.ground) // neutral1 50
        assertEquals(p.neutral1[0], light.key) // neutral1 0
        assertEquals(p.neutral1[11], dark.ground) // neutral1 900
        assertEquals(p.neutral2[10], dark.key) // neutral2 800
        assertTrue(KeyContrast.luminance(light.key) > KeyContrast.luminance(dark.key))
        assertNotEquals(light.accent, dark.accent)
    }

    @Test fun everyWallpaperGivesReadableKeysLightAndDark() {
        for (hue in hues) for (light in listOf(true, false)) {
            val c = DynamicKeyColors.derive(palette(hue), light)
            assertTrue("$hue $light ${KeyContrast.worstText(c)}", KeyContrast.worstText(c) >= KeyContrast.TEXT)
            assertTrue("$hue $light ${KeyContrast.worstHint(c)}", KeyContrast.worstHint(c) >= KeyContrast.HINT)
        }
    }

    @Test fun evenANonsensePaletteIsMadeReadable() {
        val random = Random(12)
        fun tones() = List(13) { (0xFF shl 24) or random.nextInt(0x1000000) }
        repeat(500) {
            for (light in listOf(true, false)) {
                assertTrue(KeyContrast.passes(DynamicKeyColors.derive(DynamicKeyColors.TonalPalette(tones(), tones(), tones()), light)))
            }
        }
    }

    @Test fun aPaletteWithEveryToneTheSameIsNotFilledIn() {
        val flat = List(13) { 0xFF888888.toInt() }
        assertFalse(DynamicKeyColors.TonalPalette(flat, flat, flat).filled)
        assertTrue(palette(hues.first()).filled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aFamilyHasThirteenTones() {
        DynamicKeyColors.TonalPalette(List(12) { 0 }, List(13) { 0 }, List(13) { 0 })
    }
}
