package dev.droidtop.library.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionOrientationTest {
    @Test
    fun `the lock turns portrait content to landscape and leaves a landscape window alone`() {
        assertTrue(CompanionOrientation.rotateContent("follow", lockLandscape = true, windowLandscape = false))
        assertFalse(CompanionOrientation.rotateContent("follow", lockLandscape = true, windowLandscape = true))
    }

    @Test
    fun `the lock wins over a mode asking for portrait`() {
        assertTrue(CompanionOrientation.rotateContent("portrait", lockLandscape = true, windowLandscape = false))
        assertFalse(CompanionOrientation.rotateContent("portrait", lockLandscape = true, windowLandscape = true))
    }

    @Test
    fun `without the lock follow never turns content and a fixed mode choice does when the shape differs`() {
        assertFalse(CompanionOrientation.rotateContent("follow", lockLandscape = false, windowLandscape = false))
        assertFalse(CompanionOrientation.rotateContent(null, lockLandscape = false, windowLandscape = true))
        assertTrue(CompanionOrientation.rotateContent("landscape", lockLandscape = false, windowLandscape = false))
        assertTrue(CompanionOrientation.rotateContent("portrait", lockLandscape = false, windowLandscape = true))
        assertFalse(CompanionOrientation.rotateContent("landscape_flipped", lockLandscape = false, windowLandscape = true))
    }
}
