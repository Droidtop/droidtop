package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A picture's role by its shape (docs/SPEC.md 7h, "Artwork roles", Droidtop/tracker#585), at Steam's own sizes. */
class ArtworkRolesTest {
    @Test
    fun `box art is a capsule, portrait or square`() {
        assertEquals(ArtworkRole.CAPSULE, ArtworkRoles.ofShape(600, 900))
        assertEquals(ArtworkRole.CAPSULE, ArtworkRoles.ofShape(512, 512))
        assertEquals(ArtworkRole.CAPSULE, ArtworkRoles.ofShape(1400, 1240))
    }

    @Test
    fun `a banner is a wide capsule, a very wide one a hero`() {
        assertEquals(ArtworkRole.WIDE_CAPSULE, ArtworkRoles.ofShape(920, 430))
        assertEquals(ArtworkRole.WIDE_CAPSULE, ArtworkRoles.ofShape(1920, 1080))
        assertEquals(ArtworkRole.WIDE_CAPSULE, ArtworkRoles.ofShape(460, 215))
        assertEquals(ArtworkRole.HERO, ArtworkRoles.ofShape(1920, 620))
        assertEquals(ArtworkRole.HERO, ArtworkRoles.ofShape(3840, 1240))
    }

    @Test
    fun `an unknown size has no role`() {
        assertNull(ArtworkRoles.ofShape(0, 900))
        assertNull(ArtworkRoles.ofShape(600, -1))
    }
}
