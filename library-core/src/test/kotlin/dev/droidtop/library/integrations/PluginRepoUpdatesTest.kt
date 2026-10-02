package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.PermissionDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The release reading and wording of the plugin-repository update pass (docs/SPEC.md 12a "Plugin repositories"); the network and installer legs are not under test here. */
class PluginRepoUpdatesTest {
    private fun release(id: Long, tag: String, published: String, prerelease: Boolean = false, draft: Boolean = false, assets: List<String>) = """
        {"id":$id,"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"published_at":"$published",
         "assets":[${assets.joinToString(",") { asset(it) }}]}
    """.trimIndent()

    private fun asset(name: String) =
        """{"name":"$name","url":"https://api.github.com/repos/o/r/releases/assets/9","browser_download_url":"https://github.com/o/r/releases/download/x/$name","size":1234,"digest":"sha256:${"a".repeat(64)}"}"""

    private fun list(vararg releases: String) = "[" + releases.joinToString(",") + "]"

    @Test
    fun releasesKeepOnlyBundleAssetsAndDropDrafts() {
        val json = list(
            release(1, "v1", "2026-01-01T00:00:00Z", assets = listOf("a.droidplugin.tar.xz", "notes.txt", "a.apk")),
            release(2, "v2", "2026-02-01T00:00:00Z", draft = true, assets = listOf("b.droidplugin.tar.xz")),
        )
        val parsed = PluginRepoUpdates.parseReleases(json)
        assertEquals(1, parsed.size)
        assertEquals(listOf("a.droidplugin.tar.xz"), parsed.single().bundles.map { it.name })
        assertEquals("a".repeat(64), parsed.single().bundles.single().sha256)
        assertEquals(1234L, parsed.single().bundles.single().size)
    }

    @Test
    fun anUnreadableAnswerIsNoReleasesNotACrash() {
        assertTrue(PluginRepoUpdates.parseReleases("not json").isEmpty())
        assertTrue(PluginRepoUpdates.parseReleases("{\"message\":\"Not Found\"}").isEmpty())
    }

    @Test
    fun theNewestReleaseWithBundlesWinsAndPrereleasesAreIgnoredUnlessAsked() {
        val parsed = PluginRepoUpdates.parseReleases(
            list(
                release(3, "v3-pre", "2026-03-01T00:00:00Z", prerelease = true, assets = listOf("c.droidplugin.tar.xz")),
                release(2, "v2", "2026-02-01T00:00:00Z", assets = listOf("b.droidplugin.tar.xz")),
                release(1, "v1", "2026-01-01T00:00:00Z", assets = listOf("a.droidplugin.tar.xz")),
            ),
        )
        assertEquals("v2", PluginRepoUpdates.newestWithBundles(parsed, includePrereleases = false)?.tag)
        assertEquals("v3-pre", PluginRepoUpdates.newestWithBundles(parsed, includePrereleases = true)?.tag)
    }

    @Test
    fun aReleaseWithoutBundlesIsSkippedForTheNextOne() {
        val parsed = PluginRepoUpdates.parseReleases(
            list(
                release(2, "v2", "2026-02-01T00:00:00Z", assets = listOf("notes.txt")),
                release(1, "v1", "2026-01-01T00:00:00Z", assets = listOf("a.droidplugin.tar.xz")),
            ),
        )
        assertEquals("v1", PluginRepoUpdates.newestWithBundles(parsed, false)?.tag)
        assertNull(PluginRepoUpdates.newestWithBundles(emptyList(), false))
    }

    @Test
    fun outcomesAreWordedForThePerson() {
        assertEquals("Updated Tool to 1.2.0.", PluginRepoUpdates.describe(RepoUpdateOutcome.Updated("Tool", "1.2.0", PermissionDiff(emptyList(), emptyList(), emptyList()))))
        assertTrue(
            PluginRepoUpdates.describe(RepoUpdateOutcome.Updated("Tool", "1.2.0", PermissionDiff(listOf("net.any"), emptyList(), emptyList())))
                .contains("asks for new access"),
        )
        assertTrue(PluginRepoUpdates.describe(RepoUpdateOutcome.NeedsApproval("Tool", "1.2.0")).contains("approve"))
        assertTrue(PluginRepoUpdates.describe(RepoUpdateOutcome.Refused("Tool", "its signature does not verify")).contains("not installed"))
    }

    @Test
    fun everyWayACheckCanEndHasWords() {
        val results = listOf(
            RepoCheckResult.Unchanged,
            RepoCheckResult.Seen("v1"),
            RepoCheckResult.NoBundles(true),
            RepoCheckResult.Deferred("v2"),
            RepoCheckResult.Processed("v2", emptyList(), emptyList()),
            RepoCheckResult.SignInAgain,
            RepoCheckResult.NotFound(signedIn = false),
            RepoCheckResult.NotFound(signedIn = true),
            RepoCheckResult.RateLimited(null),
            RepoCheckResult.Failed("no network"),
        )
        assertTrue(results.all { PluginRepoUpdates.describe(it).isNotBlank() })
        assertTrue(PluginRepoUpdates.describe(RepoCheckResult.NotFound(signedIn = false)).contains("sign in"))
        assertTrue(PluginRepoUpdates.describe(RepoCheckResult.Deferred("v2")).contains("unmetered"))
    }

    @Test
    fun onlyRealWorkIsNotable() {
        assertFalse(PluginRepoUpdates.isNotable(RepoCheckResult.Unchanged))
        assertFalse(PluginRepoUpdates.isNotable(RepoCheckResult.Seen("v1")))
        assertFalse(PluginRepoUpdates.isNotable(RepoCheckResult.Deferred("v2")))
        assertFalse(PluginRepoUpdates.isNotable(RepoCheckResult.Processed("v2", emptyList(), emptyList())))
        assertTrue(PluginRepoUpdates.isNotable(RepoCheckResult.Processed("v2", listOf(RepoUpdateOutcome.NeedsApproval("Tool", "1")), emptyList())))
        assertTrue(PluginRepoUpdates.isNotable(RepoCheckResult.Processed("v2", emptyList(), listOf("a: failed."))))
    }
}
