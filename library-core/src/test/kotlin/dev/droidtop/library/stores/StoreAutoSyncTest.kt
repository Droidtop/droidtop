package dev.droidtop.library.stores

import dev.droidtop.library.stores.StoreAutoSync.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the Gaming shell's opening reads a store again (docs/SPEC.md 7g, "Stores"). */
class StoreAutoSyncTest {
    private val hour = 3_600_000L

    @Test
    fun `off never asks`() {
        assertFalse(StoreAutoSync.due(Interval.OFF, null, 1000 * hour))
    }

    @Test
    fun `a store never read is due`() {
        assertTrue(StoreAutoSync.due(Interval.DAILY, null, hour))
    }

    @Test
    fun `due once the interval has passed and not before`() {
        assertFalse(StoreAutoSync.due(Interval.SIX_HOURS, 10 * hour, 15 * hour))
        assertTrue(StoreAutoSync.due(Interval.SIX_HOURS, 10 * hour, 16 * hour))
        assertFalse(StoreAutoSync.due(Interval.DAILY, 10 * hour, 33 * hour))
        assertTrue(StoreAutoSync.due(Interval.DAILY, 10 * hour, 34 * hour))
    }

    @Test
    fun `a read dated in the future is due`() {
        assertTrue(StoreAutoSync.due(Interval.DAILY, 50 * hour, 10 * hour))
    }

    @Test
    fun `a copy is named without the store's label`() {
        assertEquals("Backlog", StoreCollections.copyName("Steam: Backlog"))
        assertEquals("Odd", StoreCollections.copyName("Odd"))
        assertTrue(StoreCollections.isImported("import:steam:uc-1"))
        assertFalse(StoreCollections.isImported("8f1c"))
    }
}
