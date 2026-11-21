package dev.droidtop.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A core described by version ranges (Enginehost CapabilityProvider `supportedRanges`, `{min, max}`, inclusive). */
class EnginehostRangesTest {
    private val bundle = EnginehostCapabilities.InstalledBundle(
        bundleId = "renpy-7",
        engine = "renpy",
        engineContext = null,
        pluginVersion = "3",
        runtimeVersion = "7.6.3",
        supportedVersions = emptyList(),
        supportedSeries = emptyList(),
        origin = null,
        supportedRanges = listOf("7.0.0" to "7.8.9"),
    )

    @Test
    fun `a version inside the range is covered, both ends included`() {
        assertTrue(EnginehostCapabilities.covers(bundle, "renpy", null, "7.4.11"))
        assertTrue(EnginehostCapabilities.covers(bundle, "renpy", null, "7.0.0"))
        assertTrue(EnginehostCapabilities.covers(bundle, "renpy", null, "7.8.9"))
    }

    @Test
    fun `a version outside it is not`() {
        assertFalse(EnginehostCapabilities.covers(bundle, "renpy", null, "8.1.0"))
        assertFalse(EnginehostCapabilities.covers(bundle, "renpy", null, "6.99.14"))
    }
}
