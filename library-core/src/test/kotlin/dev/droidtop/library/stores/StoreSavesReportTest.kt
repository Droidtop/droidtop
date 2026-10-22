package dev.droidtop.library.stores

import dev.droidtop.pluginhost.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** A save sync's line in its job's report (docs/SPEC.md "Jobs and their results"). */
class StoreSavesReportTest {
    @Test
    fun aSyncReadsAsWhatItDid() {
        assertEquals(Outcome.SKIPPED, StoreSaves.reportOutcome(null))
        assertEquals(Outcome.DONE, StoreSaves.reportOutcome(SaveSyncResult("Uploaded 2 files", uploaded = 2)))
        assertEquals(Outcome.SKIPPED, StoreSaves.reportOutcome(SaveSyncResult("Saves are up to date")))
        assertEquals(Outcome.FAILED, StoreSaves.reportOutcome(SaveSyncResult("Steam is not connected", failed = true)))
        // A conflict nobody settled changes nothing and waits for the person.
        assertEquals(Outcome.NEEDS_PICK, StoreSaves.reportOutcome(SaveSyncResult("Saves differ", unresolved = true)))
    }
}
