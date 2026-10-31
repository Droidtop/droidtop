package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The storage-full line droidtop will not start below (docs/SPEC.md 10c, "Storage full"; Droidtop/tracker#849). */
class StorageGuardTest {
    @Test
    fun `the line is 64 MB`() {
        assertEquals(64_000_000L, StorageGuard.LOW_SPACE_BYTES)
    }

    @Test
    fun `less than the line is low`() {
        assertTrue(StorageGuard.lowSpace(StorageGuard.LOW_SPACE_BYTES - 1))
        // The space #849 was found with.
        assertTrue(StorageGuard.lowSpace(12_000_000L))
        assertTrue(StorageGuard.lowSpace(0L))
    }

    @Test
    fun `the line itself and more are not low`() {
        assertFalse(StorageGuard.lowSpace(StorageGuard.LOW_SPACE_BYTES))
        assertFalse(StorageGuard.lowSpace(StorageGuard.LOW_SPACE_BYTES + 1))
        assertFalse(StorageGuard.lowSpace(Long.MAX_VALUE))
    }
}
