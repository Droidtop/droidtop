package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    private fun record(disabledReason: String? = null, enabled: Boolean = true) = PluginRecord(
        manifest = manifest(),
        archiveDigest = "b".repeat(64),
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
}
