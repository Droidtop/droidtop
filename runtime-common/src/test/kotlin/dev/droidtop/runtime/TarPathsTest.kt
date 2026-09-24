package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TarPathsTest {
    @Test
    fun `names are made relative to the image root or rejected`() {
        assertEquals("usr/bin", TarPaths.relative("./usr/bin/"))
        assertEquals("etc", TarPaths.relative("/etc"))
        assertEquals("a/b", TarPaths.relative("a//./b"))
        assertEquals("", TarPaths.relative("./"))
        assertNull(TarPaths.relative("a/../../b"))
        assertNull(TarPaths.relative("../etc/passwd"))
        assertNull(TarPaths.relative("a/.."))
        assertNull(TarPaths.relative("a\u0000b"))
    }

    @Test
    fun `ancestors, parent and child`() {
        assertEquals(listOf("a", "a/b"), TarPaths.ancestors("a/b/c"))
        assertEquals(emptyList<String>(), TarPaths.ancestors("a"))
        assertEquals("a/b", TarPaths.parent("a/b/c"))
        assertEquals("", TarPaths.parent("a"))
        assertEquals("x", TarPaths.child("", "x"))
        assertEquals("a/x", TarPaths.child("a", "x"))
    }
}
