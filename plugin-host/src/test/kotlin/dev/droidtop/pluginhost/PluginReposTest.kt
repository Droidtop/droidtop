package dev.droidtop.pluginhost

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Plugin repositories (docs/SPEC.md 12a "Plugin repositories"): naming, the key trust decisions and
 * update eligibility. No key material is made here: the two P-256 public keys are a published test
 * vector (RFC 6979 A.2.5) and the app's own pinned public key, and the signature check is replaced
 * where the eligibility rules, not the cryptography, are under test.
 */
class PluginReposTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The RFC 6979 A.2.5 P-256 public key, as SPKI. */
    private val keyA = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEYP7UuiVanTHJYet0xjVtaMBJuJI7Yfps5mliLmDyn7Z5A/4QCLi8maQa6elWKLxk8vGyDC1+n1F3o8KU1EYimQ=="

    /** A second, different valid P-256 public key: the one pinned in the app. */
    private val keyB get() = PluginOriginKeys.officialKeyBase64()

    private fun published(origin: String = "acme", key: String = keyA) = PluginSourceKeys.PublishedKey(origin, key)

    private fun trusted(vararg entries: UserOriginKey): Map<String, UserOriginKey> = entries.associateBy { it.origin }

    @Test
    fun repositoryNamesParseFromTheNameOrABrowserAddress() {
        assertEquals("octocat/hello-world", PluginRepos.parse("octocat/hello-world"))
        assertEquals("octocat/hello-world", PluginRepos.parse("  https://github.com/octocat/hello-world  "))
        assertEquals("octocat/hello-world", PluginRepos.parse("https://github.com/octocat/hello-world.git"))
        assertEquals("octocat/hello-world", PluginRepos.parse("https://github.com/octocat/hello-world/tree/main"))
        assertEquals("octocat/hello-world", PluginRepos.parse("github.com/octocat/hello-world/"))
        assertEquals("Octo-Cat/my.repo_1", PluginRepos.parse("Octo-Cat/my.repo_1"))
    }

    @Test
    fun thingsThatAreNotARepositoryAreRefused() {
        assertNull(PluginRepos.parse(""))
        assertNull(PluginRepos.parse("octocat"))
        assertNull(PluginRepos.parse("a/b/c"))
        assertNull(PluginRepos.parse("octocat/hello world"))
        assertNull(PluginRepos.parse("-bad/name"))
        assertNull(PluginRepos.parse("octocat/.."))
        assertNull(PluginRepos.parse("http://github.com/octocat/hello-world"))
        assertNull(PluginRepos.parse("https://example.com/octocat/hello-world"))
    }

    @Test
    fun theSourceAddressIsTheOneTheExistingKeyFetchUnderstands() {
        assertEquals(
            listOf("https://raw.githubusercontent.com/octocat/hello-world/HEAD/droidtop-plugin-key.json"),
            PluginSourceKeys.keyUrlsFor(PluginRepos.sourceUrl("octocat/hello-world")),
        )
    }

    @Test
    fun anUnknownOriginIsProposed() {
        assertTrue(PluginRepos.decide("octocat/plugins", published(), emptyMap()) is PluginRepos.TrustDecision.Propose)
    }

    @Test
    fun theSameKeyForTheSameRepositoryIsAlreadyTrusted() {
        val decision = PluginRepos.decide("Octocat/Plugins", published(), trusted(UserOriginKey("acme", keyA, null, "octocat/plugins")))
        assertTrue(decision is PluginRepos.TrustDecision.AlreadyTrusted)
    }

    @Test
    fun aKeyTrustedBeforeWithoutARepositoryIsAdoptedNotReplaced() {
        val decision = PluginRepos.decide("octocat/plugins", published(), trusted(UserOriginKey("acme", keyA, "https://example.com", null)))
        assertTrue(decision is PluginRepos.TrustDecision.Adopt)
    }

    @Test
    fun aChangedKeyIsRefusedWithBothFingerprints() {
        val decision = PluginRepos.decide("octocat/plugins", published(key = keyB), trusted(UserOriginKey("acme", keyA, null, "octocat/plugins")))
        decision as PluginRepos.TrustDecision.KeyChanged
        assertEquals(UserOriginKeys.fingerprint(keyA), decision.trustedFingerprint)
        assertEquals(UserOriginKeys.fingerprint(keyB), decision.publishedFingerprint)
        assertFalse(decision.trustedFingerprint == decision.publishedFingerprint)
    }

    @Test
    fun aRepositoryNamingANewOriginIsRefusedAsADisguisedKeyChange() {
        val decision = PluginRepos.decide("octocat/plugins", published(origin = "other", key = keyB), trusted(UserOriginKey("acme", keyA, null, "octocat/plugins")))
        decision as PluginRepos.TrustDecision.OriginChanged
        assertEquals("acme", decision.trustedOrigin)
        assertEquals("other", decision.publishedOrigin)
    }

    @Test
    fun anOriginAlreadyTrustedForAnotherRepositoryIsRefused() {
        val decision = PluginRepos.decide("octocat/fork", published(), trusted(UserOriginKey("acme", keyA, null, "octocat/plugins")))
        decision as PluginRepos.TrustDecision.OriginTaken
        assertEquals("octocat/plugins", decision.byRepo)
    }

    @Test
    fun trustingARepositoryStoresItsNameAndAChangedKeyNeverOverwritesIt() {
        val file = File(tmp.root, "plugin-user-keys.json")
        assertTrue(UserOriginKeys.add(file, "acme", keyA, PluginRepos.sourceUrl("octocat/plugins"), "octocat/plugins") is AddKeyOutcome.Added)
        assertEquals("octocat/plugins", UserOriginKeys.verifiedBy(file, "acme"))
        // Another key for the same origin: add refuses and writes nothing.
        assertTrue(UserOriginKeys.add(file, "acme", keyB, null, "octocat/plugins") is AddKeyOutcome.KeyChanged)
        assertEquals(keyA, UserOriginKeys.load(file)["acme"]?.keyBase64)
        assertEquals(1, PluginRepos.trustedRepos(UserOriginKeys.load(file)).size)
    }

    @Test
    fun aKeyAddedByHandHasNoRepositoryUntilOneIsRecordedForIt() {
        val file = File(tmp.root, "plugin-user-keys.json")
        UserOriginKeys.add(file, "acme", keyA, source = null)
        assertNull(UserOriginKeys.verifiedBy(file, "acme"))
        assertTrue(UserOriginKeys.attachRepo(file, "acme", "octocat/plugins"))
        assertEquals("octocat/plugins", UserOriginKeys.verifiedBy(file, "acme"))
        assertEquals(keyA, UserOriginKeys.load(file)["acme"]?.keyBase64)
        assertFalse(UserOriginKeys.attachRepo(file, "nobody", "octocat/plugins"))
    }

    @Test
    fun removingTheKeyStopsThePluginsBeingVerifiedByTheRepository() {
        val file = File(tmp.root, "plugin-user-keys.json")
        UserOriginKeys.add(file, "acme", keyA, null, "octocat/plugins")
        assertTrue(UserOriginKeys.remove(file, "acme"))
        assertNull(UserOriginKeys.verifiedBy(file, "acme"))
        assertTrue(PluginRepos.trustedRepos(UserOriginKeys.load(file)).isEmpty())
    }

    // ----- update eligibility -----

    private fun peeked(id: String = "acme.tool", origin: String = "acme", version: String) =
        PluginRepos.Peeked(TestPlugins.manifest(id = id, origin = origin) { it.put("version", version) }, ByteArray(0), "sig")

    private fun installed(version: String, origin: String = "acme") =
        TestPlugins.record(TestPlugins.manifest(id = "acme.tool", origin = origin) { it.put("version", version) })

    private fun decide(p: PluginRepos.Peeked, installed: List<PluginRecord>, valid: Boolean = true) =
        PluginRepos.decide(p, "acme", emptyMap(), installed) { valid }

    @Test
    fun aNewerSignedBundleOfAnInstalledPluginIsApplied() {
        val decision = decide(peeked(version = "1.1.0"), listOf(installed("1.0.0")))
        assertTrue(decision is PluginRepos.UpdateDecision.Apply)
    }

    @Test
    fun anOlderOrEqualVersionIsNeverInstalled() {
        assertEquals(PluginRepos.UpdateDecision.UpToDate, decide(peeked(version = "1.0.0"), listOf(installed("1.0.0"))))
        assertEquals(PluginRepos.UpdateDecision.UpToDate, decide(peeked(version = "0.9.0"), listOf(installed("1.0.0"))))
    }

    @Test
    fun aPluginThatIsNotInstalledIsOfferedNeverInstalledByThePass() {
        assertEquals(PluginRepos.UpdateDecision.NotInstalled, decide(peeked(version = "1.0.0"), emptyList()))
    }

    @Test
    fun aBundleThatDoesNotVerifyAgainstTheTrustedKeyIsRefused() {
        val decision = decide(peeked(version = "2.0.0"), listOf(installed("1.0.0")), valid = false)
        assertTrue(decision is PluginRepos.UpdateDecision.Refused)
    }

    @Test
    fun aBundleForAnotherOriginIsRefusedWhateverItsSignatureSays() {
        val decision = decide(peeked(origin = "other", id = "other.tool", version = "2.0.0"), listOf(installed("1.0.0")))
        assertTrue(decision is PluginRepos.UpdateDecision.Refused)
    }

    @Test
    fun aPluginInstalledFromAnotherOriginIsNotReplacedByTheRepository() {
        val decision = decide(peeked(version = "2.0.0"), listOf(installed("1.0.0", origin = "elsewhere")))
        assertTrue(decision is PluginRepos.UpdateDecision.Refused)
    }

    @Test
    fun anUpdateThatAddsAPermissionSaysSoSoThePersonIsAskedAfterwards() {
        val old = TestPlugins.record(TestPlugins.manifest(id = "acme.tool") { it.put("version", "1.0.0") })
        val newer = PluginRepos.Peeked(
            TestPlugins.manifest(id = "acme.tool") {
                it.put("version", "1.1.0")
                it.put("permissions", TestPlugins.arr(TestPlugins.obj("id" to "net.any")))
            },
            ByteArray(0),
            "sig",
        )
        val decision = decide(newer, listOf(old)) as PluginRepos.UpdateDecision.Apply
        assertFalse(decision.newAccess.isEmpty)
    }
}
