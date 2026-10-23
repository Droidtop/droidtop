package dev.droidtop.pluginhost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An app that carries a plugin is told to the person once per version, and never when added or refused (Droidtop/tracker#547). */
class AppInterfaceAnnouncementsTest {
    @Test
    fun `an app not added yet is told once per version`() {
        assertTrue(AppInterfaceAnnouncements.shouldNotice(usable = true, added = false, noticedAt = null, updatedAt = 100L))
        assertFalse("told already, at this version", AppInterfaceAnnouncements.shouldNotice(usable = true, added = false, noticedAt = 100L, updatedAt = 100L))
        assertTrue("an update is told again", AppInterfaceAnnouncements.shouldNotice(usable = true, added = false, noticedAt = 100L, updatedAt = 200L))
    }

    @Test
    fun `an app already added, or one droidtop refuses, is not told`() {
        assertFalse(AppInterfaceAnnouncements.shouldNotice(usable = true, added = true, noticedAt = null, updatedAt = 100L))
        assertFalse(AppInterfaceAnnouncements.shouldNotice(usable = false, added = false, noticedAt = null, updatedAt = 100L))
    }
}
