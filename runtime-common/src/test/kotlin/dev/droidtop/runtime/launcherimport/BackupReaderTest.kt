package dev.droidtop.runtime.launcherimport

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The backup containers (BackupFiles.kt), built as the launchers write them. */
class BackupReaderTest {
    private val sqlite = "SQLite format 3\u0000rest".toByteArray(Charsets.ISO_8859_1)
    private val prefs = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map><int name=\"a\" value=\"1\" /></map>"

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun `a Nova backup in its write order`() {
        val file = zip(
            "nova.xml" to prefs.toByteArray(),
            "supportDetails.txt" to "x".toByteArray(),
            "nova.db" to sqlite,
        )
        val backup = BackupReader.read(file.inputStream(), "2026-01-01_10-00.novabackup")
        assertEquals(SourceLauncher.NOVA, backup.source)
        assertEquals(prefs, backup.prefsXml)
        assertArrayEquals(sqlite, backup.database)
        assertEquals(listOf("nova.xml", "supportDetails.txt", "nova.db"), backup.entries)
    }

    @Test fun `Nova's older layout`() {
        val backup = BackupReader.read(zip("com.teslacoilsw.launcher_preferences.xml" to prefs.toByteArray(), "launcher.db" to sqlite).inputStream())
        assertEquals(SourceLauncher.NOVA, backup.source)
        assertEquals(prefs, backup.prefsXml)
        assertArrayEquals(sqlite, backup.database)
    }

    @Test fun `a zipped novabackup is unwrapped once`() {
        val inner = zip("nova.xml" to prefs.toByteArray(), "nova.db" to sqlite)
        val backup = BackupReader.read(zip("backup.novabackup" to inner).inputStream())
        assertEquals(SourceLauncher.NOVA, backup.source)
        assertArrayEquals(sqlite, backup.database)
    }

    @Test fun `an Apex backup with Pro's files, stale entries ignored`() {
        val file = zip(
            "apex_settings.bak" to prefs.toByteArray(),
            "apex_data.bak" to sqlite,
            "DrawerGroups.bak" to "<map />".toByteArray(),
            "SwipeActions.bak" to "<map></map>".toByteArray(),
            "old_thing.bak" to "x".toByteArray(),
        )
        val backup = BackupReader.read(file.inputStream())
        assertEquals(SourceLauncher.APEX, backup.source)
        assertEquals("<map />", backup.drawerGroupsXml)
        assertEquals("<map></map>", backup.swipeActionsXml)
        assertArrayEquals(sqlite, backup.database)
    }

    @Test fun `Apex's loose files`() {
        val db = BackupReader.read(sqlite.inputStream(), "apex_data.bak")
        assertEquals(SourceLauncher.APEX, db.source)
        assertNull(db.prefsXml)
        val settings = BackupReader.read(prefs.toByteArray().inputStream(), "apex_settings.bak")
        assertEquals(prefs, settings.prefsXml)
        assertNull(settings.database)
    }

    @Test fun `entry paths are never used, only names`() {
        val backup = BackupReader.read(zip("../../evil/nova.db" to sqlite, "x/nova.xml" to prefs.toByteArray()).inputStream())
        assertEquals(listOf("nova.db", "nova.xml"), backup.entries)
    }

    @Test fun `anything else is refused with a reason`() {
        for (bytes in listOf("hello".toByteArray(), zip("readme.txt" to "x".toByteArray()))) {
            try {
                BackupReader.read(bytes.inputStream())
                fail("read $bytes")
            } catch (e: BackupReader.NotABackup) {
                assertEquals(true, e.message!!.isNotBlank())
            }
        }
    }
}
