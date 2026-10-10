package dev.droidtop.library.integrations

import dev.droidtop.library.integrations.AppCatalogs.CachedOffers
import dev.droidtop.library.integrations.AppCatalogs.Expected
import dev.droidtop.library.integrations.AppCatalogs.Offer
import dev.droidtop.library.integrations.AppCatalogs.PackageFacts
import dev.droidtop.library.integrations.AppCatalogs.UpdateState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** apps.catalog@1 (docs/plugin-api.md 3 A12, Droidtop/tracker#261): what droidtop reads from a catalog plugin and decides itself. */
class AppCatalogsTest {
    private val keyA = "aa".repeat(32)
    private val keyB = "bb".repeat(32)

    private fun offer(id: String, code: Long, vararg signers: String) = Offer(id, id, "v$code", code, signers.toSet(), null)

    @Test
    fun `droidtop decides what is an update, and a changed key is shown and not offered`() {
        val cached = listOf(
            CachedOffers("p1", "Catalog one", 1, listOf(offer("org.a", 5, keyA), offer("org.b", 9, keyB), offer("org.c", 2, keyA), offer("org.own", 99))),
            CachedOffers("p2", "Catalog two", 1, listOf(offer("org.a", 7, keyA))),
        )
        val installed = mapOf(
            "org.a" to PackageFacts("org.a", 4, "4", setOf(keyA)),
            "org.b" to PackageFacts("org.b", 3, "3", setOf(keyA)),
            "org.c" to PackageFacts("org.c", 2, "2", setOf(keyA)),
            "org.own" to PackageFacts("org.own", 1, "1", emptySet()),
        )
        val updates = AppCatalogs.updates(cached, installed, ownPackage = "org.own")
        assertEquals(2, updates.size)
        val a = updates[0] as UpdateState.Available
        assertEquals("org.a", a.offer.id)
        assertEquals(7L, a.offer.versionCode)
        assertEquals("p2", a.pluginId)
        assertTrue(updates[1] is UpdateState.KeyDiffers)
    }

    @Test
    fun `an APK is refused unless it is the package, version and key that were offered`() {
        val expected = Expected("org.a", 7, setOf(keyA))
        assertNull(AppPackages.refusal(PackageFacts("org.a", 7, "7", setOf(keyA)), expected, PackageFacts("org.a", 4, "4", setOf(keyA))))
        assertNotNull(AppPackages.refusal(null, expected, null))
        assertNotNull(AppPackages.refusal(PackageFacts("org.evil", 7, "7", setOf(keyA)), expected, null))
        assertNotNull(AppPackages.refusal(PackageFacts("org.a", 6, "6", setOf(keyA)), expected, null))
        assertNotNull(AppPackages.refusal(PackageFacts("org.a", 7, "7", setOf(keyB)), expected, null))
        // The catalog names no key: the installed app's key still has to match.
        assertNotNull(AppPackages.refusal(PackageFacts("org.a", 7, "7", setOf(keyB)), Expected("org.a", 7, emptySet()), PackageFacts("org.a", 4, "4", setOf(keyA))))
        assertNotNull(AppPackages.refusal(PackageFacts("org.a", 7, "7", setOf(keyA)), expected, PackageFacts("org.a", 9, "9", setOf(keyA))))
    }

    @Test
    fun `signing keys are read as lower-case SHA-256 and anything else is dropped`() {
        assertEquals(setOf(keyA), AppCatalogs.signersOf(keyA.uppercase() + ",nonsense," + keyA.chunked(2).joinToString(":")))
        val args = Expected("org.a", 7, setOf(keyA)).toArgs()
        assertEquals(Expected("org.a", 7, setOf(keyA)), Expected.fromArgs(args))
        assertNull(Expected.fromArgs(mapOf(AppCatalogs.ARG_PACKAGE to "not a package")))
    }

    @Test
    fun `a review, an app page and offers are read with their limits`() {
        val review = AppCatalogs.parseReview(
            JSONObject().put("review", JSONObject().put("token", "t1").put("name", "IzzyOnDroid").put("address", "https://apt.izzysoft.de/fdroid/repo")
                .put("fingerprint", keyA).put("fingerprintFromLink", true).put("apps", 1200)),
        )!!
        assertEquals("t1", review.token)
        assertTrue(review.fingerprintFromLink)
        assertNull(AppCatalogs.parseReview(JSONObject().put("review", JSONObject().put("name", "no token"))))
        val page = AppCatalogs.parseApp(
            JSONObject().put(
                "app",
                JSONObject().put("id", "org.a").put("name", "A").put(
                    "versions",
                    JSONArray().put(
                        JSONObject().put("version", "1.0").put("versionCode", 10).put("signers", JSONArray().put(keyA))
                            .put("antiFeatures", JSONArray().put(JSONObject().put("key", "Ads").put("label", "Advertising").put("reason", "shows ads"))),
                    ).put(JSONObject().put("version", "bad")),
                ),
            ),
        )!!
        assertEquals(1, page.versions.size)
        assertEquals("Advertising", page.versions.single().antiFeatures.single().label)
        val (offers, next) = AppCatalogs.parseOffers(
            JSONObject().put("apps", JSONArray().put(JSONObject().put("id", "org.a").put("versionCode", 3)).put(JSONObject().put("id", "x"))).put("next", "org.a"),
        )
        assertEquals(listOf("org.a"), offers.map { it.id })
        assertEquals("org.a", next)
    }
}
