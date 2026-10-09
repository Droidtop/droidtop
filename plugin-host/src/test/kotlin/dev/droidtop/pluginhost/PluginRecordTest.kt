package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip coverage for the bug found on the rig (dq-plugins-01):
 * approving a freshly-installed plugin wrote disabledReason as
 * [JSONObject.NULL] via [PluginRecord.toJson], and the very next read
 * showed "Disabled after a crash: null" -- with no crash -- because the
 * old parser used `optString(key).takeIf { it.isNotBlank() }` instead of
 * [JSONObject.isNull]. Note this test alone would NOT have caught the
 * original bug: this module's testImplementation org.json:json jar (a
 * different implementation from the one Android ships on-device) already
 * returns "" for a NULL-valued key, so `optString` "worked" here even
 * before the fix. It is still worth keeping as a correctness contract
 * for the round trip, and as a pointer to why this class of bug needs a
 * real device, not just a JVM unit test, to show up.
 */
class PluginRecordTest {
    private fun manifest(entryClass: String? = "dev.droidtop.samples.statustile.StatusTilePlugin") = PluginManifest(
        id = "droidtop.sample-statustile",
        origin = "droidtop",
        label = "Sample status tile",
        description = null,
        version = "1.0.0",
        kind = PluginKind.NATIVE_BUNDLE,
        capabilities = setOf(PluginCapability.STATUS_TILE),
        contractVersion = PLUGIN_CONTRACT_VERSION,
        requestsRoot = false,
        abis = emptySet(),
        entryClass = entryClass,
        payload = listOf(PluginPayloadFile("classes.jar", "a".repeat(64))),
    )

    private fun record(
        disabledReason: String? = null,
        enabled: Boolean = true,
        approvedKeySha256: String = "",
    ) = PluginRecord(
        manifest = manifest(),
        archiveDigest = "b".repeat(64),
        approvedKeySha256 = approvedKeySha256,
        trust = PluginTrustState.APPROVED,
        enabled = enabled,
        rootApproved = false,
        disabledReason = disabledReason,
    )

    @Test
    fun `a null disabledReason round-trips as null, not the string null`() {
        val roundTripped = PluginRecord.fromJson(record(disabledReason = null).toJson())!!
        assertNull(roundTripped.disabledReason)
        assertTrue(roundTripped.runnable())
    }

    @Test
    fun `a real disabledReason round-trips as that exact text`() {
        val roundTripped = PluginRecord.fromJson(record(disabledReason = "call timed out").toJson())!!
        assertEquals("call timed out", roundTripped.disabledReason)
        assertTrue(!roundTripped.runnable())
    }

    @Test
    fun `the technical detail behind a plain disabledReason round-trips, and a record without one reads as null`() {
        val withDetail = record(disabledReason = "The plugin crashed while loading.").copy(disabledDetail = "load failed: boom")
        assertEquals("load failed: boom", PluginRecord.fromJson(withDetail.toJson())!!.disabledDetail)
        assertNull(PluginRecord.fromJson(record().toJson())!!.disabledDetail)
        assertNull(PluginRecord.fromJson(record().toJson().apply { remove("disabledDetail") })!!.disabledDetail)
    }

    @Test
    fun `manifest subscribedEvents round-trips, not silently dropped to empty`() {
        // Found and fixed 2026-09-27 (dq-pluginui-01): toJson()/fromJson()
        // never carried this field at all, so PluginStore.installed()'s
        // every read (via this exact round trip) reset a plugin's real,
        // signed subscribedEvents back to empty -- no plugin's
        // PluginEvent hook could ever fire, regardless of what its
        // manifest declared. This is the actual repro: build a manifest
        // that DOES subscribe, round-trip it the same way PluginStore
        // does, and confirm the subscription survives.
        val withEvent = manifest().copy(subscribedEvents = setOf("default_player_changed"))
        val roundTripped = PluginRecord.fromJson(record().copy(manifest = withEvent).toJson())!!
        assertEquals(setOf("default_player_changed"), roundTripped.manifest.subscribedEvents)
    }

    @Test
    fun `the approved key fingerprint round-trips, not silently dropped to empty`() {
        // Same class of bug as subscribedEvents (above): the field that
        // decides whether an update's approval carries over (docs/SPEC.md
        // 12a "Trust over updates") must survive the record.json round
        // trip PluginStore does on every read, or every update of a
        // plugin installed by a pre-carry-over build would start PENDING
        // on a device that approved it under a known key.
        val roundTripped = PluginRecord.fromJson(record(approvedKeySha256 = "c".repeat(64)).toJson())!!
        assertEquals("c".repeat(64), roundTripped.approvedKeySha256)
    }

    @Test
    fun `a record with no key fingerprint field (pre-carry-over build) reads as empty, not garbage`() {
        val json = record().toJson().apply { remove("approvedKeySha256") }
        val roundTripped = PluginRecord.fromJson(json)!!
        assertEquals("", roundTripped.approvedKeySha256)
    }

    @Test
    fun `a null manifest description and entryClass round-trip as null`() {
        val withNullDescription = manifest(entryClass = null)
        val roundTripped = PluginRecord.fromJson(record().copy(manifest = withNullDescription).toJson())!!
        assertNull(roundTripped.manifest.description)
        assertNull(roundTripped.manifest.entryClass)
    }

    @Test
    fun `JSONObject NULL is read as null through PluginManifest fromJson directly, not the string null`() {
        val json = JSONObject().apply {
            put("id", "droidtop.sample-statustile")
            put("origin", "droidtop")
            put("label", "Sample status tile")
            put("kind", "native_bundle")
            put("capabilities", org.json.JSONArray(listOf("status_tile")))
            put("contractVersion", PLUGIN_CONTRACT_VERSION)
            put("description", JSONObject.NULL)
            put("entryClass", JSONObject.NULL)
            put(
                "payload",
                org.json.JSONArray(listOf(JSONObject().put("path", "classes.jar").put("sha256", "a".repeat(64)))),
            )
        }
        val manifest = PluginManifest.fromJson(json)!!
        assertNull(manifest.description)
        assertNull(manifest.entryClass)
    }

    @Test
    fun `a plugin an older droidtop disabled is switched back on by a newer one, and only once per build`() {
        val stopped = record(disabledReason = "The plugin crashed while loading.", enabled = false).copy(disabledDetail = "raw", disabledBuild = 1535)
        val again = stopped.afterHostUpdate(1649)!!
        assertTrue(again.enabled)
        assertNull(again.disabledReason)
        assertNull(again.disabledDetail)
        assertNull("the same build keeps its verdict", stopped.afterHostUpdate(1535))
        assertNotNull("a record from before the field existed is tried once", stopped.copy(disabledBuild = 0).afterHostUpdate(1649))
        assertNull("a plugin the person turned off has no reason and is left alone", record(enabled = false).afterHostUpdate(1649))
        assertNull("an unknown build changes nothing", stopped.afterHostUpdate(0))
    }

    @Test
    fun `the build that disabled a plugin survives the record file`() {
        val back = PluginRecord.fromJson(record(disabledReason = "x", enabled = false).copy(disabledBuild = 1535).toJson())!!
        assertEquals(1535, back.disabledBuild)
    }
}
