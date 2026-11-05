package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plugin download's job row title (Droidtop/tracker#739). */
class DownloadTitleTest {
    @Test
    fun `a hash-named file in the plugin's data reads as data, with no path`() {
        assertEquals("Download data", DownloadTitle.of(null, "8f6bd4fd62d6e3bb.json"))
        assertEquals("Download data", DownloadTitle.of("  ", "dl/8f6bd4fd62d6e3bb.json"))
    }

    @Test
    fun `a named file keeps its name`() {
        assertEquals("Download index-v2.json", DownloadTitle.of(null, "index-v2.json"))
    }

    @Test
    fun `the plugin's own title wins, kept to one short line`() {
        assertEquals("F-Droid repository index", DownloadTitle.of("F-Droid\nrepository   index", "8f6bd4fd62d6e3bb.json"))
        val long = DownloadTitle.of("word ".repeat(30), "x.bin")
        assertTrue(long.length <= DownloadTitle.MAX && long.endsWith("…") && !long.contains("wor…"))
    }
}
