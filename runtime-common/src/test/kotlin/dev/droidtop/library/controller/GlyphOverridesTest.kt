package dev.droidtop.library.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The per-pad button labels (Droidtop/tracker#723): glyphs change, the key map does not. */
class GlyphOverridesTest {
    @Test
    fun `stored overrides read back, and nonsense is dropped`() {
        val map = mapOf("pad-a" to GlyphFamily.PLAYSTATION, "pad-b" to GlyphFamily.NINTENDO)
        assertEquals(map, GlyphOverrides.parse(GlyphOverrides.format(map)))
        assertEquals(mapOf("pad-a" to GlyphFamily.XBOX), GlyphOverrides.parse("pad-a=xbox|pad-c=sega|=nintendo"))
        assertEquals(emptyMap<String, GlyphFamily>(), GlyphOverrides.parse(null))
    }

    @Test
    fun `an override changes only the family, never which key confirms`() {
        val resolved = FaceLayout.forFamily(GlyphFamily.NINTENDO, LayoutSource.CONSOLE)
        val relabelled = GlyphOverrides.apply(resolved, GlyphFamily.XBOX)
        assertEquals(GlyphFamily.XBOX, relabelled.family)
        assertEquals(resolved.confirmOnRight, relabelled.confirmOnRight)
        assertEquals(resolved.keysSwapped, relabelled.keysSwapped)
        assertSame(resolved, GlyphOverrides.apply(resolved, null))
    }
}
