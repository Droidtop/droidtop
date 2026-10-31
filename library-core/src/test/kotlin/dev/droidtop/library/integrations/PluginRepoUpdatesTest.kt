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

    // ----- pending updates from what the last pass read (Droidtop/tracker#837) -----

    private fun installed(id: String, version: String, origin: String = "maker") = dev.droidtop.pluginhost.PluginRecord(
        manifest = dev.droidtop.pluginhost.PluginManifest(
            id = id,
            origin = origin,
            label = "Label $id",
            description = null,
            version = version,
            kind = dev.droidtop.pluginhost.PluginKind.NATIVE_BUNDLE,
            capabilities = setOf(dev.droidtop.pluginhost.PluginCapability.STATUS_TILE),
            contractVersion = 1,
            requestsRoot = false,
            abis = emptySet(),
            entryClass = "x",
            payload = emptyList(),
        ),
        archiveDigest = "a".repeat(64),
        trust = dev.droidtop.pluginhost.PluginTrustState.APPROVED,
        enabled = true,
        rootApproved = false,
        disabledReason = null,
    )

    private val trusted = mapOf(
        "maker" to dev.droidtop.pluginhost.UserOriginKey(origin = "maker", keyBase64 = "k", source = null, repo = "Owner/plugins"),
        "other" to dev.droidtop.pluginhost.UserOriginKey(origin = "other", keyBase64 = "k2", source = null, repo = "else/where"),
    )

    private fun read(version: String, id: String = "tool", prerelease: Boolean = false, tag: String = "v$version") =
        RepoReleaseRead(tag, prerelease, listOf(RepoBundleRead("$id.droidplugin.tar.xz", id, version, 2048)))

    private fun pending(
        installed: List<dev.droidtop.pluginhost.PluginRecord>,
        reads: Map<String, RepoReleaseRead>,
        includePrereleases: Boolean = false,
        catalogVersions: Map<String, String> = emptyMap(),
    ) = PluginRepoUpdates.pending(installed, trusted, { repo -> reads[repo] }, includePrereleases, catalogVersions)

    @Test
    fun aNewerReleaseOfAnInstalledPluginIsPending() {
        val found = pending(listOf(installed("tool", "1.0")), mapOf("Owner/plugins" to read("1.1")))
        assertEquals(1, found.size)
        val update = found.single()
        assertEquals("tool", update.installed.manifest.id)
        assertEquals("1.1", update.version)
        assertEquals("v1.1", update.tag)
        assertEquals("Owner/plugins", update.repo.repo)
        assertEquals("tool.droidplugin.tar.xz", update.bundle.assetName)
    }

    @Test
    fun theSameVersionIsNotPending() {
        assertTrue(pending(listOf(installed("tool", "1.1")), mapOf("Owner/plugins" to read("1.1"))).isEmpty())
    }

    @Test
    fun anOlderVersionIsNotPending() {
        assertTrue(pending(listOf(installed("tool", "1.2")), mapOf("Owner/plugins" to read("1.1"))).isEmpty())
    }

    @Test
    fun aPluginThatIsNotInstalledIsNotAnUpdate() {
        assertTrue(pending(listOf(installed("other-tool", "1.0")), mapOf("Owner/plugins" to read("1.1"))).isEmpty())
        assertTrue(pending(emptyList(), mapOf("Owner/plugins" to read("1.1"))).isEmpty())
    }

    @Test
    fun aPluginInstalledFromAnotherOriginIsNotThisRepositorysToUpdate() {
        assertTrue(pending(listOf(installed("tool", "1.0", origin = "other")), mapOf("Owner/plugins" to read("1.1"))).isEmpty())
    }

    @Test
    fun aPrereleaseCountsOnlyWhenPrereleasesAreOn() {
        val reads = mapOf("Owner/plugins" to read("1.1-beta", prerelease = true))
        assertTrue(pending(listOf(installed("tool", "1.0")), reads, includePrereleases = false).isEmpty())
        assertEquals("1.1-beta", pending(listOf(installed("tool", "1.0")), reads, includePrereleases = true).single().version)
    }

    @Test
    fun aRepositoryNotReadYetOffersNothing() {
        assertTrue(pending(listOf(installed("tool", "1.0")), emptyMap()).isEmpty())
    }

    @Test
    fun aCatalogOfferWithTheSameOrAHigherVersionIsTheOneShown() {
        val here = listOf(installed("tool", "1.0"))
        val reads = mapOf("Owner/plugins" to read("1.1"))
        assertTrue(pending(here, reads, catalogVersions = mapOf("tool" to "1.1")).isEmpty())
        assertTrue(pending(here, reads, catalogVersions = mapOf("tool" to "1.2")).isEmpty())
        assertEquals("1.1", pending(here, reads, catalogVersions = mapOf("tool" to "1.0.5")).single().version)
        assertEquals("1.1", pending(here, reads, catalogVersions = mapOf("elsewhere" to "9.0")).single().version)
    }

    @Test
    fun onePluginIsOneUpdateAtItsHighestVersionInInstalledOrder() {
        val reads = mapOf(
            "Owner/plugins" to RepoReleaseRead(
                "v2",
                false,
                listOf(
                    RepoBundleRead("b.droidplugin.tar.xz", "b", "2.0", 1),
                    RepoBundleRead("a-old.droidplugin.tar.xz", "a", "1.1", 1),
                    RepoBundleRead("a.droidplugin.tar.xz", "a", "1.3", 1),
                ),
            ),
        )
        val found = pending(listOf(installed("a", "1.0"), installed("b", "1.0")), reads)
        assertEquals(listOf("a" to "1.3", "b" to "2.0"), found.map { it.installed.manifest.id to it.version })
    }

    @Test
    fun whatThePassReadSurvivesItsPreference() {
        val kept = RepoReleaseRead(
            "v1.2",
            true,
            listOf(RepoBundleRead("a.droidplugin.tar.xz", "a", "1.2", 4096), RepoBundleRead("b.droidplugin.tar.xz", "b", "0.3", -1)),
        )
        assertEquals(kept, PluginRepoUpdates.decodeRead(PluginRepoUpdates.encodeRead(kept)))
        assertNull(PluginRepoUpdates.decodeRead(""))
        // A field that would break the line is dropped, not carried into another bundle's line.
        val broken = RepoReleaseRead("v1", false, listOf(RepoBundleRead("a\tb", "a", "1", 1)))
        assertTrue(PluginRepoUpdates.decodeRead(PluginRepoUpdates.encodeRead(broken))!!.bundles.isEmpty())
    }

    @Test
    fun anUpdateLeftForThePersonSaysWhereToInstallIt() {
        assertEquals("Tool 1.2.0 is available. Update it from Updates.", PluginRepoUpdates.describe(RepoUpdateOutcome.Available("Tool", "1.2.0")))
    }
}
