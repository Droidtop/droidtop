package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WineAllocatorTest {
    private val lib = "/data/app/lib/x86_64/libmimalloc.so"

    @Test
    fun `the allocator goes first and the other preloads keep their order`() {
        assertEquals("$lib:/img/libandroid-sysvshm.so:/lib/libevshim.so", WineAllocator.withPreload("/img/libandroid-sysvshm.so:/lib/libevshim.so", lib))
    }

    @Test
    fun `an empty preload list gets the allocator alone`() {
        assertEquals(lib, WineAllocator.withPreload("", lib))
        assertEquals(lib, WineAllocator.withPreload(null, lib))
    }

    @Test
    fun `an allocator already on the list is not doubled`() {
        assertEquals("$lib:/a.so", WineAllocator.withPreload("/a.so:$lib", lib))
    }

    @Test
    fun `empty entries left by the launcher are dropped`() {
        assertEquals("$lib:/b.so", WineAllocator.withPreload(":/b.so:", lib))
    }

    @Test
    fun `Android 10 and later can load it and older versions cannot`() {
        assertTrue(WineAllocator.supported(29))
        assertTrue(WineAllocator.supported(35))
        assertFalse(WineAllocator.supported(28))
    }
}
