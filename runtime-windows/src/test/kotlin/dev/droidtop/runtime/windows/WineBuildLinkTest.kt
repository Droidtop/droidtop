package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Wine build added by link (docs/SPEC.md 5a): checked by its first bytes before it is downloaded, and named in Downloads. */
class WineBuildLinkTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun onlyATarXzOrTarZstCanBeAWineBuild() {
        assertNull("tar.xz", WineBuilds.packageRefusal(bytes(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00, 0x00, 0x04)))
        assertNull("tar.zst", WineBuilds.packageRefusal(bytes(0x28, 0xB5, 0x2F, 0xFD, 0x04, 0x00)))
        assertTrue(WineBuilds.packageRefusal(bytes(0x1f, 0x8b, 0x08))!!.startsWith("This is a .tar.gz"))
        assertTrue(WineBuilds.packageRefusal("PK\u0003\u0004".toByteArray())!!.startsWith("This is a zip"))
        assertTrue(WineBuilds.packageRefusal("<!DOCTYPE html>".toByteArray())!!.startsWith("That link is a web page"))
        assertEquals("That is not a .wcp package; a Wine build is a tar.xz or tar.zst archive", WineBuilds.packageRefusal(ByteArray(8)))
        assertEquals("That is not a .wcp package; a Wine build is a tar.xz or tar.zst archive", WineBuilds.packageRefusal(ByteArray(0)))
    }

    @Test
    fun aWineBuildDownloadIsNamedAfterItsFile() {
        assertEquals("proton-9.0-arm64ec.wcp", WineBuilds.linkName("https://github.com/o/r/releases/download/v1/proton-9.0-arm64ec.wcp"))
        assertEquals("Wine 9 (test).wcp", WineBuilds.linkName("https://example.org/builds/Wine%209%20(test).wcp?token=abc"))
        assertEquals("example.org", WineBuilds.linkName("https://example.org/"))
    }
}
