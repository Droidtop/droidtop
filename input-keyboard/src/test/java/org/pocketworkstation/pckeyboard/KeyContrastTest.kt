package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Text on a key reads at 4.5:1 or better, hints at 3:1, in every look (Droidtop/tracker#903 H12b). */
class KeyContrastTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun colors(ground: Int, key: Int, focused: Int, text: Int, muted: Int, accent: Int) =
        KeyboardColors(ground = ground, panel = ground, key = key, keyFocused = focused, text = text, muted = muted, accent = accent)

    @Test fun luminanceAndRatioFollowWcag() {
        assertEquals(0.0, KeyContrast.luminance(black), 1e-9)
        assertEquals(1.0, KeyContrast.luminance(white), 1e-9)
        assertEquals(21.0, KeyContrast.ratio(black, white), 1e-9)
        assertEquals(1.0, KeyContrast.ratio(0xFF336699.toInt(), 0xFF336699.toInt()), 1e-9)
        // #767676 is the lightest grey that reads at 4.5:1 on white, #777777 just misses (a well-known pair).
        assertTrue(KeyContrast.ratio(0xFF767676.toInt(), white) >= 4.5)
        assertTrue(KeyContrast.ratio(0xFF777777.toInt(), white) < 4.5)
    }

    @Test fun alphaIsLaidOverWhatIsBehind() {
        assertEquals(white, KeyContrast.over(white, black))
        // Half-transparent white over black is mid grey.
        assertEquals(0xFF808080.toInt(), KeyContrast.over(0x80FFFFFF.toInt(), black))
        // A label at half alpha reads less than the same label opaque.
        assertTrue(KeyContrast.onPlate(0x80FFFFFF.toInt(), black, black) < KeyContrast.onPlate(white, black, black))
    }

    @Test fun aPaletteThatAlreadyReadsComesBackUnchanged() {
        val good = colors(0xFF202124.toInt(), 0xFF3C4043.toInt(), 0xFF5F6368.toInt(), white, 0xFFAAACB0.toInt(), 0xFF1A73E8.toInt())
        assertTrue(KeyContrast.passes(good))
        assertSame(good, KeyContrast.readable(good))
    }

    @Test fun greyOnGreyIsMadeReadable() {
        val bad = colors(0xFF606060.toInt(), 0xFF808080.toInt(), 0xFF909090.toInt(), 0xFFA0A0A0.toInt(), 0xFF888888.toInt(), 0xFF8080FF.toInt())
        val fixed = KeyContrast.readable(bad)
        assertTrue(KeyContrast.worstText(fixed) >= KeyContrast.TEXT)
        assertTrue(KeyContrast.worstHint(fixed) >= KeyContrast.HINT)
        // The ground and panel are never touched.
        assertEquals(bad.ground, fixed.ground)
        assertEquals(bad.panel, fixed.panel)
    }

    @Test fun whiteOnAPaleKeyTurnsDark() {
        val bad = colors(0xFFFFF59D.toInt(), 0xFFFFEB3B.toInt(), 0xFFFDD835.toInt(), white, 0xFFEEEEEE.toInt(), 0xFFFFC107.toInt())
        val fixed = KeyContrast.readable(bad)
        assertTrue(KeyContrast.passes(fixed))
        assertTrue(KeyContrast.luminance(fixed.text) < 0.2)
    }

    @Test fun aPaleAccentOrPressedShadeMovesAwayFromTheLabel() {
        // White text on a dark key reads, but a near-white accent and pressed shade would not.
        val bad = colors(0xFF101010.toInt(), 0xFF202020.toInt(), 0xFFE0E0E0.toInt(), white, 0xFFC0C0C0.toInt(), 0xFFF0F0F0.toInt())
        val fixed = KeyContrast.readable(bad)
        assertTrue(KeyContrast.passes(fixed))
        assertEquals(white, fixed.text)
        assertEquals(bad.key, fixed.key)
    }

    @Test fun anyPaletteAtAllIsMadeReadable() {
        val random = Random(903)
        fun c() = (0xFF shl 24) or random.nextInt(0x1000000)
        repeat(5000) {
            val fixed = KeyContrast.readable(colors(c(), c(), c(), c(), c(), c()))
            assertTrue("text ${KeyContrast.worstText(fixed)} in $fixed", KeyContrast.worstText(fixed) >= KeyContrast.TEXT)
            assertTrue("hint ${KeyContrast.worstHint(fixed)} in $fixed", KeyContrast.worstHint(fixed) >= KeyContrast.HINT)
        }
    }
}
