package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Test

/** The host paces each metadata source itself (docs/plugin-api.md 3 A3, Droidtop/tracker#568); the plugin is not trusted to. */
class MetadataPacingTest {
    private fun entry(extra: String) = ProvidedPoint("library.metadata", extra = extra)

    @Test
    fun `a source's own rate is taken, capped, and the default stands in for none or nonsense`() {
        assertEquals(10, PluginMetadataProtocol.perMinute(entry("""{"rate":{"perMinute":10}}""")))
        assertEquals(120, PluginMetadataProtocol.perMinute(entry("""{"rate":{"perMinute":1000}}""")))
        assertEquals(30, PluginMetadataProtocol.perMinute(entry("{}")))
        assertEquals(30, PluginMetadataProtocol.perMinute(entry("""{"rate":{"perMinute":0}}""")))
        assertEquals(30, PluginMetadataProtocol.perMinute(entry("""{"rate":"fast"}""")))
    }

    @Test
    fun `calls are spaced to the rate, and the first is not held`() {
        assertEquals(0L, PluginMetadataProtocol.waitMs(null, 5_000L, 30))
        // 30 a minute is one every 2 s: called at 10 s, asked again at 10.5 s, it waits 1.5 s.
        assertEquals(1_500L, PluginMetadataProtocol.waitMs(10_000L, 10_500L, 30))
        assertEquals(0L, PluginMetadataProtocol.waitMs(10_000L, 13_000L, 30))
        assertEquals(500L, PluginMetadataProtocol.waitMs(10_000L, 10_000L, 120))
    }
}
