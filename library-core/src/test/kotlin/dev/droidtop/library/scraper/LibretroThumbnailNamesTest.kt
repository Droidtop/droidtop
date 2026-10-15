package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure half of [LibretroThumbnails]: the repository's file name for a title and the playlist name of a system. */
class LibretroThumbnailNamesTest {
    @Test
    fun `characters the repository forbids become underscores`() {
        assertEquals("AC_DC_ Live_", LibretroThumbnails.thumbnailName("AC/DC: Live?"))
        assertEquals("a_b_c_d_e_f_g_h_i_j_k_l", LibretroThumbnails.thumbnailName("a&b*c/d:e`f<g>h?i\\j|k\"l"))
    }

    @Test
    fun `a title with nothing forbidden is unchanged`() {
        assertEquals("Sonic the Hedgehog (USA)", LibretroThumbnails.thumbnailName("Sonic the Hedgehog (USA)"))
    }

    @Test
    fun `systems map to their playlist names and unknown ones to null`() {
        assertEquals("DOS", LibretroThumbnails.systemNameFor("dos"))
        assertEquals("SNK - Neo Geo Pocket Color", LibretroThumbnails.systemNameFor("ngpc"))
        assertNull(LibretroThumbnails.systemNameFor("nonesuch"))
    }
}
