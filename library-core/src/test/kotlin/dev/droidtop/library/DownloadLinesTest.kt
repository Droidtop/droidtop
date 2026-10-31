package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One grouping of a record's downloads for every page that lists them (Droidtop/tracker#845). */
class DownloadLinesTest {
    private fun d(section: String, host: String, vararg systems: String, role: String = "build") =
        UpdateSources.Download(section, host, host, "", "https://${host.lowercase()}.example/$section", systems.toList(), role = role)

    @Test
    fun `the System choices are the thread's own groupings, and none when everything is one download`() {
        // The owner: Windows/Linux as one download and Android as another, "two options are shown".
        val split = listOf(d("Win/Linux", "MEGA", "windows", "linux"), d("Win/Linux", "GOFILE", "windows", "linux"), d("Android", "MEGA", "android"))
        assertEquals(listOf("windows+linux", "android"), DownloadLines.options(split))
        assertEquals("Windows/Linux", DownloadLines.label("windows+linux"))
        // A patch for one system alone adds no choice: builds decide.
        val patched = split + d("Patch", "MEGA", "windows", role = "patch")
        assertEquals(listOf("windows+linux", "android"), DownloadLines.options(patched))
        // One download for everything: no choice at all.
        assertEquals(emptyList<String>(), DownloadLines.options(listOf(d("Download", "MEGA"))))
        // With no build tagged by system, the other links' sets are used.
        assertEquals(listOf("mac"), DownloadLines.options(listOf(d("Extras", "MEGA", "mac", role = "extra"))))
    }

    @Test
    fun `a pick is the choice named, else the one holding a system kept from before, else Windows then Linux`() {
        val options = listOf("windows+linux", "mac", "android")
        assertEquals("android", DownloadLines.picked(options, "android"))
        assertEquals("windows+linux", DownloadLines.picked(options, "linux"))
        assertEquals("windows+linux", DownloadLines.picked(options, null))
        assertEquals("linux", DownloadLines.picked(listOf("android", "linux"), null))
        assertEquals("android", DownloadLines.picked(listOf("android"), "ios"))
        assertNull(DownloadLines.picked(emptyList(), "windows"))
    }

    @Test
    fun `the lines hold the picked choice's links, droidtop's word on it, and what is for any system apart`() {
        val downloads = listOf(
            d("Win/Linux", "MEGA", "windows", "linux"),
            d("Android", "GOFILE", "android"),
            d("Patch", "MEGA", "windows", role = "patch"),
            d("Walkthrough", "MEGA", role = "extra"),
        )
        val windows = DownloadLines.of(downloads, null)
        assertEquals("windows+linux", windows.picked)
        assertEquals(listOf("Win/Linux", "Patch"), windows.builds.map { it.section })
        assertEquals(listOf("Walkthrough"), windows.apart.map { it.section })
        assertNull(windows.notice)
        val android = DownloadLines.of(downloads, "android")
        assertEquals(listOf("Android"), android.builds.map { it.section })
        assertEquals("This will not be trackable by us. Windows or Linux is recommended.", android.notice)
        // Not differing by system: every build, and the rest apart.
        val plain = DownloadLines.of(listOf(d("Download", "MEGA"), d("Walkthrough", "MEGA", role = "extra")), "windows")
        assertNull(plain.picked)
        assertEquals(listOf("Download"), plain.builds.map { it.section })
        assertEquals(listOf("Walkthrough"), plain.apart.map { it.section })
    }
}
