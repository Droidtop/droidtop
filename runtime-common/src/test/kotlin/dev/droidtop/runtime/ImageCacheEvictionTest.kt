package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which kept images the cache limit removes (docs/SPEC.md 3, "Storage is checked before it is spent"; Droidtop/tracker#911). */
class ImageCacheEvictionTest {
    private fun kept(digest: String, vararg blobs: String) = ImageCacheEviction.Kept(digest, blobs.toSet())

    private val sizes = mapOf("m1" to 1L, "m2" to 1L, "m3" to 1L, "base" to 100L, "a" to 50L, "b" to 50L, "c" to 50L)

    @Test
    fun `nothing goes while the cache fits`() {
        val images = listOf(kept("one", "m1", "a"), kept("two", "m2", "b"))
        assertEquals(emptyList<Int>(), ImageCacheEviction.victims(images, sizes, limit = 102, protected = emptySet()))
    }

    @Test
    fun `the least recently used go first, and only until it fits`() {
        val images = listOf(kept("old", "m1", "a"), kept("mid", "m2", "b"), kept("new", "m3", "c"))
        // 153 bytes; removing "old" frees 51 and leaves 102.
        assertEquals(listOf(0), ImageCacheEviction.victims(images, sizes, limit = 110, protected = emptySet()))
        assertEquals(listOf(0, 1), ImageCacheEviction.victims(images, sizes, limit = 60, protected = emptySet()))
    }

    @Test
    fun `an image in use by a create or just pulled is never chosen`() {
        val images = listOf(kept("creating", "m1", "a"), kept("old", "m2", "b"), kept("pulled", "m3", "c"))
        assertEquals(listOf(1), ImageCacheEviction.victims(images, sizes, limit = 0, protected = setOf("creating", "pulled")))
    }

    @Test
    fun `a shared layer frees nothing until its last image goes`() {
        val images = listOf(kept("one", "m1", "base", "a"), kept("two", "m2", "base", "b"), kept("three", "m3", "c"))
        // 253 bytes. Removing "one" frees only its own 51 (base is still used by "two"): 202, still over 160.
        // Removing "two" as well frees 151 with the base: 51.
        assertEquals(listOf(0, 1), ImageCacheEviction.victims(images, sizes, limit = 160, protected = emptySet()))
    }

    @Test
    fun `protected images alone over the limit leave every other image chosen`() {
        val images = listOf(kept("old", "m1", "a"), kept("creating", "m2", "base", "b"))
        assertEquals(listOf(0), ImageCacheEviction.victims(images, sizes, limit = 10, protected = setOf("creating")))
    }

    @Test
    fun `limit steps read back and name themselves`() {
        for (limit in ImageCachePolicy.LIMITS) assertEquals(limit, ImageCachePolicy.limitFromId(ImageCachePolicy.limitId(limit)))
        assertEquals(listOf("2 GB", "5 GB", "10 GB", "No limit"), ImageCachePolicy.LIMITS.map(ImageCachePolicy::limitLabel))
        assertTrue(ImageCachePolicy.DEFAULT.maxCacheBytes in ImageCachePolicy.LIMITS)
        // The same units as the size beside it: a cache at its 2 GB limit reads 2.0 GB, not 2.1.
        assertEquals("2.0 GB", dev.droidtop.library.format.ValueFormat.size(ImageCachePolicy.LIMITS.first()!!))
        // Never set, or a value no step has: the default's 2 GB.
        assertEquals(ImageCachePolicy.DEFAULT.maxCacheBytes, ImageCachePolicy.limitFromId(null))
        assertEquals(ImageCachePolicy.DEFAULT.maxCacheBytes, ImageCachePolicy.limitFromId("12345"))
    }
}
