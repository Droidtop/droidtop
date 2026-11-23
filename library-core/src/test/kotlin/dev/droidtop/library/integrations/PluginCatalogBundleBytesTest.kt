package dev.droidtop.library.integrations

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginCatalogBundleBytesTest {
    private val limit = 512L * 1024 * 1024

    @Test
    fun `a declared size is what the download counts against`() {
        // F-Droid 0.1.5's bundle: shown as "0/536 MB" while the limit stood in for its size.
        assertEquals(101_556L, PluginCatalog.bundleBytes(101_556L))
    }

    @Test
    fun `no size or one past the limit falls back to the limit`() {
        assertEquals(limit, PluginCatalog.bundleBytes(0L))
        assertEquals(limit, PluginCatalog.bundleBytes(-1L))
        assertEquals(limit, PluginCatalog.bundleBytes(limit + 1))
    }
}
