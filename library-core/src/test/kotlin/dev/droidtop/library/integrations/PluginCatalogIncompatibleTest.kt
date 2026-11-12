package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.PLUGIN_MANIFEST_CONTRACT_VERSION
import dev.droidtop.pluginhost.PLUGIN_MANIFEST_MIN_CONTRACT_VERSION
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCatalogIncompatibleTest {
    private fun release(contract: Int?) = PluginCatalogRelease(
        version = "1.0.0",
        stream = "stable",
        publishedAt = null,
        manifestSha256 = "a".repeat(64),
        bundle = PluginCatalogBundle("x.droidplugin.tar.xz", "https://example.invalid/x", 1, "b".repeat(64)),
        contractVersion = contract,
    )

    @Test
    fun `a release older than this droidtop runs says so, as Root modules 1_0_0 did`() {
        // Droidtop/tracker#932: listed with a red tag, never installed by Install all.
        val why = release(PLUGIN_MANIFEST_MIN_CONTRACT_VERSION - 1).incompatibility()
        assertNotNull(why)
        assertTrue(why!!.contains("older droidtop"))
    }

    @Test
    fun `a release newer than this droidtop runs asks for a droidtop update`() {
        assertTrue(release(PLUGIN_MANIFEST_CONTRACT_VERSION + 1).incompatibility()!!.contains("newer droidtop"))
    }

    @Test
    fun `a release in range, or one the index says nothing about, is offered`() {
        assertNull(release(PLUGIN_MANIFEST_CONTRACT_VERSION).incompatibility())
        assertNull(release(PLUGIN_MANIFEST_MIN_CONTRACT_VERSION).incompatibility())
        assertNull(release(null).incompatibility())
    }
}
