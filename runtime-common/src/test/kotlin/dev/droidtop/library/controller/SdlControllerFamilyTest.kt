package dev.droidtop.library.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [SdlControllerIds.familyOf]: the glyph family SDL's table gives a vendor and product id, null when it lists none. */
class SdlControllerFamilyTest {
    @Test
    fun `listed pads map to their family`() {
        assertEquals(GlyphFamily.XBOX, SdlControllerIds.familyOf(0x045e, 0x028e))
        assertEquals(GlyphFamily.PLAYSTATION, SdlControllerIds.familyOf(0x054c, 0x0268))
        assertEquals(GlyphFamily.NINTENDO, SdlControllerIds.familyOf(0x057e, 0x2006))
    }

    @Test
    fun `an id the table does not list has no family`() {
        assertNull(SdlControllerIds.familyOf(0, 0))
        assertNull(SdlControllerIds.familyOf(0x045e, 0xffff))
    }
}
