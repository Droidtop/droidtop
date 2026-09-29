package dev.droidtop.library.lutris

import dev.droidtop.library.scraper.ScrapeLookup
import dev.droidtop.library.scraper.foundOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The pure halves of lutris.net's installer list: parse, against the
 * response shape the live call in LutrisInstallerClient.kt's own doc
 * comment confirms, and the bounds that make a request safe before it
 * is ever made -- the slug alphabet and the 2 MiB download bound (the
 * network paths themselves are not exercised here; they need a live
 * host).
 */
class LutrisInstallerClientParseTest {

    private fun row(slug: String, runner: String): JSONObject = JSONObject()
        .put("slug", slug)
        .put("name", "Foo")
        .put("version", "GOG")
        .put("runner", runner)
        .put("script", JSONObject().put("game", JSONObject()))

    private fun response(vararg rows: Any): JSONObject =
        JSONObject().put("count", rows.size).put("results", JSONArray().apply { rows.forEach { put(it) } })

    private fun slugs(lookup: ScrapeLookup<List<LutrisInstaller>>): List<String> =
        lookup.foundOrNull.orEmpty().map { it.slug }

    @Test
    fun `wine installers come first, each group in Lutris's own order`() {
        val lookup = LutrisInstallerClient.parse(
            response(
                row("foo-gog", "wine"),
                row("foo-libretro", "libretro"),
                row("foo-steam", "steam"),
                row("foo-gog-old", "wine"),
            ),
        )
        val installers = lookup.foundOrNull.orEmpty()
        assertEquals(listOf("foo-gog", "foo-gog-old", "foo-libretro", "foo-steam"), installers.map { it.slug })
        assertEquals(listOf(true, true, false, false), installers.map { it.importable })
    }

    @Test
    fun `rows without a script are skipped`() {
        val lookup = LutrisInstallerClient.parse(
            response(
                "just a string, not a row",
                row("foo-no-script", "wine").apply { remove("script") },
                JSONObject().put("slug", "foo-null-script").put("runner", "wine").put("script", JSONObject.NULL),
                row("foo-keep", "wine"),
            ),
        )
        assertEquals(listOf("foo-keep"), slugs(lookup))
    }

    @Test
    fun `no results array or an empty one is NoMatch`() {
        assertEquals(ScrapeLookup.NoMatch, LutrisInstallerClient.parse(JSONObject().put("detail", "Not found")))
        assertEquals(ScrapeLookup.NoMatch, LutrisInstallerClient.parse(response()))
    }

    @Test
    fun `a row's own fields are carried into the installer`() {
        val script = JSONObject().put("game", JSONObject().put("exe", "\$GAMEDIR/Foo.exe"))
        val row = JSONObject()
            .put("slug", "foo-gog")
            .put("name", "Foo (GOG)")
            .put("version", "GOG")
            .put("runner", "wine")
            .put("script", script)
        val installer = LutrisInstallerClient.parse(response(row)).foundOrNull.orEmpty().single()
        assertEquals("foo-gog", installer.slug)
        assertEquals("Foo (GOG)", installer.name)
        assertEquals("GOG", installer.version)
        assertEquals("wine", installer.runner)
        assertSame(script, installer.script)
        assertTrue(installer.importable)
    }

    @Test
    fun `a game id outside Lutris's own slug alphabet is refused before any request`() {
        // These all fail the slug check LutrisInstallerClient runs
        // before it opens a connection, so this test makes no request.
        listOf("Foo Bar", "../escape", "foo/bar", "foo_bar", "foo.bar", "", "a".repeat(129)).forEach { slug ->
            try {
                LutrisInstallerClient.forGame(slug)
                fail("Expected \"$slug\" to be refused")
            } catch (e: LutrisScriptRefused) {
                assertEquals("\"$slug\" is not a Lutris game id", e.message)
            }
        }
    }

    @Test
    fun `the download is bounded at two MiB, never open-ended`() {
        // readBounded enforces this on the live stream, which a JVM
        // test cannot reach; the bound itself is pinned here because it
        // is the security boundary and must not drift silently.
        assertEquals(2 * 1024 * 1024, LutrisInstallerClient.MAX_BYTES)
    }
}
