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
        // The build is named on its own too (Droidtop/tracker#845); the default build has none.
        assertEquals(listOf(null, "Steam Build", "Steam Build"), read.entries.map { it.build })
        val json = ThreadDownloads.toJson(read).getJSONArray("entries")
        assertFalse(json.getJSONObject(0).has("build"))
        assertEquals("Steam Build", json.getJSONObject(1).getString("build"))
    }

    /**
     * The shape of the thread in Droidtop/tracker#838: a build with no heading for three platforms, a "Steam Build" for
     * one, five hosts on every line, then patches and a row of extras, some starred. Two builds for Windows, five
     * mirrors each, and nothing from the patches or the extras taken as a build.
     */
    @Test
    fun `a thread with a default build, a Steam build, patches and extras`() {
        val hosts = listOf("GOFILE" to "https://gofile.io/d/", "MEGA" to "https://mega.nz/file/", "MIXDROP" to "https://mixdrop.ag/f/", "PIXELDRAIN" to "https://pixeldrain.com/u/", "UPLOADHAVEN" to "https://uploadhaven.com/download/")
        fun line(id: String) = hosts.joinToString(" - ") { (name, base) -> a(base + id, name) }
        val read = ThreadDownloads.read(
            page(
                """<b>DOWNLOAD</b><br>
                <b>Win/Linux</b>: ${line("pc")}<br>
                <b>Mac</b>: ${line("mac")}<br>
                <b>Android</b>: ${line("apk")}<br>
                <b>Steam Build</b><br>
                <b>Win/Linux</b>: ${line("steam")}<br>
                <b>Patches</b>: ${a("https://mega.nz/file/patch", "Incest+")}*<br>
                <b>Extras</b>: ${a("https://mega.nz/file/wm", "WALKTHROUGH MOD")}* - ${a("https://mega.nz/file/wt", "WALKTHROUGH")} - ${a("https://mega.nz/file/mm", "MULTI MOD")}* - ${a("https://mega.nz/file/sig", "Fan Sig")} - ${a("https://mega.nz/file/tr", "Russian/French/Italian Translation")}* - ${a("https://mega.nz/file/vid", "Video Enhancement Mod")}*<br>""",
            ),
        )
        assertNull(read.problem)
        val builds = read.entries.filter { it.role == ThreadDownloads.Role.BUILD }
        assertEquals(20, builds.size)
        assertTrue(builds.all { it.official })
        val forWindows = builds.filter { "windows" in it.systems }
        assertEquals(listOf("Win/Linux", "Steam Build · Win/Linux"), forWindows.map { it.section }.distinct())
        assertEquals(listOf(5, 5), forWindows.groupBy { it.section }.values.map { it.size })
        assertEquals(hosts.map { it.first }, forWindows.filter { it.section == "Win/Linux" }.map { it.host })
        assertEquals(listOf("Mac", "Android"), builds.filter { "windows" !in it.systems }.map { it.section }.distinct())
        val rest = read.entries.filter { it.role != ThreadDownloads.Role.BUILD }
        assertEquals(7, rest.size)
        assertEquals(listOf("https://mega.nz/file/patch"), rest.filter { it.role == ThreadDownloads.Role.PATCH }.map { it.url })
        assertEquals(listOf("WALKTHROUGH", "Fan Sig"), rest.filter { it.official }.map { it.label })
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
    @Test
    fun `a first post's platforms, languages and content notes are read, and none it does not state`() {
        // Droidtop/tracker#845: facts beside the links, for the source's metadata.
        val facts = ThreadDownloads.facts(
            listOf("Thread Updated: 2026-10-01", "Censored: No", "Version: 0.5", "OS: Win, Linux, Mac", "Language: English, Japanese"),
        )
        assertEquals(listOf("Windows", "Linux", "macOS"), facts.platforms)
        assertEquals(listOf("English", "Japanese"), facts.languages)
        assertEquals(listOf("Uncensored"), facts.contentNotes)
        assertEquals(listOf("Censored"), ThreadDownloads.facts(listOf("Censorship: Yes (mosaic)")).contentNotes)
        // A platform droidtop has no name for keeps the post's own word.
        assertEquals(listOf("Browser"), ThreadDownloads.facts(listOf("Platform: Browser")).platforms)
        assertEquals(ThreadDownloads.Facts.NONE, ThreadDownloads.facts(listOf("Developer: Someone", "Genre: 2dcg")))
        val json = ThreadDownloads.toJson(ThreadDownloads.Read(null, emptyList(), null, facts)).getJSONObject("facts")
        assertEquals("Uncensored", json.getJSONArray("contentNotes").getString(0))
        assertFalse(ThreadDownloads.toJson(ThreadDownloads.Read(null, emptyList(), null)).has("facts"))
    }
}
