package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The committed root key is the reference; the release copy only cross-checks it (docs/SPEC.md 12a "Plugin repositories"). No key material is made: the keys are a published test vector and the app's pinned public key. */
class PluginRepoKeysTest {
    private val keyA = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEYP7UuiVanTHJYet0xjVtaMBJuJI7Yfps5mliLmDyn7Z5A/4QCLi8maQa6elWKLxk8vGyDC1+n1F3o8KU1EYimQ=="
    private val keyB get() = PluginOriginKeys.officialKeyBase64()

    private fun file(origin: String, key: String) = """{"origin":"$origin","key":"$key"}""".toByteArray()
    private fun root(origin: String = "acme", key: String = keyA): () -> PluginSourceKeys.FetchResult =
        { PluginSourceKeys.FetchResult.Fetched(PluginSourceKeys.PublishedKey(origin, key)) }

    private val rootMissing: () -> PluginSourceKeys.FetchResult = { PluginSourceKeys.FetchResult.Failed("couldn't fetch it") }

    private fun releases(vararg tags: Pair<String, Boolean>) = "[" + tags.joinToString(",") { (tag, hasKey) ->
        """{"tag_name":"$tag","draft":false,"prerelease":true,"published_at":"2026-0${tag.last()}-01T00:00:00Z","assets":[""" +
            (if (hasKey) """{"name":"droidtop-plugin-key.json","url":"https://api.github.com/a/$tag","browser_download_url":"https://github.com/a/$tag"}""" else """{"name":"x.txt","url":"u","browser_download_url":"b"}""") + "]}"
    } + "]"

    private fun fetch(root: () -> PluginSourceKeys.FetchResult, releases: String?, asset: ByteArray?) =
        PluginRepoKeys.fetch("o/r", null, root, { releases }, { asset })

    @Test
    fun matchingReleaseCopyIsAccepted() {
        val result = fetch(root(), releases("build-main1" to true), file("acme", keyA)) as PluginRepoKeys.Result.Fetched
        assertEquals(PluginRepoKeys.ReleaseCheck.Matches("build-main1"), result.found.release)
        assertTrue(result.found.describeSources("o/r").contains("matches"))
    }

    @Test
    fun noReleaseCopyIsAcceptedAsRootOnly() {
        val none = fetch(root(), releases("build-main1" to false), null) as PluginRepoKeys.Result.Fetched
        assertEquals(PluginRepoKeys.ReleaseCheck.NoCopy, none.found.release)
        val unreachable = fetch(root(), null, null) as PluginRepoKeys.Result.Fetched
        assertEquals(PluginRepoKeys.ReleaseCheck.NoCopy, unreachable.found.release)
    }

    @Test
    fun aDifferentKeyInTheBuildIsRefused() {
        val result = fetch(root(), releases("build-main1" to true), file("acme", keyB)) as PluginRepoKeys.Result.Failed
        assertTrue(result.reason.contains("does not match the key committed"))
        assertTrue(result.reason.contains("tampered"))
    }

    @Test
    fun aDifferentOriginInTheBuildIsRefused() {
        assertTrue(fetch(root(), releases("build-main1" to true), file("other", keyA)) is PluginRepoKeys.Result.Failed)
    }

    @Test
    fun anUnreadableReleaseCopyIsRefusedNotIgnored() {
        assertTrue(fetch(root(), releases("build-main1" to true), "garbage".toByteArray()) is PluginRepoKeys.Result.Failed)
        assertTrue(fetch(root(), releases("build-main1" to true), null) is PluginRepoKeys.Result.Failed)
    }

    @Test
    fun aMissingRootFileIsRefusedEvenWhenTheBuildPublishesOne() {
        val result = fetch(rootMissing, releases("build-main1" to true), file("acme", keyA)) as PluginRepoKeys.Result.Failed
        assertTrue(result.reason.contains("committed at the root"))
    }

    @Test
    fun theNewestReleaseWithACopyIsTheOneCompared() {
        val json = releases("build-a1" to true, "build-b3" to true, "build-c2" to false)
        assertEquals("build-b3", PluginRepoKeys.pickKeyAsset(json)?.releaseTag)
        assertNull(PluginRepoKeys.pickKeyAsset("not json"))
        assertNull(PluginRepoKeys.pickKeyAsset(releases("build-a1" to false)))
    }

    @Test
    fun aChangedRootKeyIsStillRefusedByTheTrustDecision() {
        val found = (fetch(root(key = keyB), null, null) as PluginRepoKeys.Result.Fetched).found
        val decision = PluginRepos.decide("o/r", found.key, mapOf("acme" to UserOriginKey("acme", keyA, null, "o/r")))
        assertTrue(decision is PluginRepos.TrustDecision.KeyChanged)
    }
}
