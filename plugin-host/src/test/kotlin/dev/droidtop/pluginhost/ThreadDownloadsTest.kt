package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The one thread parser (Droidtop/tracker#838). The pages here are written for the tests in the shape forum threads
 * use (XenForo's `bbWrapper` post, bold labels, `<br>` lines, links named by their host), not copied from any site.
 * Real pages are read only from a local folder ([snapshotsFolder]), never kept in the repository.
 */
class ThreadDownloadsTest {
    private fun page(post: String, loggedIn: Boolean = true) =
        """<html data-logged-in="$loggedIn"><head><title>Game [v1.2] [Dev] | F95zone</title></head><body>
        <article class="message"><div class="bbWrapper">$post</div><div class="js-selectToQuoteEnd"></div></article>
        <article class="message"><div class="bbWrapper">A reply with <a href="https://mega.nz/file/reply">MEGA</a></div></article>
        </body></html>"""

    private fun a(url: String, host: String) = """<a href="$url" class="link link--external" rel="nofollow">$host</a>"""

    @Test
    fun `one build for several platforms, hosts in the thread's order, a combined label is one download`() {
        val read = ThreadDownloads.read(
            page(
                """<b>Version</b>: v1.2<br><b>Developer</b>: Someone<br><br>
                <b>DOWNLOAD</b><br>
                <b>Win/Linux</b>: ${a("https://gofile.io/d/w1", "GOFILE")} - ${a("https://mega.nz/file/w2", "MEGA")} - ${a("https://pixeldrain.com/u/w3", "PIXELDRAIN")}<br>
                <b>Mac</b>: ${a("https://mega.nz/file/m1", "MEGA")}<br>
                <b>Android</b>: ${a("https://gofile.io/d/a1", "GOFILE")}<br>""",
            ),
        )
        assertNull(read.problem)
        assertEquals("v1.2", read.version)
        assertEquals(listOf("GOFILE", "MEGA", "PIXELDRAIN", "MEGA", "GOFILE"), read.entries.map { it.host })
        assertEquals(listOf("windows", "linux"), read.entries[0].systems)
        assertEquals(3, read.entries.count { it.systems == listOf("windows", "linux") })
        assertEquals(listOf("mac"), read.entries[3].systems)
        assertEquals(listOf("android"), read.entries[4].systems)
        assertTrue(read.entries.all { it.role == ThreadDownloads.Role.BUILD && it.official })
        assertFalse("a reply's links are not the post's", read.entries.any { it.url.endsWith("/reply") })
    }

    @Test
    fun `several builds are told apart by their heading`() {
        val read = ThreadDownloads.read(
            page(
                """<b>DOWNLOAD</b><br>
                <b>Win/Linux</b>: ${a("https://gofile.io/d/main", "GOFILE")}<br>
                <b>Steam Build</b><br>
                <b>Win/Linux</b>: ${a("https://gofile.io/d/steam", "GOFILE")} - ${a("https://mega.nz/file/steam", "MEGA")}<br>""",
            ),
        )
        assertEquals(listOf("Win/Linux", "Steam Build · Win/Linux", "Steam Build · Win/Linux"), read.entries.map { it.section })
    }

    @Test
    fun `patches and extras are their own items, and a star marks one unofficial`() {
        val read = ThreadDownloads.read(
            page(
                """<b>DOWNLOAD</b><br>
                <b>Win/Linux</b>: ${a("https://gofile.io/d/g", "GOFILE")}<br>
                <b>Android*</b>: ${a("https://mega.nz/file/port", "MEGA")}<br>
                <b>Patches</b>: ${a("https://mega.nz/file/p1", "Walkthrough Mod")}* - ${a("https://mega.nz/file/p2", "Gallery Unlocker")}<br>
                <b>Extras</b>: ${a("https://mega.nz/file/e1", "Soundtrack")}<br>""",
            ),
        )
        val port = read.entries.single { it.url.endsWith("/port") }
        assertFalse("an unofficial port", port.official)
        assertEquals(listOf("android"), port.systems)
        val patches = read.entries.filter { it.role == ThreadDownloads.Role.PATCH }
        assertEquals(listOf("Walkthrough Mod", "Gallery Unlocker"), patches.map { it.label })
        assertEquals(listOf(false, true), patches.map { it.official })
        assertEquals(listOf("Soundtrack"), read.entries.filter { it.role == ThreadDownloads.Role.EXTRA }.map { it.label })
    }

    @Test
    fun `a platform with one host, two platforms on one line, and the end of the section`() {
        val read = ThreadDownloads.read(
            page(
                """<b>DOWNLOAD</b><br>
                <b>Win</b>: ${a("https://pixeldrain.com/u/w", "PIXELDRAIN")} <b>Mac</b>: ${a("https://mega.nz/file/m", "MEGA")}<br>
                <b>Changelog</b><br>
                v1.1: ${a("https://example.com/notes", "notes")}<br>
                ${a("https://www.patreon.com/someone", "Patreon")}<br>""",
            ),
        )
        assertEquals(listOf("PIXELDRAIN", "MEGA"), read.entries.map { it.host })
        assertEquals(listOf(listOf("windows"), listOf("mac")), read.entries.map { it.systems })
    }

    @Test
    fun `nothing to read says why`() {
        assertEquals(ThreadDownloads.Problem.CHECK, ThreadDownloads.read("<html><head><title>Just a moment...</title></head></html>").problem)
        assertEquals(
            ThreadDownloads.Problem.MEMBERS,
            ThreadDownloads.read(page("<b>DOWNLOAD</b><br>You must be registered to see the links<br>", loggedIn = false)).problem,
        )
        assertEquals(ThreadDownloads.Problem.NO_DOWNLOADS, ThreadDownloads.read(page("<b>Overview</b>: a game<br>")).problem)
        assertEquals(ThreadDownloads.Problem.LAYOUT, ThreadDownloads.read("<html data-logged-in=\"true\"><body>other</body></html>").problem)
    }

    @Test
    fun `the answer is the download bundle's entries, with role and official`() {
        val json = ThreadDownloads.toJson(
            ThreadDownloads.read(page("<b>DOWNLOAD</b><br><b>Win/Linux</b>: ${a("https://gofile.io/d/g", "GOFILE")}<br>")),
        )
        val entry = json.getJSONArray("entries").getJSONObject(0)
        assertEquals("GOFILE", entry.getString("host"))
        assertEquals("page", entry.getString("kind"))
        assertEquals("build", entry.getString("role"))
        assertTrue(entry.getBoolean("official"))
        assertEquals(2, entry.getJSONArray("systems").length())
        assertFalse(json.has("problem"))
    }

    /** Real thread pages saved by hand, read where they are and never kept in the repository; skipped without them. */
    private val snapshotsFolder: File? = System.getenv("DROIDTOP_THREAD_SNAPSHOTS")?.let(::File)?.takeIf { it.isDirectory }

    @Test
    fun `saved thread pages read into downloads or a reason`() {
        val folder = snapshotsFolder
        assumeTrue("no DROIDTOP_THREAD_SNAPSHOTS folder", folder != null)
        val pages = folder!!.listFiles { f -> f.name.endsWith(".html") }.orEmpty()
        assumeTrue(pages.isNotEmpty())
        for (file in pages) {
            val read = ThreadDownloads.read(file.readText())
            println("${file.name}: ${read.entries.size} links, ${read.problem?.id ?: "no problem"}")
            assertTrue(file.name, read.entries.isNotEmpty() || read.problem != null)
        }
    }
}
