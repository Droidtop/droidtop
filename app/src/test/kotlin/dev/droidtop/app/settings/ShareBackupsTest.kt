package dev.droidtop.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareBackupsTest {
    @Test
    fun aDeviceFolderIsAPlainNameWithTheStartOfItsId() {
        assertEquals("Retroid-Pocket-5-0123abcd", ShareBackups.folderName("Retroid Pocket 5", "0123abcdef987654"))
        assertEquals("Retroid-Pocket-5", ShareBackups.folderName("Retroid Pocket 5", null))
        assertEquals("device", ShareBackups.folderName("  /../ ", ""))
        assertFalse(ShareBackups.folderName("a/../b\\c", null).contains('/'))
        assertFalse(ShareBackups.folderName("a/../b\\c", null).contains(".."))
    }

    @Test
    fun namesSayWhenAndWhetherLocked() {
        // 2026-11-05 06:30:15 UTC.
        val at = 1_793_860_215_000L
        assertEquals("droidtop-backup-20261105-063015.zip", ShareBackups.fileName(at, locked = false))
        assertEquals("droidtop-backup-20261105-063015.zip.locked", ShareBackups.fileName(at, locked = true))
        assertEquals("2026-11-05 06:30 UTC", ShareBackups.stampLabel("droidtop-backup-20261105-063015.zip.locked"))
        assertNull(ShareBackups.stampLabel("holiday.zip"))
        assertTrue(ShareBackups.isBackup("droidtop-backup-20261105-063015.zip"))
        assertTrue(ShareBackups.isBackup("droidtop-backup-20261105-063015.zip.locked"))
        assertFalse(ShareBackups.isBackup(".droidtop-backup-20261105-063015.zip.ab12.part"))
    }
}
