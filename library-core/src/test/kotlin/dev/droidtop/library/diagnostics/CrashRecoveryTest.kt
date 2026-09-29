package dev.droidtop.library.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashRecoveryTest {
    @Test
    fun earlyCrashesCountUpAndALongLivedProcessResets() {
        assertEquals(1, CrashRecovery.countAfterCrash(0, 5_000))
        assertEquals(2, CrashRecovery.countAfterCrash(1, 30_000))
        assertEquals(3, CrashRecovery.countAfterCrash(2, 59_999))
        assertEquals(0, CrashRecovery.countAfterCrash(2, CrashRecovery.WINDOW_MS))
    }

    @Test
    fun theThresholdsFollowTheSpec() {
        assertFalse(CrashRecovery.safeModeFor(1))
        assertTrue(CrashRecovery.safeModeFor(2))
        assertFalse(CrashRecovery.settingsFor(2))
        assertTrue(CrashRecovery.settingsFor(3))
    }

    @Test
    fun theTenNewestNotesAreKept() {
        val names = (1L..13L).map { "crash-$it.txt" } + listOf("scan.log", "desktop-container.log")
        val deleted = CrashRecovery.notesToDelete(names)
        assertEquals(listOf("crash-3.txt", "crash-2.txt", "crash-1.txt"), deleted)
    }
}
