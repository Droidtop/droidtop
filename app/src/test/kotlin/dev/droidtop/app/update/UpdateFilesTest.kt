package dev.droidtop.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which files in the downloads folder the self-update deletes (Droidtop/tracker#445). */
class UpdateFilesTest {
    private val folder = listOf(
        "droidtop-1734.apk", "droidtop-1735.apk", "droidtop-1736.apk.part", "droidtop-1736.apk.part.meta",
        "droidtop-1744.apk.part", "droidtop-1745.apk", "droidtop-1746.apk.part", "somegame.zip", "droidtop-x.apk",
    )

    @Test
    fun `only the updater's own files carry a build number`() {
        assertEquals(1746L, UpdateFiles.buildOf("droidtop-1746.apk.part"))
        assertEquals(1736L, UpdateFiles.buildOf("droidtop-1736.apk.part.meta"))
        assertEquals(1745L, UpdateFiles.buildOf("droidtop-1745.apk"))
        assertNull(UpdateFiles.buildOf("somegame.zip"))
        assertNull(UpdateFiles.buildOf("droidtop-x.apk"))
        assertNull(UpdateFiles.buildOf("droidtop-1746.apk.bak"))
    }

    @Test
    fun `with nothing newer, the installed build and everything older go`() {
        val gone = UpdateFiles.stale(folder, installed = 1745, target = null)
        assertEquals(
            listOf("droidtop-1734.apk", "droidtop-1735.apk", "droidtop-1736.apk.part", "droidtop-1736.apk.part.meta",
                "droidtop-1744.apk.part", "droidtop-1745.apk"),
            gone,
        )
    }

    @Test
    fun `fetching a newer build keeps that build's partial file and drops older ones`() {
        val gone = UpdateFiles.stale(folder, installed = 1745, target = 1746)
        assertFalse("droidtop-1746.apk.part" in gone)
        assertTrue("droidtop-1744.apk.part" in gone)
        assertTrue("droidtop-1745.apk" in gone)
        assertFalse("somegame.zip" in gone)
    }

    @Test
    fun `a partial file of a build between installed and target is not resumed`() {
        assertTrue(UpdateFiles.isStale(build = 1746, installed = 1745, target = 1748))
        assertFalse(UpdateFiles.isStale(build = 1748, installed = 1745, target = 1748))
        assertFalse(UpdateFiles.isStale(build = 1749, installed = 1745, target = 1748))
    }
}
