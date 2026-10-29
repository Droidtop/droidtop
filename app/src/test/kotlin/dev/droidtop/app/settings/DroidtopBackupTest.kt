package dev.droidtop.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a "Restore droidtop" archive may put where (Droidtop/tracker#625): only its own files, never outside them. */
class DroidtopBackupTest {
    @Test
    fun `the archive's own files go where they came from`() {
        assertEquals("files/library/games/ab/cd.json", DroidtopBackup.destinationOf("files/library/games/ab/cd.json"))
        assertEquals("files/play-log.json", DroidtopBackup.destinationOf("files/play-log.json"))
        assertEquals("databases/droidtop-play-history.db", DroidtopBackup.destinationOf("databases/droidtop-play-history.db"))
        assertEquals("databases/droidtop-rom-cache.db", DroidtopBackup.destinationOf("databases/droidtop-rom-cache.db"))
    }

    @Test
    fun `anything else, and anything climbing out, is skipped`() {
        assertNull(DroidtopBackup.destinationOf("files/library/games/../../shared_prefs/x.xml"))
        assertNull(DroidtopBackup.destinationOf("../evil"))
        assertNull(DroidtopBackup.destinationOf("/data/data/x"))
        assertNull(DroidtopBackup.destinationOf("files/plugins/some/plugin.bin"))
        assertNull(DroidtopBackup.destinationOf("files/library/games/"))
        assertNull(DroidtopBackup.destinationOf("databases/droidtop-library-index.db"))
        assertNull(DroidtopBackup.destinationOf("databases/../shared_prefs/x.xml"))
        assertNull(DroidtopBackup.destinationOf("files\\library\\games\\x"))
        assertNull(DroidtopBackup.destinationOf(DroidtopBackup.SETTINGS))
    }
}
