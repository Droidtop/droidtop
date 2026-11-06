package dev.droidtop.runtime

import dev.droidtop.runtime.ContainerSizeCache.Size
import dev.droidtop.runtime.ContainerSizeCache.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a container's size in the Containers list still holds (docs/SPEC.md 3d; Droidtop/tracker#911). */
class ContainerSizeCacheTest {
    private val stopped = Stamp(running = false, digest = "sha256:aa")
    private val running = Stamp(running = true, digest = "sha256:aa")

    private fun ContainerSizeCache.measure(id: String, stamp: Stamp, bytes: Long?) {
        val token = begin(id)!!
        assertTrue(finish(id, token, stamp, bytes?.let { DiskUsage(it) }))
    }

    @Test
    fun `a measured size is kept while nothing changes`() {
        val cache = ContainerSizeCache()
        assertNull(cache.get("a", stopped))
        cache.measure("a", stopped, 2048)
        assertEquals(Size.Known(DiskUsage(2048)), cache.get("a", stopped))
    }

    @Test
    fun `starting or stopping, or another image, measures again`() {
        val cache = ContainerSizeCache()
        cache.measure("a", stopped, 2048)
        assertNull(cache.get("a", running))
        assertNull(cache.get("a", stopped.copy(digest = "sha256:bb")))
    }

    @Test
    fun `one measurement per container at a time, shown as measuring`() {
        val cache = ContainerSizeCache()
        val token = cache.begin("a")
        assertNotNull(token)
        assertNull(cache.begin("a"))
        assertEquals(Size.Measuring, cache.get("a", stopped))
        assertNotNull(cache.begin("b"))
    }

    @Test
    fun `a change during a measurement throws its answer away`() {
        val cache = ContainerSizeCache()
        val token = cache.begin("a")!!
        cache.forget("a") // "Update its software" ran meanwhile
        assertFalse(cache.finish("a", token, stopped, DiskUsage(2048)))
        assertNull(cache.get("a", stopped))
        // And a new measurement may start.
        assertNotNull(cache.begin("a"))
    }

    @Test
    fun `forget drops what was kept`() {
        val cache = ContainerSizeCache()
        cache.measure("a", stopped, 2048)
        cache.forget("a")
        assertNull(cache.get("a", stopped))
    }

    @Test
    fun `the page's own measurement replaces the kept one and outlives an older walk`() {
        val cache = ContainerSizeCache()
        val token = cache.begin("a")!!
        cache.record("a", stopped, DiskUsage(4096))
        assertFalse(cache.finish("a", token, stopped, DiskUsage(1)))
        assertEquals(Size.Known(DiskUsage(4096)), cache.get("a", stopped))
    }

    @Test
    fun `a size that could not be measured is kept as unavailable, not measured on every visit`() {
        val cache = ContainerSizeCache()
        cache.measure("a", stopped, null)
        assertEquals(Size.Unavailable, cache.get("a", stopped))
        assertNull(ContainerSizeCache.label(Size.Unavailable))
    }

    @Test
    fun `deleted containers are dropped`() {
        val cache = ContainerSizeCache()
        cache.measure("a", stopped, 1)
        cache.measure("b", stopped, 1)
        cache.keepOnly(setOf("b"))
        assertNull(cache.get("a", stopped))
        assertEquals(Size.Known(DiskUsage(1)), cache.get("b", stopped))
    }

    @Test
    fun `labels say measuring until known, then the size`() {
        assertEquals("Measuring…", ContainerSizeCache.label(null))
        assertEquals("Measuring…", ContainerSizeCache.label(Size.Measuring))
        assertEquals("512 MB", ContainerSizeCache.label(Size.Known(DiskUsage(512_000_000L))))
        assertEquals("1.5 GB", ContainerSizeCache.label(Size.Known(DiskUsage(1_500_000_000L))))
        assertEquals("0 KB", ContainerSizeCache.label(Size.Known(DiskUsage(0))))
        // Part of the tree could not be read: a floor, said as one.
        assertEquals("at least 1.5 GB", ContainerSizeCache.label(Size.Known(DiskUsage(1_500_000_000L, complete = false))))
    }
}
