package dev.droidtop.library.consoles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which copy of the platform databases a build reads: the download only when it is not older than the seed. */
class PlatformDatabaseFreshnessTest {
    private val seed = "2026-10-02T21:29:34Z"

    @Test
    fun aDownloadOlderThanTheSeedIsNotUsed() {
        // The console on build 1386: a download from before the morning's plain-path rows.
        assertFalse(PlatformDatabaseSnapshot.refreshedIsCurrent("2026-10-02T06:00:00Z", seed))
    }

    @Test
    fun aDownloadAsNewOrNewerIsUsed() {
        assertTrue(PlatformDatabaseSnapshot.refreshedIsCurrent(seed, seed))
        assertTrue(PlatformDatabaseSnapshot.refreshedIsCurrent("2026-10-03T08:00:00Z", seed))
    }

    @Test
    fun unknownAgesFollowTheirOwnRule() {
        // A copy from before the marker existed: its age is unknown, the seed is used until the next refresh.
        assertFalse(PlatformDatabaseSnapshot.refreshedIsCurrent(null, seed))
        // A source with no index, or a seed built without one: the download is taken as current.
        assertTrue(PlatformDatabaseSnapshot.refreshedIsCurrent("", seed))
        assertTrue(PlatformDatabaseSnapshot.refreshedIsCurrent("2026-10-01T00:00:00Z", ""))
    }
}
